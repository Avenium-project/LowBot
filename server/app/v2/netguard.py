"""Egress guard against SSRF for every server-side fetch made on a bot's behalf.

* Only http/https, no credentials in URLs.
* The host is resolved and EVERY resolved address must be public, unless the
  operator enabled ``ALLOW_PRIVATE_NETWORK`` (explicit LAN policy).
* Cloud metadata addresses are always blocked, even with LAN access.
* Redirects are followed manually and each hop is re-checked.
* The connection is pinned to the vetted IP to avoid DNS rebinding between
  check and connect (Host header / SNI keep the original name).
"""

from __future__ import annotations

import asyncio
import ipaddress
import socket
from typing import Dict, List, Optional, Tuple
from urllib.parse import urljoin, urlsplit

import httpx

ALWAYS_BLOCKED = [
    ipaddress.ip_network("169.254.169.254/32"),  # AWS/GCP/Azure/OpenStack metadata
    ipaddress.ip_network("fd00:ec2::254/128"),   # AWS IMDS IPv6
    ipaddress.ip_network("100.100.100.200/32"),  # Alibaba metadata
    ipaddress.ip_network("0.0.0.0/8"),
]
METADATA_HOSTS = {"metadata.google.internal", "metadata", "instance-data", "metadata.azure.com"}


class EgressDenied(Exception):
    pass


def _is_public(ip: ipaddress._BaseAddress) -> bool:
    return not (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved
                or ip.is_unspecified or (isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped
                                         and not _is_public(ip.ipv4_mapped)))


def check_ip(ip_text: str, allow_private: bool) -> None:
    ip = ipaddress.ip_address(ip_text)
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    if any(ip in net for net in ALWAYS_BLOCKED):
        raise EgressDenied(f"Address {ip} is a blocked metadata/unspecified address.")
    if not allow_private and not _is_public(ip):
        raise EgressDenied(f"Address {ip} is not public; LAN access requires an explicit policy.")


async def resolve(host: str) -> List[str]:
    loop = asyncio.get_running_loop()
    try:
        infos = await loop.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    except socket.gaierror as exc:
        raise EgressDenied(f"Cannot resolve {host}.") from exc
    return sorted({i[4][0] for i in infos})


async def vet_url(url: str, *, allow_private: bool, allowlist: Tuple[str, ...] = ()) -> Tuple[str, str]:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https"):
        raise EgressDenied("Only http and https URLs are allowed.")
    if parts.username or parts.password:
        raise EgressDenied("Credentials in URLs are not allowed.")
    host = (parts.hostname or "").lower().rstrip(".")
    if not host:
        raise EgressDenied("URL has no host.")
    if host in METADATA_HOSTS:
        raise EgressDenied("Cloud metadata hosts are blocked.")
    if allowlist and not any(host == a or host.endswith("." + a) for a in allowlist):
        raise EgressDenied(f"Host {host} is not in the egress allowlist.")
    try:
        ips = [str(ipaddress.ip_address(host))]
    except ValueError:
        ips = await resolve(host)
    for ip in ips:
        check_ip(ip, allow_private)
    return host, ips[0]


class _PinnedTransport(httpx.AsyncHTTPTransport):
    def __init__(self, pins: Dict[str, str], **kw):
        super().__init__(**kw)
        self.pins = pins

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        host = request.url.host
        ip = self.pins.get(host)
        if ip and host != ip:
            request.extensions = {**request.extensions, "sni_hostname": host}
            request.url = request.url.copy_with(host=ip)
        return await super().handle_async_request(request)


async def guarded_request(method: str, url: str, *, allow_private: bool, allowlist: Tuple[str, ...] = (),
                          headers: Optional[Dict[str, str]] = None, content: Optional[bytes] = None,
                          json_body=None, timeout: float = 20.0, max_redirects: int = 5,
                          max_bytes: int = 2_000_000) -> Tuple[httpx.Response, bytes, str]:
    """Perform a request with per-hop SSRF checks. Returns (response, body, final_url)."""
    current = url
    for _ in range(max_redirects + 1):
        host, ip = await vet_url(current, allow_private=allow_private, allowlist=allowlist)
        transport = _PinnedTransport({host: ip})
        async with httpx.AsyncClient(transport=transport, timeout=timeout, follow_redirects=False) as client:
            async with client.stream(method, current, headers=headers, content=content, json=json_body) as resp:
                if resp.is_redirect and resp.headers.get("location"):
                    current = urljoin(current, resp.headers["location"])
                    if method.upper() not in ("GET", "HEAD"):
                        method, content, json_body = "GET", None, None
                    continue
                body = b""
                async for chunk in resp.aiter_bytes():
                    body += chunk
                    if len(body) > max_bytes:
                        body = body[:max_bytes]
                        break
                return resp, body, current
    raise EgressDenied("Too many redirects.")


def validate_provider_url(url: str, kind: str) -> None:
    """Static checks for operator-configured model endpoints (the operator,
    not a bot, chooses these, so local endpoints are legitimate)."""
    if not url:
        return
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password \
            or parts.query or parts.fragment:
        raise ValueError("Use an http(s) base URL without credentials, query or fragment.")
    if (parts.hostname or "").lower() in METADATA_HOSTS or parts.hostname == "169.254.169.254":
        raise ValueError("Metadata endpoints cannot be used as model providers.")
