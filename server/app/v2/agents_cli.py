"""Integrations with agent CLIs through their documented program interfaces.

* **ChatGPT / Codex** — ``codex exec --json`` (non-interactive, JSONL events)
  and ``codex login --device-auth`` / ``codex login status``. Signing in with
  a ChatGPT account is done by the user through OpenAI's own device-code page;
  LowBot never sees the password and never copies cookies. Credentials stay in
  ``CODEX_HOME`` (``DATA_DIR/cli/codex``, mode 0700) on the server.
* **OpenCode / OpenCode Go** — ``opencode run --format json``. The OpenCode Go
  key is stored as an encrypted LowBot secret and passed only to the child
  process as ``OPENCODE_API_KEY``. Tool permissions are pinned through
  ``OPENCODE_CONFIG_CONTENT`` (bash/webfetch denied, edits opt-in).

A ChatGPT subscription is not API credit: Codex usage is governed by the
user's plan limits, which LowBot neither bypasses nor measures in money.
Both CLIs send prompts and workspace content to their cloud services.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import signal
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from app.v2.util import now_iso, redact, truncate

URL_RE = re.compile(r"https://[^\s\"'<>]+")
CODE_RE = re.compile(r"\b([A-Z0-9]{4,5}-[A-Z0-9]{4,5})\b")


class CliError(Exception):
    pass


@dataclass
class CliResult:
    text: str
    input_tokens: int = 0
    output_tokens: int = 0
    actions: List[Dict[str, Any]] = field(default_factory=list)
    session_id: Optional[str] = None
    exit_code: int = 0
    stderr_tail: str = ""


def which(name: str, env_var: str) -> Optional[str]:
    explicit = os.getenv(env_var)
    if explicit:
        return explicit if os.path.exists(explicit) else None
    return shutil.which(name)


class AgentCli:
    def __init__(self, core):
        self.core = core
        self.root = core.settings.data_dir / "cli"
        self.codex_home = self.root / "codex"
        self.opencode_home = self.root / "opencode"
        for d in (self.root, self.codex_home, self.opencode_home):
            d.mkdir(parents=True, exist_ok=True)
            os.chmod(d, 0o700)
        self._login_proc: Optional[asyncio.subprocess.Process] = None
        # Tests may point the CLIs at a fake model server via extra config.
        self.codex_extra_args: List[str] = []
        self.opencode_extra_config: Dict[str, Any] = {}
        self.extra_env: Dict[str, str] = {}

    # ------------------------------------------------------------ discovery
    @property
    def codex_bin(self) -> Optional[str]:
        return which("codex", "CODEX_BIN")

    @property
    def opencode_bin(self) -> Optional[str]:
        return which("opencode", "OPENCODE_BIN")

    def _base_env(self) -> Dict[str, str]:
        # Minimal environment: never leak server secrets (APP_AUTH_TOKEN, keys…).
        env = {"PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"), "LANG": "C.UTF-8",
               "NO_COLOR": "1", "TERM": "dumb"}
        for k in ("HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "SSL_CERT_FILE", "NODE_EXTRA_CA_CERTS"):
            if os.environ.get(k):
                env[k] = os.environ[k]
        env.update(self.extra_env)
        return env

    def codex_env(self) -> Dict[str, str]:
        return {**self._base_env(), "HOME": str(self.codex_home), "CODEX_HOME": str(self.codex_home)}

    def opencode_env(self, permissions: Dict[str, str]) -> Dict[str, str]:
        env = {**self._base_env(), "HOME": str(self.opencode_home),
               "XDG_CONFIG_HOME": str(self.opencode_home / "config"),
               "XDG_DATA_HOME": str(self.opencode_home / "data"),
               "XDG_CACHE_HOME": str(self.opencode_home / "cache"),
               "XDG_STATE_HOME": str(self.opencode_home / "state"),
               "OPENCODE_DISABLE_AUTOUPDATE": "1"}
        key = self.opencode_key()
        if key:
            env["OPENCODE_API_KEY"] = key
        cfg = {"$schema": "https://opencode.ai/config.json", "autoupdate": False, "share": "disabled",
               "permission": permissions, **self.opencode_extra_config}
        env["OPENCODE_CONFIG_CONTENT"] = json.dumps(cfg)
        return env

    # --------------------------------------------------------------- status
    async def _run(self, argv: List[str], env: Dict[str, str], cwd: Optional[str] = None,
                   timeout: float = 30.0, stdin: Optional[bytes] = None):
        proc = await asyncio.create_subprocess_exec(
            *argv, cwd=cwd, env=env, stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, start_new_session=True)
        try:
            out, err = await asyncio.wait_for(proc.communicate(stdin), timeout)
        except (asyncio.TimeoutError, asyncio.CancelledError):
            self._kill(proc)
            raise
        return proc.returncode, out.decode("utf-8", "replace"), err.decode("utf-8", "replace")

    @staticmethod
    def _kill(proc) -> None:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass

    async def status(self) -> Dict[str, Any]:
        out: Dict[str, Any] = {"codex": {"installed": bool(self.codex_bin)}, "opencode": {"installed": bool(self.opencode_bin)}}
        if self.codex_bin:
            try:
                code, so, se = await self._run([self.codex_bin, "login", "status"], self.codex_env(), timeout=20)
                text = (so + se).strip()
                out["codex"].update(logged_in=code == 0 and "not logged in" not in text.lower(),
                                    detail=truncate(str(redact(text)), 200))
            except (asyncio.TimeoutError, OSError) as exc:
                out["codex"].update(logged_in=False, detail=f"status failed: {type(exc).__name__}")
            out["codex"]["login"] = self.core.kv_get("codex_login", None)
        out["opencode"]["go_key_configured"] = bool(self.core.kv_get("opencode_go_secret_id"))
        return out

    # ------------------------------------------------- ChatGPT (Codex) login
    async def start_codex_login(self) -> Dict[str, Any]:
        """Starts ``codex login --device-auth`` and returns the verification
        URL and one-time code printed by the CLI. The user completes sign-in
        on OpenAI's page; the CLI stores tokens in CODEX_HOME."""
        if not self.codex_bin:
            raise CliError("Codex CLI is not installed on the server.")
        if self._login_proc and self._login_proc.returncode is None:
            self._kill(self._login_proc)
        proc = await asyncio.create_subprocess_exec(
            self.codex_bin, "login", "--device-auth", env=self.codex_env(), stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, start_new_session=True)
        self._login_proc = proc
        lines: List[str] = []
        url = code = None
        loop = asyncio.get_running_loop()
        deadline = loop.time() + 30
        while loop.time() < deadline and not (url and code):
            try:
                raw = await asyncio.wait_for(proc.stdout.readline(), max(0.1, deadline - loop.time()))
            except asyncio.TimeoutError:
                break
            if not raw:
                break
            line = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", raw.decode("utf-8", "replace")).strip()
            if line:
                lines.append(line)
            url = url or next(iter(URL_RE.findall(line)), None)
            code = code or next(iter(CODE_RE.findall(line)), None)
        state = {"started_at": now_iso(), "url": url, "code": code,
                 "status": "waiting_for_user" if url else "failed",
                 "output": [truncate(str(redact(x)), 200) for x in lines[-8:]]}
        self.core.kv_set("codex_login", state)
        if url:
            asyncio.ensure_future(self._finish_login(proc))
        with self.core.db.tx():
            self.core.audit("integration.codex_login_start", actor_type="user", outcome=state["status"])
        return state

    async def _finish_login(self, proc) -> None:
        try:
            await asyncio.wait_for(proc.wait(), 900)
        except asyncio.TimeoutError:
            self._kill(proc)
        state = self.core.kv_get("codex_login", {}) or {}
        state["status"] = "completed" if proc.returncode == 0 else "failed"
        state["finished_at"] = now_iso()
        self.core.kv_set("codex_login", state)
        with self.core.db.tx():
            self.core.emit("integration.codex_login", status=state["status"])

    async def codex_logout(self) -> None:
        if self.codex_bin:
            await self._run([self.codex_bin, "logout"], self.codex_env(), timeout=20)
        self.core.kv_set("codex_login", None)

    # ------------------------------------------------------- OpenCode Go key
    def set_opencode_key(self, key: Optional[str]) -> None:
        old = self.core.kv_get("opencode_go_secret_id")
        if key:
            sid = self.core.secrets.put("opencode-go", key.strip(), "api_key", old)
            self.core.kv_set("opencode_go_secret_id", sid)
        else:
            self.core.secrets.delete(old)
            self.core.kv_set("opencode_go_secret_id", None)

    def opencode_key(self) -> Optional[str]:
        return self.core.secrets.get(self.core.kv_get("opencode_go_secret_id"))

    # --------------------------------------------------------------- runners
    async def run_codex(self, prompt: str, *, cwd: Path, sandbox: str = "read-only", model: Optional[str] = None,
                        timeout: float = 900.0) -> CliResult:
        if not self.codex_bin:
            raise CliError("Codex CLI is not installed on the server.")
        if sandbox not in ("read-only", "workspace-write"):
            raise CliError("sandbox must be read-only or workspace-write")
        last = self.codex_home / f"last-{uuid.uuid4().hex}.txt"
        argv = [self.codex_bin, "exec", "--json", "--ephemeral", "--skip-git-repo-check", "--color", "never",
                "-s", sandbox, "-C", str(cwd), "-o", str(last), *self.codex_extra_args]
        if model:
            argv += ["-m", model]
        argv.append("-")  # prompt from stdin (no length limits, not visible in ps)
        code, out, err = await self._run(argv, self.codex_env(), cwd=str(cwd), timeout=timeout,
                                         stdin=prompt.encode("utf-8"))
        res = CliResult(text="", exit_code=code, stderr_tail=truncate(str(redact(err)), 600))
        error = None
        for line in out.splitlines():
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            t = ev.get("type")
            if t == "thread.started":
                res.session_id = ev.get("thread_id")
            elif t == "item.completed":
                item = ev.get("item") or {}
                kind = item.get("type")
                if kind == "agent_message":
                    res.text = item.get("text") or res.text
                elif kind in ("command_execution", "file_change", "mcp_tool_call", "web_search"):
                    res.actions.append(redact({k: item.get(k) for k in ("type", "command", "status", "exit_code",
                                                                       "changes", "server", "tool", "query")
                                               if item.get(k) is not None}))
            elif t == "turn.completed":
                u = ev.get("usage") or {}
                res.input_tokens += int(u.get("input_tokens") or 0)
                res.output_tokens += int(u.get("output_tokens") or 0)
            elif t in ("turn.failed", "error"):
                error = (ev.get("error") or {}).get("message") if isinstance(ev.get("error"), dict) else ev.get("message")
        if last.exists():
            res.text = last.read_text(encoding="utf-8", errors="replace").strip() or res.text
            last.unlink()
        if error or (code != 0 and not res.text):
            msg = error or res.stderr_tail or f"exit code {code}"
            if "login" in msg.lower() or "unauthorized" in msg.lower() or "401" in msg:
                raise CliError(f"Codex is not signed in or the session expired: {truncate(str(redact(msg)), 300)}")
            raise CliError(f"Codex failed: {truncate(str(redact(msg)), 300)}")
        return res

    async def run_opencode(self, prompt: str, *, cwd: Path, model: str, allow_edits: bool = False,
                           timeout: float = 900.0) -> CliResult:
        if not self.opencode_bin:
            raise CliError("OpenCode CLI is not installed on the server.")
        if not model or "/" not in model:
            raise CliError("OpenCode model must look like provider/model, e.g. opencode-go/<model>.")
        # OpenCode has no OS sandbox: shell and web access are always denied here.
        perms = {"bash": "deny", "webfetch": "deny", "edit": "allow" if allow_edits else "deny"}
        argv = [self.opencode_bin, "run", "--format", "json", "-m", model, "--dir", str(cwd), truncate(prompt, 100_000)]
        code, out, err = await self._run(argv, self.opencode_env(perms), cwd=str(cwd), timeout=timeout)
        res = CliResult(text="", exit_code=code, stderr_tail=truncate(str(redact(err)), 600))
        texts: List[str] = []
        error = None
        for line in out.splitlines():
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            part = ev.get("part") or {}
            res.session_id = ev.get("sessionID") or res.session_id
            t = ev.get("type")
            if t == "text" and part.get("text"):
                texts.append(part["text"])
            elif t == "tool_use" or part.get("type") == "tool":
                res.actions.append(redact({"type": "tool", "tool": part.get("tool"),
                                           "status": (part.get("state") or {}).get("status")}))
            elif t == "step_finish":
                tok = part.get("tokens") or {}
                res.input_tokens += int(tok.get("input") or 0)
                res.output_tokens += int(tok.get("output") or 0)
            elif t == "error":
                error = json.dumps(ev.get("error") or ev)[:400]
        res.text = (texts[-1] if texts else "").strip()
        if error or (code != 0 and not res.text):
            msg = error or res.stderr_tail or f"exit code {code}"
            raise CliError(f"OpenCode failed: {truncate(str(redact(msg)), 300)}")
        return res


