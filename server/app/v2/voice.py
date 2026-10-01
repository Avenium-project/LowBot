"""Swappable STT/TTS adapter over OpenAI-compatible audio endpoints.

``POST {base}/audio/transcriptions`` (multipart) and ``POST {base}/audio/speech``.
Model ids are configured by the owner (never guessed). Real-time voice
conversation is NOT implemented.
"""

from __future__ import annotations

from typing import Any, Dict, Optional

import httpx

from app.v2.providers.base import ProviderError, classify_http


class VoiceService:
    def __init__(self, runtime):
        self.rt, self.core = runtime, runtime.core

    def settings(self) -> Dict[str, Any]:
        return self.core.kv_get("voice", {}) or {}

    def save(self, data: Dict[str, Any]) -> Dict[str, Any]:
        cur = self.settings()
        for k in ("profile_id", "stt_model", "tts_model", "tts_voice"):
            if k in data:
                cur[k] = (data[k] or "").strip()
        self.core.kv_set("voice", cur)
        return cur

    def _endpoint(self):
        cfg = self.settings()
        prov = self.rt.services["providers"]
        profile = prov.get(cfg.get("profile_id") or "") if cfg.get("profile_id") else None
        if not profile or profile["kind"] == "scripted_mock":
            raise ProviderError("config", "Configure a voice provider profile in Settings → Voice.")
        row = self.core.db.one("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", (profile["id"],))
        key = self.core.secrets.get(row["api_key_secret_id"]) if row else None
        headers = {"Authorization": f"Bearer {key}"} if key else {}
        return profile, headers, cfg, prov.transports.get(profile["id"])

    async def transcribe(self, audio: bytes, filename: str, mime: str, language: Optional[str]) -> str:
        profile, headers, cfg, transport = self._endpoint()
        if not cfg.get("stt_model"):
            raise ProviderError("config", "Set the speech-to-text model id in Settings → Voice.")
        data = {"model": cfg["stt_model"]}
        if language:
            data["language"] = language[:5]
        async with httpx.AsyncClient(timeout=120, transport=transport) as c:
            r = await c.post(f"{profile['base_url'].rstrip('/')}/audio/transcriptions", headers=headers,
                             data=data, files={"file": (filename, audio, mime)})
        if not r.is_success:
            raise classify_http(r.status_code)
        return (r.json() or {}).get("text", "")

    async def speak(self, text: str) -> bytes:
        profile, headers, cfg, transport = self._endpoint()
        if not cfg.get("tts_model") or not cfg.get("tts_voice"):
            raise ProviderError("config", "Set the text-to-speech model and voice in Settings → Voice.")
        async with httpx.AsyncClient(timeout=120, transport=transport) as c:
            r = await c.post(f"{profile['base_url'].rstrip('/')}/audio/speech", headers=headers,
                             json={"model": cfg["tts_model"], "voice": cfg["tts_voice"], "input": text[:4000]})
        if not r.is_success:
            raise classify_http(r.status_code)
        return r.content
