"""Wires a :class:`Core` into a runnable system (services, tools, engine,
scheduler) and owns the background loops' lifecycle."""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Any, Dict, Optional

from app.v2.approvals import ApprovalService
from app.v2.artifacts import ArtifactService
from app.v2.bots import BotService
from app.v2.config import V2Settings, load_settings
from app.v2.core import Core
from app.v2.engine import Engine
from app.v2.memory import MemoryService
from app.v2.providers.service import ProviderService
from app.v2.scheduler import RoutineService, ScheduleError
from app.v2.skills import SkillService
from app.v2.tasks import TaskService
from app.v2.tools.builtin import ToolError, register_builtin
from app.v2.tools.registry import ToolContext, ToolRegistry, ToolSpec

log = logging.getLogger("opendots.runtime")


def _routine_tool(reg: ToolRegistry) -> None:
    async def create(ctx: ToolContext, a: Dict[str, Any]) -> Dict[str, Any]:
        try:
            r = ctx.services["routines"].create(
                bot_id=ctx.bot["id"], name=a["name"], prompt=a["prompt"], schedule=a["schedule"],
                timezone_name=a.get("timezone"), conversation_id=ctx.task.get("conversation_id"))
        except (ScheduleError, ValueError) as exc:
            raise ToolError(str(exc))
        return {"routine_id": r["id"], "schedule": r["schedule"], "timezone": r["timezone"], "next_runs": r["preview"]}

    S = {"type": "string"}
    reg.register(ToolSpec(
        "routine.create", "Create a scheduled routine for yourself. schedule accepts natural language "
                          "(e.g. 'codziennie o 8:00', 'weekdays at 7:30') or cron. Default timezone Europe/Warsaw.",
        {"type": "object", "properties": {"name": S, "prompt": S, "schedule": S, "timezone": S},
         "required": ["name", "prompt", "schedule"], "additionalProperties": False},
        create, "internal", "ask",
        summarize=lambda a: {"summary": f"Create routine '{a.get('name')}' ({a.get('schedule')})",
                             "effect": "runs automatically on a schedule", "target": a.get("schedule", "")}))


class Runtime:
    def __init__(self, settings: Optional[V2Settings] = None, *, worker_id: Optional[str] = None):
        self.settings = settings or load_settings()
        self.core = Core(self.settings)
        s: Dict[str, Any] = self.core.services
        s["bots"] = BotService(self.core)
        s["tasks"] = TaskService(self.core, s["bots"])
        s["providers"] = ProviderService(self.core)
        s["approvals"] = ApprovalService(self.core)
        s["memory"] = MemoryService(self.core)
        s["artifacts"] = ArtifactService(self.core)
        s["skills"] = SkillService(self.core)
        s["routines"] = RoutineService(self.core, s["tasks"])
        s["capabilities"] = set()
        reg = ToolRegistry()
        register_builtin(reg)
        _routine_tool(reg)
        if os.getenv("SHELL_TOOL", "0") == "1":
            from app.v2.tools.shell import register_shell
            register_shell(reg)
            s["capabilities"].add("shell")
        s["tools"] = reg
        self.services = s
        from app.v2.agents_cli import register_agent_tools
        register_agent_tools(self)  # ChatGPT/Codex and OpenCode (tools only if the CLI is installed)
        self.engine = Engine(self.core, s, worker_id=worker_id)
        s["engine"] = self.engine
        self._stop = asyncio.Event()
        self._tasks: list = []
        self._extensions_loaded = False

    def load_extensions(self) -> None:
        """Optional subsystems (browser broker, MCP). Missing dependencies
        disable the capability instead of crashing the server."""
        if self._extensions_loaded:
            return
        self._extensions_loaded = True
        try:
            from app.v2.mcp_client import register_mcp
            register_mcp(self)
        except Exception as exc:  # pragma: no cover - optional dependency
            log.warning("MCP disabled: %s", exc)
        try:
            from app.v2.browser import register_browser
            register_browser(self)
        except Exception as exc:  # pragma: no cover - optional dependency
            log.warning("Browser broker disabled: %s", exc)

    async def start(self, *, worker: Optional[bool] = None, scheduler: Optional[bool] = None) -> None:
        self._stop = asyncio.Event()
        if worker if worker is not None else self.settings.embedded_worker:
            self._tasks.append(asyncio.ensure_future(self.engine.run_forever(self._stop)))
        if scheduler if scheduler is not None else self.settings.embedded_scheduler:
            self._tasks.append(asyncio.ensure_future(
                self.services["routines"].run_forever(self._stop, self.engine.worker_id)))

    async def stop(self) -> None:
        self._stop.set()
        self.core.bus.wake_workers()
        self.core.bus.notify()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks = []
        browser = self.services.get("browser")
        if browser:
            await browser.close()
        mcp = self.services.get("mcp")
        if mcp:
            await mcp.close()
