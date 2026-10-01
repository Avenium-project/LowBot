"""SCRIPTED MOCK provider — deterministic, offline, for tests and demos.

This is explicitly *not* a model. It is labelled ``is_mock = True`` and every
response it produces is tagged so the UI and test reports cannot mistake it
for a live integration.

Script format (list of rules, first match wins):
    {"when": "<regex on the latest user/tool text>",
     "reply": "text"}                                  -> final answer
    {"when": "...", "call": {"name": "tool.name", "arguments": {...}}}
    {"when": "...", "calls": [{...}, {...}]}           -> parallel tool calls
Rules match the latest *user* message by default; ``"on": "tool"`` (or
``"after_tool": "tool.name"``) matches right after a tool result and
``"on": "any"`` matches either. ``{{last}}`` in a reply is replaced with the latest text.
Without a match the mock replies ``"[mock] <last text>"``.
"""

from __future__ import annotations

import json
import re
from typing import Any, Callable, Dict, List, Optional

from app.v2.providers.base import DeltaCallback, ModelAdapter, ModelRequest, ModelResponse, ToolCall
from app.v2.tools.registry import wire_name


class ScriptedAdapter(ModelAdapter):
    kind = "scripted_mock"
    is_mock = True

    def __init__(self, script: Optional[List[Dict[str, Any]]] = None,
                 fn: Optional[Callable[[ModelRequest], ModelResponse]] = None):
        self.script = script or []
        self.fn = fn
        self.calls: List[ModelRequest] = []

    async def complete(self, request: ModelRequest, on_delta: DeltaCallback = None) -> ModelResponse:
        self.calls.append(request)
        if self.fn:
            resp = self.fn(request)
            if hasattr(resp, "__await__"):
                resp = await resp
            return resp
        last = request.messages[-1] if request.messages else {"role": "user", "content": ""}
        last_text = last.get("content") or ""
        last_tool = last.get("name") if last.get("role") == "tool" else None
        n = len(self.calls)
        role = last.get("role")
        for rule in self.script:
            on = rule.get("on", "tool" if rule.get("after_tool") else "user")
            if on != "any" and on != role:
                continue
            if rule.get("after_tool") and last_tool not in (rule["after_tool"], wire_name(rule["after_tool"])):
                continue
            if rule.get("when") and not re.search(rule["when"], last_text, re.I | re.S):
                continue
            calls = rule.get("calls") or ([rule["call"]] if rule.get("call") else [])
            if calls:
                return ModelResponse(text=rule.get("text", ""), tool_calls=[
                    ToolCall(f"call_{n}_{j}", wire_name(c["name"]), _fill(c.get("arguments") or {}, last_text))
                    for j, c in enumerate(calls)], input_tokens=10, output_tokens=5, raw_model="scripted-mock")
            text = str(rule.get("reply", "")).replace("{{last}}", last_text)
            if on_delta:
                await on_delta(text)
            return ModelResponse(text=text, tool_calls=[], input_tokens=10, output_tokens=len(text.split()),
                                 raw_model="scripted-mock")
        text = f"[mock] {last_text}"
        if on_delta:
            await on_delta(text)
        return ModelResponse(text=text, tool_calls=[], input_tokens=10, output_tokens=5, raw_model="scripted-mock")

    async def list_models(self):
        return ["scripted-mock"]


def _fill(value: Any, last: str) -> Any:
    if isinstance(value, str):
        return value.replace("{{last}}", last)
    if isinstance(value, dict):
        return {k: _fill(v, last) for k, v in value.items()}
    if isinstance(value, list):
        return [_fill(v, last) for v in value]
    return value


def script_from_json(text: str) -> List[Dict[str, Any]]:
    data = json.loads(text)
    if not isinstance(data, list):
        raise ValueError("Mock script must be a JSON list of rules.")
    return data
