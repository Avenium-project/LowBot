"""Optional terminal tool (``SHELL_TOOL=1``), default policy ``ask``.

Runs in the bot's workspace directory with a scrubbed environment (no
server secrets), rlimits (CPU, memory, processes, file size) and a timeout.
This is resource limiting, NOT isolation: commands run as the server user and
can reach the network. For hostile code use the Docker computer provider or a
stronger sandbox (see SECURITY.md).
"""

from __future__ import annotations

import asyncio
import os
import resource
from typing import Any, Dict

from app.v2.tools.builtin import ToolError, workspace_root
from app.v2.tools.registry import ToolContext, ToolRegistry, ToolSpec
from app.v2.util import redact, truncate


def _limits() -> None:  # runs in the child before exec
    resource.setrlimit(resource.RLIMIT_CPU, (60, 60))
    resource.setrlimit(resource.RLIMIT_AS, (1 << 30, 1 << 30))
    resource.setrlimit(resource.RLIMIT_FSIZE, (100 << 20, 100 << 20))
    try:
        resource.setrlimit(resource.RLIMIT_NPROC, (256, 256))
    except (ValueError, OSError):
        pass
    os.setsid()


async def shell_run(ctx: ToolContext, a: Dict[str, Any]) -> Dict[str, Any]:
    cwd = workspace_root(ctx)
    env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": str(cwd), "LANG": "C.UTF-8", "TERM": "dumb"}
    proc = await asyncio.create_subprocess_exec(
        "/bin/sh", "-c", a["command"], cwd=str(cwd), env=env, stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, preexec_fn=_limits)
    try:
        out, err = await asyncio.wait_for(proc.communicate(), float(a.get("timeout_s") or 60))
    except asyncio.TimeoutError:
        try:
            os.killpg(proc.pid, 9)
        except ProcessLookupError:
            pass
        raise ToolError("Command timed out and was killed.")
    except asyncio.CancelledError:
        try:
            os.killpg(proc.pid, 9)
        except ProcessLookupError:
            pass
        raise
    return {"exit_code": proc.returncode, "stdout": redact(truncate(out.decode("utf-8", "replace"), 12000)),
            "stderr": redact(truncate(err.decode("utf-8", "replace"), 4000))}


def register_shell(reg: ToolRegistry) -> None:
    reg.register(ToolSpec(
        "shell.run", "Run a shell command in the workspace directory (limited resources, scrubbed env).",
        {"type": "object", "properties": {"command": {"type": "string", "maxLength": 4000},
                                          "timeout_s": {"type": "integer", "minimum": 1, "maximum": 300}},
         "required": ["command"], "additionalProperties": False},
        shell_run, "external", "ask", timeout_s=320,
        summarize=lambda a: {"summary": f"Run: {truncate(a.get('command', ''), 200)}",
                             "effect": "executes a command on the server", "target": "workspace"}))
