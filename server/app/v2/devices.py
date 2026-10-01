"""Device pairing for native clients (Android / Windows).

Flow: the signed-in owner requests a one-time pairing code (short TTL). The
code and the server URL are shown as text and as a QR payload — never the
owner token. The native app exchanges the code once for a per-device token
that it stores in the OS secure store and sends as ``Authorization: Bearer``.
Each device can be revoked individually.
"""

from __future__ import annotations

import secrets
from typing import Any, Dict, List, Optional

from app.v2.core import Core
from app.v2.util import iso_in, new_id, now_iso, sha256

DEVICE_PREFIX = "odd_"
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no 0/O/1/I


class PairingError(ValueError):
    pass


class DeviceService:
    def __init__(self, core: Core):
        self.core, self.db = core, core.db

    def create_code(self, server_url: str) -> Dict[str, Any]:
        code = "".join(secrets.choice(ALPHABET) for _ in range(8))
        expires = iso_in(self.core.settings.pairing_code_ttl_s)
        with self.db.tx():
            self.db.execute("DELETE FROM pairing_codes WHERE expires_at < ? OR used_at IS NOT NULL", (now_iso(),))
            self.db.insert("pairing_codes", {"code_hash": sha256(code), "expires_at": expires, "created_at": now_iso()})
            self.core.audit("device.pairing_code", actor_type="user")
        display = f"{code[:4]}-{code[4:]}"
        return {"code": display, "expires_at": expires, "server_url": server_url,
                "qr_payload": f"opendots://pair?server={server_url}&code={display}"}

    def exchange(self, code: str, name: str, platform: str) -> Dict[str, Any]:
        normalized = (code or "").replace("-", "").replace(" ", "").upper()
        if len(normalized) != 8:
            raise PairingError("Invalid pairing code.")
        if platform not in ("android", "windows", "web", "ios", "other"):
            platform = "other"
        with self.db.tx():
            n = self.db.execute(
                "UPDATE pairing_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
                (now_iso(), sha256(normalized), now_iso())).rowcount
            if n != 1:
                raise PairingError("Pairing code is invalid, expired or already used.")
            device_id = new_id("dev")
            tok = DEVICE_PREFIX + secrets.token_urlsafe(32)
            self.db.insert("devices", {"id": device_id, "name": (name or platform)[:60], "platform": platform,
                                       "token_hash": sha256(tok), "created_at": now_iso(), "last_seen_at": now_iso()})
            self.core.audit("device.paired", actor_type="device", actor_id=device_id, platform=platform)
        return {"device_id": device_id, "token": tok}

    def authenticate(self, tok: str) -> Optional[Dict[str, Any]]:
        if not tok.startswith(DEVICE_PREFIX) or len(tok) > 100:
            return None
        row = self.db.one("SELECT * FROM devices WHERE token_hash = ? AND revoked_at IS NULL", (sha256(tok),))
        if row:
            last = row["last_seen_at"] or ""
            if last[:16] != now_iso()[:16]:  # at most one write per minute
                with self.db.tx():
                    self.db.execute("UPDATE devices SET last_seen_at = ? WHERE id = ?", (now_iso(), row["id"]))
        return row

    def list(self) -> List[Dict[str, Any]]:
        return self.db.all("SELECT id, name, platform, created_at, last_seen_at, revoked_at FROM devices ORDER BY created_at")

    def revoke(self, device_id: str) -> None:
        with self.db.tx():
            n = self.db.execute("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                                (now_iso(), device_id)).rowcount
            if not n:
                raise PairingError("Unknown or already revoked device.")
            self.core.audit("device.revoke", actor_type="user", device_id=device_id)
