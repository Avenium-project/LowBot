import asyncio

from tests.v2.conftest import mock_profile


def test_message_to_reply(rt):
    prof = mock_profile(rt, script=[{"when": "hello", "reply": "Hi! ({{last}})"}])
    bot = rt.services["bots"].create({"name": "Researcher", "provider_profile_id": prof["id"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    out = rt.services["tasks"].post_user_message(conv["id"], "hello there", client_msg_id="c1")
    asyncio.run(rt.engine.drain())
    msgs = rt.services["tasks"].messages(conv["id"])
    assert [m["author_type"] for m in msgs] == ["user", "bot"]
    assert msgs[1]["text"] == "Hi! (hello there)"
    task = rt.services["tasks"].get_task(out["tasks"][0]["id"])
    assert task["status"] == "completed"
