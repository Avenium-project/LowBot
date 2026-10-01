"""MCP client: stdio and Streamable HTTP connections, managed per connection.

* Protocol versions/capabilities come from the installed ``mcp`` SDK
  (``mcp.types.LATEST_PROTOCOL_VERSION``); nothing is hard-coded to a server.
* Tools are discovered with ``tools/list``, exposed as
  ``mcp.<connection>.<tool>`` and validated against the server's
  ``inputSchema`` before every call (gateway + policy still apply).
* Default policy: ``ask``; tools annotated ``readOnlyHint`` default to
  ``allow`` (an owner-configured server's hint; it never bypasses deny rules).
* Calls have timeouts, cancellation and progress events.
* Elicitation:
  - form mode: fields that look like secrets (password/token/key/...) are
    refused — forms never collect credentials;
  - url mode: the target domain is shown and explicit consent is required;
    consenting means "open it", not "authorization finished";
  - every elicitation is a persisted request with accept / decline / cancel;
    nothing is auto-approved, and an unanswered one expires as ``cancel``.
* Secrets: HTTP auth headers and stdio env values are secret references
  resolved at connect time; they are never sent to the model or logged.
"""

from __future__ import annotations

import asyncio
import collections
import json
import logging
import os
import re
from contextlib import AsyncExitStack
from datetime import timedelta
from typing import Any, Deque, Dict, List, Optional
from urllib.parse import urlsplit

from app.v2.db import loads
from app.v2.tools.builtin import ToolError
from app.v2.tools.registry import ToolContext, ToolSpec
from app.v2.util import iso_in, new_id, now_iso, redact, truncate

log = logging.getLogger("opendots.mcp")
SECRET_FIELD = re.compile(r"pass(word|phrase)?|secret|token|api[_-]?key|credential|pin\b|otp|cvv|card", re.I)
ELICITATION_TTL_S = 600


def _safe(name: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]", "_", name)[:40]


class _Conn:
    """Owns one live MCP session inside its own task (anyio contexts must be
    entered and exited by the same task)."""

    def __init__(self, manager: "McpManager", row: Dict[str, Any]):
        self.m, self.row = manager, row
        self.session = None
        self.ready = asyncio.Event()
        self.closing = asyncio.Event()
        self.error: Optional[str] = None
        self.task: Optional[asyncio.Task] = None
        self.logs: Deque[str] = collections.deque(maxlen=200)
        self.current_ctx: Optional[ToolContext] = None

    async def _main(self) -> None:
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client
        from mcp.client.streamable_http import streamablehttp_client
        row = self.row
        try:
            async with AsyncExitStack() as stack:
                if row["transport"] == "stdio":
                    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "/tmp")}
                    for key, sid in loads(row["env_secrets_json"], {}).items():
                        env[key] = self.m.core.secrets.get(sid) or ""
                    params = StdioServerParameters(command=row["command"], args=loads(row["args_json"], []), env=env)
                    errlog = open(self.m.log_dir / f"{row['id']}.stderr.log", "a")
                    stack.callback(errlog.close)
                    read, write = await stack.enter_async_context(stdio_client(params, errlog=errlog))
                else:
                    headers = {}
                    if row["auth_secret_id"]:
                        headers["Authorization"] = f"Bearer {self.m.core.secrets.get(row['auth_secret_id'])}"
                    read, write, _ = await stack.enter_async_context(
                        streamablehttp_client(row["url"], headers=headers, timeout=float(row["timeout_s"])))
                session = await stack.enter_async_context(ClientSession(
                    read, write, read_timeout_seconds=timedelta(seconds=float(row["timeout_s"]) + 600),
                    elicitation_callback=self._elicit, logging_callback=self._log))
                init = await asyncio.wait_for(session.initialize(), float(row["timeout_s"]))
                self.logs.append(f"initialized protocol={init.protocolVersion} server={init.serverInfo.name}")
                self.session = session
                self.ready.set()
                await self.closing.wait()
        except BaseException as exc:  # noqa: BLE001 - record and surface any transport failure
            self.error = f"{type(exc).__name__}: {truncate(str(redact(str(exc))), 300)}"
            self.logs.append("error " + self.error)
            self.ready.set()
            if isinstance(exc, asyncio.CancelledError):
                raise
        finally:
            self.session = None

    async def start(self) -> None:
        self.task = asyncio.ensure_future(self._main())
        await asyncio.wait_for(self.ready.wait(), float(self.row["timeout_s"]) + 5)
        if self.session is None:
            raise ToolError(f"MCP connection '{self.row['name']}' failed: {self.error}")

    async def close(self) -> None:
        self.closing.set()
        if self.task:
            try:
                await asyncio.wait_for(self.task, 5)
            except (asyncio.TimeoutError, Exception):
                self.task.cancel()

    async def _log(self, params) -> None:
        self.logs.append(f"log {params.level}: {truncate(str(redact(str(params.data))), 300)}")

    async def _elicit(self, context, params):
        from mcp import types
        return await self.m.elicit(self, params, types)


