"""Small shared helpers: ids, clocks, hashing, redaction."""

from __future__ import annotations

import hashlib
import json
import re
import secrets
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Mapping

# Tests replace this to control time deterministically.
_clock: Callable[[], float] = time.time


def set_clock(fn: Callable[[], float]) -> None:
    global _clock
    _clock = fn


def reset_clock() -> None:
    set_clock(time.time)


def now_ts() -> float:
    return _clock()


def now_dt() -> datetime:
    return datetime.fromtimestamp(_clock(), tz=timezone.utc)


def now_iso() -> str:
    return iso(now_dt())


def iso(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def iso_in(seconds: float) -> str:
    return iso(now_dt() + timedelta(seconds=seconds))


def parse_iso(value: str) -> datetime:
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    dt = datetime.fromisoformat(value)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:20]}"


def token(nbytes: int = 32) -> str:
    return secrets.token_urlsafe(nbytes)


def sha256(text: str | bytes) -> str:
    if isinstance(text, str):
        text = text.encode("utf-8")
    return hashlib.sha256(text).hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=str)


def args_hash(tool: str, arguments: Any) -> str:
    return sha256(f"{tool}\n{canonical_json(arguments)}")


def slugify(text: str) -> str:
    text = re.sub(r"[^a-zA-Z0-9]+", "-", text.strip().lower()).strip("-")
    return text or "bot"


_SENSITIVE_KEYS = ("api_key", "apikey", "authorization", "cookie", "credential", "password", "secret", "token", "passphrase")
_SENSITIVE_TEXT = re.compile(
    r"(?i)\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|authorization|password|secret|token|bearer)\b"
    r"(\s*[:=]\s*|\s+)['\"]?([A-Za-z0-9_\-\.=/+]{8,})"
)
_KEY_SHAPES = re.compile(r"\b(sk-[A-Za-z0-9_\-]{16,}|xai-[A-Za-z0-9_\-]{16,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})\b")


def redact(value: Any, key: str = "") -> Any:
    """JSON-safe copy with credential-like fields/values removed (best effort)."""
    if key and any(m in key.lower() for m in _SENSITIVE_KEYS):
        return "[REDACTED]"
    if isinstance(value, Mapping):
        return {str(k): redact(v, str(k)) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact(v, key) for v in value]
    if isinstance(value, str):
        value = _SENSITIVE_TEXT.sub(lambda m: f"{m.group(1)}{m.group(2)}[REDACTED]", value)
        return _KEY_SHAPES.sub("[REDACTED]", value)
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return str(value)


def truncate(text: str, limit: int) -> str:
    if text is None:
        return ""
    return text if len(text) <= limit else text[: limit - 20] + f"\n…[truncated {len(text) - limit + 20} chars]"
