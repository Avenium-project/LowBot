"""Live provider integration (real model, real network).

Skipped with an explicit reason when credentials are not configured — a
skipped live test is NOT a pass. Configure:
  LIVE_PROVIDER_KIND (openai_responses|chat_completions|xai|openrouter|local)
  LIVE_PROVIDER_MODEL, LIVE_PROVIDER_API_KEY, optional LIVE_PROVIDER_BASE_URL
"""

import asyncio
import os

import pytest

from tests.v2.conftest import make_runtime

KIND = os.getenv("LIVE_PROVIDER_KIND")
MODEL = os.getenv("LIVE_PROVIDER_MODEL")
KEY = os.getenv("LIVE_PROVIDER_API_KEY")

pytestmark = pytest.mark.skipif(not (KIND and MODEL and (KEY or KIND == "local")),
                                reason="no live provider credentials configured")


def test_live_test_connection_and_tool_loop(tmp_path):
    rt = make_runtime(tmp_path)
    prov = rt.services["providers"].upsert({"kind": KIND, "name": "live", "default_model": MODEL, "api_key": KEY,
                                            **({"base_url": os.environ["LIVE_PROVIDER_BASE_URL"]} if os.getenv("LIVE_PROVIDER_BASE_URL") else {})})
    result = asyncio.run(rt.services["providers"].test(prov["id"]))
    assert result["checks"]["text"]["ok"], result
    bot = rt.services["bots"].create({"name": "Live", "provider_profile_id": prov["id"], "tools": ["memory.*"],
                                      "instructions": "When asked to remember something, call memory.save, then confirm in one sentence."})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    tid = rt.services["tasks"].post_user_message(conv["id"], "Remember that my favourite colour is teal.")["tasks"][0]["id"]
    asyncio.run(rt.engine.drain(180))
    task = rt.services["tasks"].get_task(tid)
    assert task["status"] == "completed", task
    if result["capabilities"].get("tools"):
        assert rt.core.db.scalar("SELECT COUNT(*) FROM memories") >= 1
