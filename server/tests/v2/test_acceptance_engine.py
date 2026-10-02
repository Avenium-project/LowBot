"""Acceptance tests A–E, K, L (engine level).

Model behaviour comes from the SCRIPTED MOCK adapter (deterministic, offline).
These tests prove orchestration/durability logic, not model quality.
"""

import asyncio
import json
import re

import httpx
import pytest

from app.v2.engine import Engine, LeaseLost
from app.v2.providers.base import ModelResponse, ToolCall
from app.v2.providers.scripted import ScriptedAdapter
from app.v2.tools.registry import ToolSpec, wire_name
from app.v2.util import args_hash, iso_in, now_iso
from tests.v2.conftest import make_runtime, mock_profile


def drain(rt, timeout=20):
    asyncio.run(rt.engine.drain(timeout))


def last_user(req):
    return next((m for m in reversed(req.messages) if m["role"] in ("user", "tool")), {"content": ""})


def bot_named(req):
    return re.search(r"You are (.+?) \(@", req.system).group(1)


@pytest.mark.parametrize("stored_cap", [0, 1, 24])
def test_runs_continue_past_legacy_step_limits(tmp_path, monkeypatch, stored_cap):
    monkeypatch.setenv("RUN_MAX_STEPS", "1")
    rt = make_runtime(tmp_path)
    calls = 0

    def brain(req):
        nonlocal calls
        calls += 1
        if calls <= 40:
            return ModelResponse("", [ToolCall(f"list-{calls}", wire_name("workspace.list"), {})])
        return ModelResponse("All forty steps completed", [])

    try:
        prof = mock_profile(rt, fn=brain)
        bot = rt.services["bots"].create({"name": "Long task", "provider_profile_id": prof["id"],
                                          "budget": {"max_steps": 1}})
        tasks = rt.services["tasks"]
        conv = tasks.private_conversation(bot["id"])
        task = tasks.post_user_message(conv["id"], "Complete forty steps")["tasks"][0]
        run = tasks.run_for_task(task["id"])
        assert run["max_steps"] == 0  # Old per-bot and environment settings are ignored.
        rt.core.db.update("runs", {"id": run["id"]}, {"max_steps": stored_cap})
        drain(rt)
        assert tasks.get_task(task["id"])["status"] == "completed"
        assert calls == 41
        assert rt.core.db.scalar("SELECT COUNT(*) FROM run_steps WHERE run_id = ? AND kind = 'model'",
                                 (run["id"],)) == 41
    finally:
        rt.core.db.close()


# --------------------------------------------------------------------- A ---
def test_A_fifty_bots_persist_and_idle_bots_cost_nothing(tmp_path):
    rt = make_runtime(tmp_path)
    adapter = ScriptedAdapter(script=[])
    prof = mock_profile(rt, script=[])
    rt.services["providers"].overrides[prof["id"]] = adapter
    ids = [rt.services["bots"].create({"name": f"Bot {i}", "provider_profile_id": prof["id"]})["id"] for i in range(50)]
    drain(rt)
    assert adapter.calls == []  # no task -> no model call
    assert rt.core.db.scalar("SELECT COUNT(*) FROM computers") == 0  # no desktop/browser kept alive
    rt.core.db.close()

    rt2 = make_runtime(tmp_path)  # "restart"
    bots = rt2.services["bots"].list()
    assert len(bots) == 50 and {b["id"] for b in bots} == set(ids)
    assert all(b["status"] == "idle" for b in bots)
    rt2.core.db.close()


# --------------------------------------------------------------------- B ---
def team_brain(req):
    """Deterministic per-bot behaviour keyed on the bot name in the system prompt."""
    me = bot_named(req)
    last = last_user(req)
    text = last.get("content") or ""
    done_tools = [m["name"] for m in req.messages if m["role"] == "tool"]
    if me == "Manager":
        if not done_tools:
            return ModelResponse("", [ToolCall("c1", wire_name("task.delegate"), {
                "bot": "researcher", "instructions": "Research topic X and get it written and reviewed.",
                "expected_output": "Reviewed summary"})])
        return ModelResponse(f"REPORT: {json.loads(text.split(chr(10), 1)[1].rsplit(chr(10), 1)[0])['result']}", [])
    if me == "Researcher":
        if not done_tools:
            return ModelResponse("", [ToolCall("r1", wire_name("task.delegate"), {
                "bot": "writer", "instructions": "Write a summary of findings F1,F2.", "expected_output": "Draft"})])
        if done_tools == [wire_name("task.delegate")]:
            return ModelResponse("", [ToolCall("r2", wire_name("task.delegate"), {
                "bot": "reviewer", "instructions": "Review the draft: DRAFT(F1,F2)", "expected_output": "Verdict"})])
        return ModelResponse("Final: DRAFT(F1,F2) reviewed OK", [])
    if me == "Writer":
        return ModelResponse("DRAFT(F1,F2)", [])
    if me == "Reviewer":
        return ModelResponse("OK", [])
    raise AssertionError(me)


