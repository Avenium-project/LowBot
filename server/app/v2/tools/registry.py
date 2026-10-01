"""Tool contracts and the registry the gateway executes from.

Every tool — internal, workspace, web, browser, MCP — is a :class:`ToolSpec`
with a JSON schema. The model only ever *proposes* a call; the gateway in
:mod:`app.v2.engine` validates, applies policy, asks for approval and runs
it. Unknown tools are rejected.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional, Union

import jsonschema

from app.v2.bots import tool_allowed

EffectKind = str  # "read" | "workspace" | "internal" | "external"
EFFECT_TEXT = {
    "read": "read-only",
    "workspace": "changes files in the workspace",
    "internal": "changes data inside LowBot",
    "external": "has an effect outside LowBot",
}


@dataclass
class Wait:
    """Returned by a tool that parks the run (question, delegation)."""
    kind: str  # "input" | "dependency"
    detail: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ToolContext:
    core: Any
    task: Dict[str, Any]
    run: Dict[str, Any]
    bot: Dict[str, Any]
    step_id: str
    idempotency_key: str
    services: Dict[str, Any]
    is_cancelled: Callable[[], bool] = lambda: False


Executor = Callable[[ToolContext, Dict[str, Any]], Awaitable[Union[Dict[str, Any], Wait]]]
Reconciler = Callable[[ToolContext, Dict[str, Any]], Awaitable[Optional[bool]]]


@dataclass
class ToolSpec:
    name: str
    description: str
    input_schema: Dict[str, Any]
    executor: Executor
    effect_kind: EffectKind = "read"
    default_effect: str = "allow"  # allow | ask | deny
    hard_ask: bool = False  # explicit allow rules cannot remove the approval
    summarize: Optional[Callable[[Dict[str, Any]], Dict[str, str]]] = None
    reconcile: Optional[Reconciler] = None
    requires: Optional[str] = None  # capability flag, e.g. "browser"
    timeout_s: Optional[float] = None  # overrides TOOL_TIMEOUT_SECONDS
    # Optional dynamic escalation: returns a reason when this particular call
    # needs approval even though the static policy allows the tool.
    escalate: Optional[Callable[[ToolContext, Dict[str, Any]], Awaitable[Optional[str]]]] = None

    def validate(self, args: Any) -> Optional[str]:
        if not isinstance(args, dict):
            return "Tool arguments must be a JSON object."
        try:
            jsonschema.validate(args, self.input_schema)
        except jsonschema.ValidationError as exc:
            return f"Invalid arguments: {exc.message}"
        return None

    def card(self, args: Dict[str, Any]) -> Dict[str, str]:
        if self.summarize:
            return self.summarize(args)
        preview = "; ".join(f"{k}: {str(v)[:80]}" for k, v in list((args or {}).items())[:3])
        return {"summary": f"{self.description.split('.')[0]} ({preview})" if preview else self.description.split(".")[0],
                "target": "", "effect": EFFECT_TEXT.get(self.effect_kind, self.effect_kind)}


def wire_name(name: str) -> str:
    """Function names on OpenAI-compatible APIs must match ^[a-zA-Z0-9_-]{1,64}$."""
    return re.sub(r"[^a-zA-Z0-9_-]", "_", name.replace(".", "__"))[:64]


class ToolRegistry:
    def __init__(self) -> None:
        self._static: Dict[str, ToolSpec] = {}
        self._dynamic: List[Callable[[Dict[str, Any]], List[ToolSpec]]] = []

    def register(self, spec: ToolSpec) -> None:
        if spec.name in self._static:
            raise ValueError(f"Duplicate tool: {spec.name}")
        self._static[spec.name] = spec

    def add_provider(self, provider: Callable[[Dict[str, Any]], List[ToolSpec]]) -> None:
        self._dynamic.append(provider)

    def all_for(self, bot: Dict[str, Any]) -> Dict[str, ToolSpec]:
        specs = dict(self._static)
        for provider in self._dynamic:
            for spec in provider(bot):
                specs[spec.name] = spec
        return specs

    def for_bot(self, bot: Dict[str, Any], skill_tools: Optional[List[str]] = None,
                capabilities: Optional[set] = None) -> Dict[str, ToolSpec]:
        out = {}
        for name, spec in self.all_for(bot).items():
            if not tool_allowed(bot.get("tools") or [], name):
                continue
            if skill_tools and not tool_allowed(skill_tools, name):
                continue
            if spec.requires and capabilities is not None and spec.requires not in capabilities:
                continue
            out[name] = spec
        return out

    def get(self, bot: Dict[str, Any], name: str) -> Optional[ToolSpec]:
        return self.all_for(bot).get(name)