class McpManager:
    def __init__(self, runtime):
        self.rt, self.core, self.db = runtime, runtime.core, runtime.core.db
        self.conns: Dict[str, _Conn] = {}
        self.log_dir = runtime.settings.data_dir / "logs"
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self._locks: Dict[str, asyncio.Lock] = {}

    # ------------------------------------------------------- connections ---
    def rows(self) -> List[Dict[str, Any]]:
        out = []
        for r in self.db.all("SELECT * FROM tool_connections ORDER BY name"):
            out.append(self.public(r))
        return out

    def public(self, r: Dict[str, Any]) -> Dict[str, Any]:
        r = dict(r)
        r["args"] = loads(r.pop("args_json"), [])
        r["env_secret_names"] = sorted(loads(r.pop("env_secrets_json"), {}).keys())
        r["auth_configured"] = bool(r.pop("auth_secret_id"))
        r["bot_ids"] = loads(r.pop("bot_ids_json"), [])
        r["tools"] = loads(r.pop("tools_cache_json"), [])
        r["enabled"] = bool(r["enabled"])
        c = self.conns.get(r["id"])
        r["connected"] = bool(c and c.session)
        r["logs"] = list(c.logs)[-20:] if c else []
        return r

    def save(self, data: Dict[str, Any], conn_id: Optional[str] = None) -> Dict[str, Any]:
        transport = data.get("transport") or (self.db.scalar("SELECT transport FROM tool_connections WHERE id = ?", (conn_id,)) if conn_id else None)
        if transport not in ("stdio", "http"):
            raise ValueError("transport must be stdio or http")
        values: Dict[str, Any] = {"updated_at": now_iso()}
        if "name" in data:
            if not re.fullmatch(r"[a-zA-Z0-9_-]{1,40}", data["name"] or ""):
                raise ValueError("Name: 1-40 chars of letters, digits, _ or -.")
            values["name"] = data["name"]
        if transport == "stdio" and "command" in data:
            values["command"] = data["command"]
            values["args_json"] = list(data.get("args") or [])
        if transport == "http" and "url" in data:
            parts = urlsplit(data["url"] or "")
            if parts.scheme not in ("http", "https") or not parts.hostname or parts.username:
                raise ValueError("Use an http(s) URL without credentials.")
            values["url"] = data["url"]
        for key in ("scope", "timeout_s", "enabled"):
            if key in data:
                values[key] = data[key]
        if "bot_ids" in data:
            values["bot_ids_json"] = list(data["bot_ids"] or [])
        with self.db.tx():
            if conn_id is None:
                conn_id = new_id("mcp")
                self.db.insert("tool_connections", {"id": conn_id, "transport": transport, "created_at": now_iso(),
                                                    "name": values.pop("name", conn_id), **values})
            else:
                self.db.update("tool_connections", {"id": conn_id}, values)
            if data.get("auth_token"):
                old = self.db.scalar("SELECT auth_secret_id FROM tool_connections WHERE id = ?", (conn_id,))
                sid = self.core.secrets.put(f"mcp:{conn_id}:auth", data["auth_token"], "mcp_auth", old)
                self.db.update("tool_connections", {"id": conn_id}, {"auth_secret_id": sid})
            if data.get("env_secrets"):
                env = loads(self.db.scalar("SELECT env_secrets_json FROM tool_connections WHERE id = ?", (conn_id,)), {})
                for k, v in data["env_secrets"].items():
                    if not re.fullmatch(r"[A-Z_][A-Z0-9_]{0,60}", k):
                        raise ValueError("Env names must be UPPER_SNAKE_CASE.")
                    env[k] = self.core.secrets.put(f"mcp:{conn_id}:env:{k}", str(v), "mcp_env", env.get(k))
                self.db.update("tool_connections", {"id": conn_id}, {"env_secrets_json": env})
            self.core.audit("mcp.save", actor_type="user", connection_id=conn_id)
        return self.public(self.db.one("SELECT * FROM tool_connections WHERE id = ?", (conn_id,)))

    async def delete(self, conn_id: str) -> None:
        c = self.conns.pop(conn_id, None)
        if c:
            await c.close()
        row = self.db.one("SELECT * FROM tool_connections WHERE id = ?", (conn_id,))
        if row:
            for sid in [row["auth_secret_id"], *loads(row["env_secrets_json"], {}).values()]:
                self.core.secrets.delete(sid)
        with self.db.tx():
            self.db.execute("DELETE FROM tool_connections WHERE id = ?", (conn_id,))

    async def conn(self, conn_id: str) -> _Conn:
        lock = self._locks.setdefault(conn_id, asyncio.Lock())
        async with lock:
            c = self.conns.get(conn_id)
            if c and c.session and c.task and not c.task.done():
                return c
            row = self.db.one("SELECT * FROM tool_connections WHERE id = ? AND enabled = 1", (conn_id,))
            if not row:
                raise ToolError("MCP connection is missing or disabled.")
            c = _Conn(self, row)
            self.conns[conn_id] = c
            await c.start()
            return c

    async def refresh(self, conn_id: str) -> Dict[str, Any]:
        """Connect, list tools, cache them. Used by 'Test connection'."""
        old = self.conns.pop(conn_id, None)
        if old:
            await old.close()
        try:
            c = await self.conn(conn_id)
            result = await asyncio.wait_for(c.session.list_tools(), float(c.row["timeout_s"]))
            tools = [{"name": t.name, "description": t.description or "", "input_schema": t.inputSchema or {"type": "object"},
                      "read_only": bool(getattr(t.annotations, "readOnlyHint", False)) if t.annotations else False}
                     for t in result.tools]
            status = f"ok: {len(tools)} tools"
        except (ToolError, asyncio.TimeoutError, Exception) as exc:
            tools, status = None, f"error: {truncate(str(redact(str(exc))), 300)}"
        with self.db.tx():
            vals = {"last_status": status, "updated_at": now_iso()}
            if tools is not None:
                vals["tools_cache_json"] = tools
            self.db.update("tool_connections", {"id": conn_id}, vals)
        return self.public(self.db.one("SELECT * FROM tool_connections WHERE id = ?", (conn_id,)))

    async def close(self) -> None:
        for c in list(self.conns.values()):
            await c.close()
        self.conns.clear()
        with self.db.tx():
            self.db.execute("UPDATE elicitations SET status = 'orphaned', decided_at = ? WHERE status = 'pending'",
                            (now_iso(),))

    # ------------------------------------------------------------- calls ---
    async def call(self, ctx: ToolContext, conn_id: str, tool: str, args: Dict[str, Any]) -> Dict[str, Any]:
        c = await self.conn(conn_id)
        timeout = float(c.row["timeout_s"])

        async def progress(p: float, total: Optional[float], message: Optional[str]) -> None:
            with self.db.tx():
                self.core.emit("run.tool_progress", task_id=ctx.task["id"], run_id=ctx.run["id"], bot_id=ctx.bot["id"],
                               tool=f"mcp.{c.row['name']}.{tool}", progress=p, total=total,
                               message=truncate(message or "", 200))
        c.current_ctx = ctx
        try:
            # Elicitation waits are excluded from the timeout budget by design of
            # the read timeout above; the overall call is still bounded.
            result = await asyncio.wait_for(
                c.session.call_tool(tool, args, read_timeout_seconds=timedelta(seconds=timeout + ELICITATION_TTL_S),
                                    progress_callback=progress), timeout + ELICITATION_TTL_S + 5)
        except asyncio.TimeoutError:
            raise ToolError(f"MCP tool timed out after {timeout}s.")
        finally:
            c.current_ctx = None
        parts = []
        for item in result.content or []:
            if getattr(item, "type", "") == "text":
                parts.append(item.text)
            else:
                parts.append(f"[{getattr(item, 'type', 'content')} omitted]")
        out = {"is_error": bool(result.isError), "content": truncate("\n".join(parts), 20000)}
        if getattr(result, "structuredContent", None):
            out["structured"] = result.structuredContent
        if result.isError:
            raise ToolError(f"MCP tool reported an error: {truncate(out['content'], 1000)}")
        return out

    # ------------------------------------------------------- elicitation ---
    async def elicit(self, c: _Conn, params, types):
        ctx = c.current_ctx
        eid = new_id("eli")
        mode = getattr(params, "mode", "form") or "form"
        schema = getattr(params, "requestedSchema", None)
        schema_dict = schema if isinstance(schema, dict) else (schema.model_dump() if schema is not None else {})
        url = getattr(params, "url", None)
        domain = (urlsplit(url).hostname or "") if url else None
        refusal = None
        if mode == "form":
            for field, spec in (schema_dict.get("properties") or {}).items():
                text = f"{field} {spec.get('title', '')} {spec.get('description', '')}" if isinstance(spec, dict) else field
                if SECRET_FIELD.search(text) or (isinstance(spec, dict) and spec.get("format") == "password"):
                    refusal = f"Form field '{field}' looks like a secret; forms must not collect credentials."
        elif mode == "url" and (not url or urlsplit(url).scheme != "https"):
            refusal = "URL elicitation requires an https URL."
        status = "refused" if refusal else "pending"
        with self.db.tx():
            self.db.insert("elicitations", {
                "id": eid, "connection_id": c.row["id"], "task_id": ctx.task["id"] if ctx else None,
                "run_id": ctx.run["id"] if ctx else None, "bot_id": ctx.bot["id"] if ctx else None, "mode": mode,
                "message": truncate(getattr(params, "message", ""), 2000), "schema_json": schema_dict, "url": url,
                "url_domain": domain, "status": status, "refusal_reason": refusal, "created_at": now_iso(),
                "expires_at": iso_in(ELICITATION_TTL_S)})
            if not refusal:
                conv = ctx.task.get("conversation_id") if ctx else None
                self.core.emit("elicitation.requested", conversation_id=conv, task_id=ctx.task["id"] if ctx else None,
                               bot_id=ctx.bot["id"] if ctx else None, elicitation_id=eid, mode=mode, domain=domain)
                self.core.notify("needs_input", f"{c.row['name']} asks: {truncate(getattr(params, 'message', ''), 120)}",
                                 f"Domain: {domain}" if domain else "", task_id=ctx.task["id"] if ctx else None,
                                 conversation_id=conv, bot_id=ctx.bot["id"] if ctx else None)
            self.core.audit("mcp.elicitation", actor_type="mcp", actor_id=c.row["id"], mode=mode, domain=domain,
                            outcome=status, reason=refusal)
        if refusal:
            return types.ElicitResult(action="decline")
        deadline = asyncio.get_running_loop().time() + ELICITATION_TTL_S
        while asyncio.get_running_loop().time() < deadline:
            row = self.db.one("SELECT status, response_json FROM elicitations WHERE id = ?", (eid,))
            if row["status"] != "pending":
                break
            await self.core.bus.wait(2.0)
        row = self.db.one("SELECT status, response_json FROM elicitations WHERE id = ?", (eid,))
        if row["status"] == "pending":
            with self.db.tx():
                self.db.update("elicitations", {"id": eid}, {"status": "expired", "decided_at": now_iso()})
            return types.ElicitResult(action="cancel")
        action = {"accepted": "accept", "declined": "decline"}.get(row["status"], "cancel")
        content = loads(row["response_json"], {}) if action == "accept" and mode == "form" else None
        return types.ElicitResult(action=action, content=content)

    def respond(self, eid: str, action: str, content: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        if action not in ("accept", "decline", "cancel"):
            raise ValueError("action must be accept, decline or cancel")
        row = self.db.one("SELECT * FROM elicitations WHERE id = ?", (eid,))
        if not row or row["status"] != "pending":
            raise ValueError("Elicitation is not pending.")
        schema = loads(row["schema_json"], {})
        if action == "accept" and row["mode"] == "form":
            import jsonschema
            content = content or {}
            for k in content:
                if SECRET_FIELD.search(k):
                    raise ValueError("Secrets cannot be submitted through elicitation forms.")
            try:
                jsonschema.validate(content, {**schema, "type": "object"})
            except jsonschema.ValidationError as exc:
                raise ValueError(f"Invalid answer: {exc.message}")
        status = {"accept": "accepted", "decline": "declined", "cancel": "cancelled"}[action]
        with self.db.tx():
            n = self.db.update("elicitations", {"id": eid, "status": "pending"},
                               {"status": status, "decided_at": now_iso(),
                                "response_json": content if action == "accept" and row["mode"] == "form" else {}})
            if n != 1:
                raise ValueError("Elicitation was answered concurrently.")
            self.core.emit("elicitation.answered", task_id=row["task_id"], bot_id=row["bot_id"], elicitation_id=eid,
                           action=action)
            self.core.audit("mcp.elicitation_answer", actor_type="user", decision=action, elicitation_id=eid)
        self.core.bus.notify()
        return {"id": eid, "status": status}

    def pending_elicitations(self) -> List[Dict[str, Any]]:
        rows = self.db.all("SELECT * FROM elicitations WHERE status = 'pending' ORDER BY created_at")
        for r in rows:
            r["schema"] = loads(r.pop("schema_json"), {})
            r.pop("response_json", None)
        return rows

    # -------------------------------------------------------- tool specs ---
    def specs_for(self, bot: Dict[str, Any]) -> List[ToolSpec]:
        out = []
        for row in self.db.all("SELECT * FROM tool_connections WHERE enabled = 1"):
            if row["scope"] == "bot" and bot.get("id") not in loads(row["bot_ids_json"], []) and bot.get("id"):
                continue
            for t in loads(row["tools_cache_json"], []):
                out.append(self._spec(row, t))
        return out

    def _spec(self, row: Dict[str, Any], t: Dict[str, Any]) -> ToolSpec:
        conn_id, tool_name = row["id"], t["name"]
        schema = t.get("input_schema") or {"type": "object"}
        if schema.get("type") != "object":
            schema = {"type": "object", "properties": {}}

        async def run(ctx: ToolContext, args: Dict[str, Any]) -> Dict[str, Any]:
            return await self.call(ctx, conn_id, tool_name, args)

        return ToolSpec(
            name=f"mcp.{_safe(row['name'])}.{_safe(tool_name)}",
            description=f"[MCP {row['name']}] {truncate(t.get('description') or tool_name, 900)}",
            input_schema=schema, executor=run,
            effect_kind="read" if t.get("read_only") else "external",
            default_effect="allow" if t.get("read_only") else "ask",
            timeout_s=float(row["timeout_s"]) + ELICITATION_TTL_S + 10,
            summarize=lambda a, n=row["name"], tn=tool_name: {
                "summary": f"MCP {n}: {tn}", "effect": "calls an external MCP tool",
                "target": truncate(json.dumps(redact(a)), 200)})


def register_mcp(runtime) -> None:
    import mcp  # noqa: F401 - ensure the SDK is importable
    manager = McpManager(runtime)
    runtime.services["mcp"] = manager
    runtime.services["capabilities"].add("mcp")
    runtime.services["tools"].add_provider(manager.specs_for)