def test_B_delegation_chain_each_subtask_has_own_run_and_trace(rt):
    prof = mock_profile(rt, fn=team_brain)
    bots = {n: rt.services["bots"].create({"name": n, "provider_profile_id": prof["id"]})
            for n in ("Manager", "Researcher", "Writer", "Reviewer")}
    conv = rt.services["tasks"].create_conversation("group", [b["id"] for b in bots.values()], "Team")
    out = rt.services["tasks"].post_user_message(conv["id"], "@manager please produce a reviewed summary")
    drain(rt)
    root = rt.services["tasks"].get_task(out["tasks"][0]["id"])
    assert root["bot_id"] == bots["Manager"]["id"] and root["status"] == "completed"
    assert root["result_text"] == "REPORT: Final: DRAFT(F1,F2) reviewed OK"
    tree = rt.services["tasks"].task_tree(root["id"])
    research = tree["children"][0]
    assert research["task"]["bot_id"] == bots["Researcher"]["id"]
    kids = {c["task"]["bot_id"]: c for c in research["children"]}
    assert set(kids) == {bots["Writer"]["id"], bots["Reviewer"]["id"]}
    for node in [tree, research, *kids.values()]:
        assert node["run"]["status"] == "completed"           # own run & state
        assert node["task"]["result_text"]                     # own result
    assert kids[bots["Writer"]["id"]]["task"]["result_text"] == "DRAFT(F1,F2)"
    assert len(rt.core.db.all("SELECT * FROM handoffs WHERE status = 'completed'")) == 3  # delegation trace
    assert len({n["run"]["id"] for n in [tree, research, *kids.values()]}) == 4
    # The user can see the handoffs in the conversation.
    texts = [m["text"] for m in rt.services["tasks"].messages(conv["id"])]
    assert any("Manager → Researcher" in t for t in texts)
    assert any("Researcher → Writer" in t for t in texts)


def test_B_loop_guards_cycle_depth_and_budget(rt):
    prof = mock_profile(rt, script=[])
    a = rt.services["bots"].create({"name": "A", "provider_profile_id": prof["id"]})
    b = rt.services["bots"].create({"name": "B", "provider_profile_id": prof["id"]})
    tasks = rt.services["tasks"]
    t_a = tasks.get_task(tasks.create_task(bot_id=a["id"], conversation_id=None, requester_type="user",
                                           requester_id="u", instructions="x")["id"])
    t_b = tasks.get_task(tasks.create_task(bot_id=b["id"], conversation_id=None, requester_type="bot",
                                           requester_id=a["id"], instructions="y", parent_task=t_a)["id"])
    from app.v2.tasks import TaskError
    with pytest.raises(TaskError, match="cycle"):
        tasks.guard_delegation(t_b, a["id"])  # B -> A while A waits on B
    rt.settings.max_delegation_depth = 1
    with pytest.raises(TaskError, match="depth"):
        tasks.guard_delegation(t_b, rt.services["bots"].create({"name": "C"})["id"])
    rt.settings.max_delegation_depth = 9
    rt.settings.max_tasks_per_correlation = 2
    with pytest.raises(TaskError, match="budget"):
        tasks.guard_delegation(t_b, rt.services["bots"].create({"name": "D"})["id"])