def transcript_prompt(system: str, messages: List[Dict[str, Any]]) -> str:
    """Flattens LowBot's transcript into one prompt for a CLI agent."""
    parts = []
    if system:
        parts.append(f"<instructions>\n{system}\n</instructions>")
    for m in messages:
        role = m.get("role")
        if role == "tool":
            parts.append(f"<tool_result name=\"{m.get('name')}\">\n{m.get('content', '')}\n</tool_result>")
        elif role == "assistant":
            if m.get("content"):
                parts.append(f"<assistant>\n{m['content']}\n</assistant>")
        else:
            parts.append(f"<user>\n{m.get('content', '')}\n</user>")
    parts.append("Reply to the latest user message. Your final message is delivered as the answer.")
    return "\n\n".join(parts)


# ---------------------------------------------------------------- tools ----
def register_agent_tools(runtime) -> None:
    """``codex.run`` / ``opencode.run``: delegate a coding task to an agent CLI
    working inside the bot's workspace. Default policy ``ask`` — workspace
    content is sent to the CLI's cloud model and files may change."""
    from app.v2.tools.builtin import ToolError, workspace_root
    from app.v2.tools.registry import ToolSpec

    cli = AgentCli(runtime.core)
    runtime.services["agent_cli"] = cli
    reg = runtime.services["tools"]
    S = {"type": "string"}

    def summarize(name):
        return lambda a: {"summary": f"{name}: {truncate(a.get('task', ''), 160)}",
                          "effect": "sends the task and workspace files to an external AI service"
                                    + ("; may modify workspace files" if a.get("sandbox") == "workspace-write"
                                       or a.get("allow_edits") else ""),
                          "target": a.get("model") or name}

    def report(r: CliResult) -> Dict[str, Any]:
        return {"result": truncate(r.text, 12000), "actions": r.actions[:40],
                "usage": {"input_tokens": r.input_tokens, "output_tokens": r.output_tokens}}

    if cli.codex_bin:
        async def codex_run(ctx, a):
            try:
                return report(await cli.run_codex(a["task"], cwd=workspace_root(ctx),
                                                  sandbox=a.get("sandbox", "read-only"), model=a.get("model")))
            except CliError as exc:
                raise ToolError(str(exc))
        reg.register(ToolSpec(
            "codex.run", "Delegate a coding/analysis task to OpenAI Codex (ChatGPT sign-in) running in your "
                         "workspace. sandbox=read-only (default) or workspace-write to let it edit files.",
            {"type": "object", "properties": {"task": S, "sandbox": {"enum": ["read-only", "workspace-write"]}, "model": S},
             "required": ["task"], "additionalProperties": False},
            codex_run, "external", "ask", timeout_s=960, summarize=summarize("Codex")))
        runtime.services["capabilities"].add("codex")

    if cli.opencode_bin:
        async def opencode_run(ctx, a):
            model = a.get("model") or runtime.core.kv_get("opencode_default_model") or ""
            try:
                return report(await cli.run_opencode(a["task"], cwd=workspace_root(ctx), model=model,
                                                     allow_edits=bool(a.get("allow_edits"))))
            except CliError as exc:
                raise ToolError(str(exc))
        reg.register(ToolSpec(
            "opencode.run", "Delegate a coding task to the OpenCode agent (e.g. OpenCode Go models) in your workspace. "
                            "Shell and web are disabled; allow_edits=true lets it edit files.",
            {"type": "object", "properties": {"task": S, "model": S, "allow_edits": {"type": "boolean"}},
             "required": ["task"], "additionalProperties": False},
            opencode_run, "external", "ask", timeout_s=960, summarize=summarize("OpenCode")))
        runtime.services["capabilities"].add("opencode")
