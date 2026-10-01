"""Voice adapter against a fake OpenAI-compatible audio endpoint (mock transport)."""

import asyncio

import httpx
import pytest

from app.v2.providers.base import ProviderError
from app.v2.voice import VoiceService


def test_transcribe_and_speak_contract(rt):
    seen = []

    def handler(req: httpx.Request):
        seen.append(req)
        if req.url.path.endswith("/audio/transcriptions"):
            assert b'name="model"' in req.content and b"stt-model-x" in req.content
            return httpx.Response(200, json={"text": "cześć"})
        return httpx.Response(200, content=b"ID3audio")
    prov = rt.services["providers"].upsert({"kind": "chat_completions", "name": "voice", "base_url": "https://audio.test/v1",
                                            "api_key": "sk-voice-key-1234567890"})
    rt.services["providers"].transports[prov["id"]] = httpx.MockTransport(handler)
    v = VoiceService(rt)
    with pytest.raises(ProviderError):
        asyncio.run(v.transcribe(b"x", "a.webm", "audio/webm", "pl"))
    v.save({"profile_id": prov["id"], "stt_model": "stt-model-x", "tts_model": "tts-x", "tts_voice": "alloy-x"})
    assert asyncio.run(v.transcribe(b"x", "a.webm", "audio/webm", "pl")) == "cześć"
    assert asyncio.run(v.speak("hello")) == b"ID3audio"
    assert seen[0].headers["authorization"] == "Bearer sk-voice-key-1234567890"