def test_B_ping_pong_mentions_are_bounded(rt):
    """Two bots that always @mention each other cannot loop forever."""
    def brain(req):
        me = bot_named(req)
        other = "pong" if me == "Ping" else "ping"
        return ModelResponse(f"@{other} your turn", [])
    prof = mock_profile(rt, fn=brain)
    p1 = rt.services["bots"].create({"name": "Ping", "provider_profile_id": prof["id"]})
    p2 = rt.services["bots"].create({"name": "Pong", "provider_profile_id": prof["id"]})
    conv = rt.services["tasks"].create_conversation("group", [p1["id"], p2["id"]])
    rt.settings.max_tasks_per_correlation = 6
    rt.services["tasks"].post_user_message(conv["id"], "@ping start")
    drain(rt)
    total = rt.core.db.scalar("SELECT COUNT(*) FROM tasks")
    assert total <= 6
    assert rt.core.db.scalar("SELECT COUNT(*) FROM events WHERE type = 'delegation.rejected'") >= 1


def test_B_bot_create_cannot_escalate(rt):
    prof = mock_profile(rt, script=[])
    boss = rt.services["bots"].create({"name": "Boss", "provider_profile_id": prof["id"], "can_create_bots": True,
                                       "tools": ["memory.*", "bot.create"]})
    from app.v2.bots import BotError
    with pytest.raises(BotError, match="tools"):
        rt.services["bots"].create({"name": "Evil", "tools": ["http.post"]}, created_by_bot=boss)
    child = rt.services["bots"].create({"name": "Kid", "tools": ["memory.search"]}, created_by_bot=boss)
    assert child["can_create_bots"] is False and child["created_by_bot_id"] == boss["id"]
    plain = rt.services["bots"].create({"name": "Plain"})
    with pytest.raises(BotError, match="not allowed"):
        rt.services["bots"].create({"name": "X"}, created_by_bot=plain)


def test_B_hierarchy_rules(rt):
    bots = rt.services["bots"]
    ceo = bots.create({"name": "CEO", "org_role": "ceo"})
    head = bots.create({"name": "Head", "org_role": "head", "reports_to": ceo["id"]})
    worker = bots.create({"name": "W", "org_role": "worker", "reports_to": head["id"]})
    rt.core.kv_set("hierarchy_enforced", True)
    assert bots.can_delegate(ceo, worker) is None
    assert "only delegate" in bots.can_delegate(worker, ceo)
    from app.v2.bots import BotError
    with pytest.raises(BotError, match="cycle"):
        bots.update(ceo["id"], {"reports_to": worker["id"]})


# --------------------------------------------------------------------- C ---
def test_C_work_continues_without_client_and_duplicate_posts_do_not_double(tmp_path):
    rt = make_runtime(tmp_path)
    adapter = ScriptedAdapter(script=[{"reply": "done: {{last}}"}])
    prof = mock_profile(rt, script=[])
    rt.services["providers"].overrides[prof["id"]] = adapter
    bot = rt.services["bots"].create({"name": "Phone", "provider_profile_id": prof["id"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    # Two clients (phone + desktop) replay the same outbox entry.
    r1 = rt.services["tasks"].post_user_message(conv["id"], "long job", client_msg_id="phone-1")
    r2 = rt.services["tasks"].post_user_message(conv["id"], "long job", client_msg_id="phone-1")
    assert r2["duplicate"] and r1["message_id"] == r2["message_id"]
    # Two workers race for the same queue.
    rt2 = make_runtime(tmp_path)
    rt2.services["providers"].overrides[prof["id"]] = adapter

    async def both():
        await asyncio.gather(rt.engine.drain(), rt2.engine.drain())
    asyncio.run(both())
    assert len(adapter.calls) == 1
    # Client re-opens later and fetches missed state; no extra execution.
    msgs = rt2.services["tasks"].messages(conv["id"])
    assert [m["text"] for m in msgs] == ["long job", "done: long job"]
    events = rt2.core.events_after(0, 1000, conv["id"])
    assert sum(1 for e in events if e["type"] == "task.completed") == 1
    rt.core.db.close()
    rt2.core.db.close()


# --------------------------------------------------------------------- D ---
def crash(rt, run_id):
    """Simulate process death: the lease simply expires; nothing is cleaned up."""
    with rt.core.db.tx():
        rt.core.db.execute("UPDATE runs SET lease_expires_at = ? WHERE id = ?", ("2000-01-01T00:00:00.000000Z", run_id))


def test_D_restart_mid_task_resumes_from_checkpoint(tmp_path):
    rt = make_runtime(tmp_path)
    script = [{"when": "start", "call": {"name": "memory.save", "arguments": {"content": "step one"}}},
              {"after_tool": "memory.save", "call": {"name": "workspace.write", "arguments": {"path": "a.txt", "content": "x"}}},
              {"after_tool": "workspace.write", "reply": "all done"}]
    adapter = ScriptedAdapter(script=script)
    prof = mock_profile(rt, script=[])
    rt.services["providers"].overrides[prof["id"]] = adapter
    bot = rt.services["bots"].create({"name": "Worker", "provider_profile_id": prof["id"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    out = rt.services["tasks"].post_user_message(conv["id"], "start")
    run_id = out["tasks"][0]["run_id"]

    gate = asyncio.Event()
    orig = rt.services["tools"].get(bot, "workspace.write").executor

    async def hang(ctx, a):
        gate.set()
        await asyncio.sleep(3600)

    rt.services["tools"]._static["workspace.write"].executor = hang

    async def first_worker():
        run = rt.engine.claim()
        t = rt.engine._spawn(run)
        await gate.wait()
        rt.engine._reasons[run_id] = "lease_lost"  # die without writing anything
        t.cancel()
        await asyncio.gather(t, return_exceptions=True)
    asyncio.run(first_worker())
    rt.services["tools"]._static["workspace.write"].executor = orig
    steps_before = rt.core.db.all("SELECT kind, tool_name, status FROM run_steps WHERE run_id = ? ORDER BY seq", (run_id,))
    assert [s["status"] for s in steps_before] == ["completed", "completed", "completed", "executing"]
    crash(rt, run_id)

    rt2 = make_runtime(tmp_path)  # new process
    adapter2 = ScriptedAdapter(script=script)
    rt2.services["providers"].overrides[prof["id"]] = adapter2
    drain(rt2)
    run = rt2.core.db.one("SELECT * FROM runs WHERE id = ?", (run_id,))
    assert run["status"] == "completed" and run["attempt"] == 1
    # Only the remaining model step was executed after recovery (memory.save not repeated).
    assert len(adapter2.calls) == 1
    assert rt2.core.db.scalar("SELECT COUNT(*) FROM memories") == 1
    assert (tmp_path / "workspace" / "a.txt").read_text() == "x"
    assert rt2.core.db.scalar("SELECT COUNT(*) FROM events WHERE type = 'run.recovered'") == 1
    rt.core.db.close()
    rt2.core.db.close()


def test_D_stale_worker_is_fenced(rt):
    prof = mock_profile(rt, script=[])
    bot = rt.services["bots"].create({"name": "F", "provider_profile_id": prof["id"]})
    rt.services["tasks"].create_task(bot_id=bot["id"], conversation_id=None, requester_type="user",
                                     requester_id="u", instructions="x")
    old = rt.engine.claim()
    crash(rt, old["id"])
    assert rt.engine.recover_expired() == 1
    newer = Engine(rt.core, rt.services, worker_id="w2")
    fresh = newer.claim()
    assert fresh["lease_version"] == old["lease_version"] + 1
    with pytest.raises(LeaseLost):
        with rt.core.db.tx():
            rt.engine._fence(old)
    with pytest.raises(LeaseLost):
        with rt.core.db.tx():
            rt.engine._set_run(old, status="completed")
    assert rt.core.db.scalar("SELECT status FROM runs WHERE id = ?", (old["id"],)) == "running"


class Receiver:
    """Test external system that records deliveries by Idempotency-Key."""

    def __init__(self):
        self.deliveries = []

    def handler(self, request: httpx.Request):
        self.deliveries.append((request.headers.get("idempotency-key"), json.loads(request.content)))
        return httpx.Response(200, json={"ok": True})


def register_send(rt, receiver, *, hang_after_send=None):
    async def send(ctx, a):
        async with httpx.AsyncClient(transport=httpx.MockTransport(receiver.handler)) as c:
            await c.post("https://receiver.test/send", json=a, headers={"Idempotency-Key": ctx.idempotency_key})
        if hang_after_send is not None:
            hang_after_send.set()
            await asyncio.sleep(3600)  # process dies before recording the result
        return {"sent": True}
    rt.services["tools"].register(ToolSpec(
        "test.send", "Send a message to the test system.",
        {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
        send, "external", "allow"))


def test_D_crash_during_external_send_is_not_blindly_retried(tmp_path):
    rt = make_runtime(tmp_path)
    receiver = Receiver()
    gate = asyncio.Event()
    register_send(rt, receiver, hang_after_send=gate)
    script = [{"when": "notify", "call": {"name": "test.send", "arguments": {"text": "hello"}}},
              {"after_tool": "test.send", "reply": "sent"}]
    prof = mock_profile(rt, script=script)
    bot = rt.services["bots"].create({"name": "Sender", "provider_profile_id": prof["id"], "tools": ["test.*"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    run_id = rt.services["tasks"].post_user_message(conv["id"], "notify team")["tasks"][0]["run_id"]

    async def die():
        run = rt.engine.claim()
        t = rt.engine._spawn(run)
        await gate.wait()
        rt.engine._reasons[run_id] = "lease_lost"
        t.cancel()
        await asyncio.gather(t, return_exceptions=True)
    asyncio.run(die())
    assert len(receiver.deliveries) == 1
    crash(rt, run_id)

    rt2 = make_runtime(tmp_path)
    register_send(rt2, receiver)
    mock_profile(rt2, script=script)
    rt2.services["providers"].overrides[prof["id"]] = ScriptedAdapter(script=script)
    drain(rt2)
    run = rt2.core.db.one("SELECT * FROM runs WHERE id = ?", (run_id,))
    assert run["status"] == "unknown_outcome"
    assert len(receiver.deliveries) == 1  # no blind resend
    assert rt2.core.db.scalar("SELECT status FROM operations") == "unknown"
    assert rt2.core.db.scalar("SELECT COUNT(*) FROM notifications WHERE kind = 'needs_resolution'") == 1
    # Human checks the receiver and confirms it arrived.
    rt2.engine.resolve_unknown(run_id, "done", "seen in receiver log")
    drain(rt2)
    assert rt2.core.db.scalar("SELECT status FROM runs WHERE id = ?", (run_id,)) == "completed"
    assert len(receiver.deliveries) == 1
    rt.core.db.close()
    rt2.core.db.close()


def test_D_retry_with_backoff_then_fail(rt):
    from app.v2.providers.base import ProviderError

    def flaky(req):
        raise ProviderError("server", "HTTP 503", retryable=True)
    prof = mock_profile(rt, fn=flaky)
    bot = rt.services["bots"].create({"name": "R", "provider_profile_id": prof["id"]})
    t = rt.services["tasks"].create_task(bot_id=bot["id"], conversation_id=None, requester_type="user",
                                         requester_id="u", instructions="x")
    drain(rt)
    run = rt.core.db.one("SELECT * FROM runs WHERE id = ?", (t["run_id"],))
    assert run["status"] == "failed" and "Gave up after 4 attempts" in run["error"]
    assert rt.core.db.scalar("SELECT COUNT(*) FROM events WHERE type = 'run.retry_scheduled'") == 3


def test_D_stop_reports_done_and_inflight_actions(rt):
    gate = asyncio.Event()

    async def slow(ctx, a):
        gate.set()
        await asyncio.sleep(3600)
    rt.services["tools"].register(ToolSpec("test.slow", "slow", {"type": "object"}, slow, "external", "allow"))
    script = [{"when": "go", "call": {"name": "memory.save", "arguments": {"content": "m"}}},
              {"after_tool": "memory.save", "call": {"name": "test.slow", "arguments": {}}}]
    prof = mock_profile(rt, script=script)
    bot = rt.services["bots"].create({"name": "S", "provider_profile_id": prof["id"], "tools": ["memory.*", "test.*"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    task_id = rt.services["tasks"].post_user_message(conv["id"], "go")["tasks"][0]["id"]

    async def scenario():
        run = rt.engine.claim()
        t = rt.engine._spawn(run)
        await gate.wait()
        rt.services["tasks"].cancel(task_id)   # user presses Stop
        await asyncio.wait_for(asyncio.gather(t, return_exceptions=True), 5)
    asyncio.run(scenario())
    task = rt.services["tasks"].get_task(task_id)
    assert task["status"] == "cancelled"
    assert "memory.save" in task["error"] and "test.slow" in task["error"]
    assert rt.core.db.scalar("SELECT status FROM operations") == "unknown"


# --------------------------------------------------------------------- E ---
def test_E_approval_survives_restart_and_is_bound(tmp_path):
    rt = make_runtime(tmp_path)
    receiver = Receiver()
    register_send(rt, receiver)
    script = [{"when": "send", "call": {"name": "test.send", "arguments": {"text": "draft 1"}}},
              {"after_tool": "test.send", "reply": "{{last}}"}]
    prof = mock_profile(rt, script=script)
    bot = rt.services["bots"].create({"name": "Mailer", "provider_profile_id": prof["id"], "tools": ["test.*"],
                                      "policy": [{"tool": "test.send", "effect": "ask"}]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    task_id = rt.services["tasks"].post_user_message(conv["id"], "send it")["tasks"][0]["id"]
    drain(rt)
    assert rt.core.db.scalar("SELECT status FROM runs WHERE task_id = ?", (task_id,)) == "waiting_approval"
    rt.core.db.close()

    rt2 = make_runtime(tmp_path)  # restart
    register_send(rt2, receiver)
    rt2.services["providers"].overrides[prof["id"]] = ScriptedAdapter(script=script)
    ap = rt2.services["approvals"].list()[0]
    assert ap["status"] == "pending" and ap["display"] == {"text": "draft 1"}
    from app.v2.approvals import ApprovalError
    with pytest.raises(ApprovalError, match="changed"):
        rt2.services["approvals"].decide(ap["id"], user_id="local-user", decision="approve",
                                         args_hash_seen=args_hash("test.send", {"text": "other"}))
    with pytest.raises(ApprovalError, match="another user"):
        rt2.services["approvals"].decide(ap["id"], user_id="mallory", decision="approve", args_hash_seen=ap["args_hash"])
    spec = rt2.services["tools"].get(rt2.services["bots"].get(bot["id"]), "test.send")
    edited = rt2.services["approvals"].edit(ap["id"], user_id="local-user", arguments={"text": "draft 2"},
                                            validate=spec.validate)
    with pytest.raises(ApprovalError, match="invalidated"):
        rt2.services["approvals"].decide(ap["id"], user_id="local-user", decision="approve", args_hash_seen=ap["args_hash"])
    rt2.services["approvals"].decide(edited["id"], user_id="local-user", decision="approve",
                                     args_hash_seen=edited["args_hash"])
    # An approval cannot be used for a different operation.
    assert not rt2.services["approvals"].consume(edited["id"], "http.post", {"text": "draft 2"})
    drain(rt2)
    assert [d[1] for d in receiver.deliveries] == [{"text": "draft 2"}]
    assert rt2.services["approvals"].get(edited["id"])["status"] == "consumed"
    assert not rt2.services["approvals"].consume(edited["id"], "test.send", {"text": "draft 2"})  # single use
    assert rt2.services["tasks"].get_task(task_id)["status"] == "completed"
    rt2.core.db.close()


def test_E_expired_approval_does_not_execute(rt):
    receiver = Receiver()
    register_send(rt, receiver)
    prof = mock_profile(rt, script=[{"when": "send", "call": {"name": "test.send", "arguments": {"text": "x"}}},
                                    {"after_tool": "test.send", "reply": "{{last}}"}])
    bot = rt.services["bots"].create({"name": "M", "provider_profile_id": prof["id"], "tools": ["test.*"],
                                      "policy": [{"tool": "test.*", "effect": "ask"}]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    rt.services["tasks"].post_user_message(conv["id"], "send")
    drain(rt)
    with rt.core.db.tx():
        rt.core.db.execute("UPDATE approvals SET expires_at = ?", ("2000-01-01T00:00:00.000000Z",))
    drain(rt)
    assert receiver.deliveries == []
    msgs = rt.services["tasks"].messages(conv["id"])
    assert "Approval expired" in msgs[-1]["text"]


def test_policy_most_restrictive_wins_and_hard_ask(rt):
    from app.v2 import policy
    assert policy.evaluate("x.y", default="allow", hard_ask=False, global_rules=[{"tool": "x.*", "effect": "allow"}],
                           bot_rules=[{"tool": "x.y", "effect": "deny"}]) == "deny"
    assert policy.evaluate("x.y", default="ask", hard_ask=False, global_rules=[],
                           bot_rules=[{"tool": "x.*", "effect": "allow"}]) == "allow"
    assert policy.evaluate("pay.now", default="ask", hard_ask=True, global_rules=[],
                           bot_rules=[{"tool": "*", "effect": "allow"}]) == "ask"
    assert policy.evaluate("w.read", default="allow", hard_ask=False, global_rules=[], bot_rules=[]) == "allow"


# --------------------------------------------------------------------- K ---
def chat_server(behaviour):
    """Fake Chat Completions endpoint (httpx MockTransport)."""
    seen = []

    def handler(request: httpx.Request):
        body = json.loads(request.content) if request.content else {}
        seen.append((request.url.path, request.headers.get("authorization"), body))
        return behaviour(request, body)
    return httpx.MockTransport(handler), seen


def test_K_two_bots_two_providers_and_honest_failures(rt):
    providers = rt.services["providers"]

    def no_tools(request, body):
        if request.url.path.endswith("/models"):
            return httpx.Response(200, json={"data": [{"id": "small-1"}]})
        if body.get("tools"):
            return httpx.Response(400, json={"error": {"message": "tools are not supported by this model"}})
        return httpx.Response(200, json={"choices": [{"message": {"content": "plain answer"}, "finish_reason": "stop"}],
                                         "usage": {"prompt_tokens": 7, "completion_tokens": 2}})
    t1, seen1 = chat_server(no_tools)
    p1 = providers.upsert({"kind": "local", "name": "Local", "base_url": "http://127.0.0.1:9/v1", "default_model": "small-1"})
    providers.transports[p1["id"]] = t1

    def unauthorized(request, body):
        return httpx.Response(401, json={"error": {"message": "bad key"}})
    t2, _ = chat_server(unauthorized)
    p2 = providers.upsert({"kind": "openrouter", "name": "OR", "api_key": "sk-or-secret-value-123456",
                           "default_model": "vendor/model"})
    providers.transports[p2["id"]] = t2

    test = asyncio.run(providers.test(p1["id"]))
    assert test["checks"]["text"]["ok"] and test["checks"]["tools"]["ok"] is False
    assert test["capabilities"]["tools"] is False
    test2 = asyncio.run(providers.test(p2["id"]))
    assert test2["checks"]["text"]["ok"] is False and test2["checks"]["text"]["kind"] == "auth"

    b1 = rt.services["bots"].create({"name": "Local bot", "provider_profile_id": p1["id"]})
    b2 = rt.services["bots"].create({"name": "Cloud bot", "provider_profile_id": p2["id"], "tools": ["web.fetch"]})
    c1 = rt.services["tasks"].private_conversation(b1["id"])
    c2 = rt.services["tasks"].private_conversation(b2["id"])
    t_ok = rt.services["tasks"].post_user_message(c1["id"], "hi")["tasks"][0]["id"]
    t_bad = rt.services["tasks"].post_user_message(c2["id"], "hi")["tasks"][0]["id"]
    drain(rt)
    assert rt.services["tasks"].get_task(t_ok)["status"] == "completed"
    assert "tools" not in seen1[-1][2]  # tools disabled after the capability test
    bad = rt.services["tasks"].get_task(t_bad)
    assert bad["status"] == "failed" and bad["error"].startswith("auth")
    # Key never appears in stored events, messages or audit.
    dump = json.dumps(rt.core.db.all("SELECT * FROM events")) + json.dumps(rt.core.db.all("SELECT * FROM audit_log")) \
        + json.dumps(rt.core.db.all("SELECT * FROM messages"))
    assert "sk-or-secret-value-123456" not in dump


def test_K_budget_limit_blocks_before_call(rt):
    adapter = ScriptedAdapter(script=[{"reply": "x"}])
    prof = mock_profile(rt, script=[])
    rt.services["providers"].overrides[prof["id"]] = adapter
    rt.services["providers"].upsert({"prices": {"scripted-mock": {"input_per_mtok": 1_000_000, "output_per_mtok": 0}}},
                                    prof["id"])
    bot = rt.services["bots"].create({"name": "Cheap", "provider_profile_id": prof["id"],
                                      "budget": {"max_cost_per_day": 0.5}})
    t = rt.services["tasks"].create_task(bot_id=bot["id"], conversation_id=None, requester_type="user",
                                         requester_id="u", instructions="x")
    drain(rt)
    task = rt.services["tasks"].get_task(t["id"])
    assert task["status"] == "failed" and task["error"].startswith("budget")
    assert adapter.calls == []


def test_K_vision_missing_is_reported(rt):
    adapter = ScriptedAdapter(script=[{"reply": "ok"}])
    prof = mock_profile(rt, script=[])
    rt.services["providers"].overrides[prof["id"]] = adapter
    rt.services["providers"].upsert({"capabilities": {"vision": False}}, prof["id"])
    bot = rt.services["bots"].create({"name": "Eyes", "provider_profile_id": prof["id"]})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    rt.services["tasks"].post_user_message(conv["id"], "what is this?",
                                           attachments=[{"kind": "image", "url": "data:image/png;base64,AAAA"}])
    drain(rt)
    req = adapter.calls[0]
    assert not any(m.get("images") for m in req.messages)
    assert "images were NOT sent" in req.system


def test_K_missing_provider_is_a_failed_state_not_success(rt):
    bot = rt.services["bots"].create({"name": "NoModel"})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    tid = rt.services["tasks"].post_user_message(conv["id"], "hi")["tasks"][0]["id"]
    drain(rt)
    t = rt.services["tasks"].get_task(tid)
    assert t["status"] == "failed" and "No model provider" in t["error"]


# --------------------------------------------------------------------- L ---
def test_L_duplicate_export_hide_pause_delete_and_backup(tmp_path):
    rt = make_runtime(tmp_path)
    prof = mock_profile(rt, script=[{"reply": "ok"}])
    rt.services["providers"].upsert({"api_key": "sk-supersecret-abcdefgh"}, prof["id"])
    bot = rt.services["bots"].create({"name": "Orig", "provider_profile_id": prof["id"], "instructions": "be nice"})
    conv = rt.services["tasks"].private_conversation(bot["id"])
    rt.services["tasks"].post_user_message(conv["id"], "hello")
    drain(rt)
    rt.services["memory"].save(content="private fact", scope="bot", bot_id=bot["id"], source="t", created_by="u")
    rt.services["routines"].create(bot_id=bot["id"], name="daily", prompt="report", schedule="codziennie o 8:00")

    copy = rt.services["bots"].duplicate(bot["id"])
    assert copy["instructions"] == "be nice"
    assert rt.services["memory"].list(bot=copy) == []
    assert rt.services["tasks"].list_conversations() and not rt.core.db.all(
        "SELECT * FROM conversations WHERE default_bot_id = ?", (copy["id"],))
    copied_routines = rt.services["routines"].list(copy["id"])
    assert len(copied_routines) == 1 and copied_routines[0]["enabled"] is False
    exported = json.dumps(rt.services["bots"].export(bot["id"]))
    assert "sk-supersecret" not in exported and "private fact" not in exported and "hello" not in exported

    # hide != pause
    rt.services["bots"].update(bot["id"], {"hidden": True})
    t = rt.services["tasks"].post_user_message(conv["id"], "still works while hidden")["tasks"][0]
    drain(rt)
    assert rt.services["tasks"].get_task(t["id"])["status"] == "completed"
    rt.services["bots"].set_paused(bot["id"], True)
    t2 = rt.services["tasks"].post_user_message(conv["id"], "queued while paused")["tasks"][0]
    drain(rt)
    assert rt.core.db.scalar("SELECT status FROM runs WHERE id = ?", (t2["run_id"],)) == "paused"
    rt.services["bots"].set_paused(bot["id"], False)
    drain(rt)
    assert rt.services["tasks"].get_task(t2["id"])["status"] == "completed"

    # backup -> restore
    from app.v2.backup import create_backup, restore_backup
    archive = create_backup(tmp_path, rt.settings.db_path, tmp_path / "backups")
    rt.services["bots"].delete(bot["id"])
    assert rt.services["bots"].get(bot["id"]) is None
    assert rt.services["routines"].list(bot["id"]) == []
    assert rt.core.db.scalar("SELECT COUNT(*) FROM audit_log WHERE action = 'bot.delete'") == 1
    rt.core.db.close()
    info = restore_backup(archive, tmp_path)
    rt2 = make_runtime(tmp_path)
    assert rt2.services["bots"].get(bot["id"])["name"] == "Orig"
    assert len(rt2.services["tasks"].messages(conv["id"])) >= 4
    assert (tmp_path / info["previous_state_moved_to"].split("/")[-1]).exists()
    rt2.core.db.close()
