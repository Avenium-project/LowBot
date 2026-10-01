"""Adapters for documented public HTTP APIs.

* :class:`ChatCompletionsAdapter` — ``POST {base}/chat/completions``
  (OpenAI Chat Completions contract; used with presets for xAI, OpenRouter
  and local OpenAI-compatible servers such as Ollama / LM Studio / vLLM).
* :class:`ResponsesAdapter` — ``POST {base}/responses`` (OpenAI Responses).

Compatibility of third-party endpoints is *checked* with the Test connection
flow (``app.v2.providers.service``) rather than assumed.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

import httpx

from app.v2.providers.base import (
    DeltaCallback, ModelAdapter, ModelRequest, ModelResponse, ProviderError, ToolCall, classify_http,
)
from app.v2.util import redact, truncate


def _parse_args(raw: Any) -> Any:
    if isinstance(raw, dict):
        return raw
    if raw in (None, ""):
        return {}
    try:
        value = json.loads(raw)
        return value if isinstance(value, dict) else raw
    except (TypeError, ValueError):
        return raw


def _error_detail(response: httpx.Response) -> str:
    try:
        body = response.json()
        err = body.get("error") if isinstance(body, dict) else None
        msg = err.get("message") if isinstance(err, dict) else (err if isinstance(err, str) else None)
        msg = msg or (body.get("message") if isinstance(body, dict) else None) or ""
    except ValueError:
        msg = response.text or ""
    return truncate(str(redact(msg)), 300)


class _HttpAdapter(ModelAdapter):
    def __init__(self, base_url: str, api_key: Optional[str], headers: Optional[Dict[str, str]] = None,
                 transport: Optional[httpx.AsyncBaseTransport] = None):
        if not base_url:
            raise ProviderError("config", "Provider base URL is not configured.")
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key or ""
        self.extra_headers = dict(headers or {})
        self._transport = transport

    def _headers(self, stream: bool = False) -> Dict[str, str]:
        h = {**self.extra_headers, "Content-Type": "application/json"}
        if self.api_key:
            h["Authorization"] = f"Bearer {self.api_key}"
        if stream:
            h["Accept"] = "text/event-stream"
        return h

    def _client(self, timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=httpx.Timeout(timeout, connect=15.0), transport=self._transport)

    async def _post(self, path: str, body: Dict[str, Any], timeout: float) -> Dict[str, Any]:
        try:
            async with self._client(timeout) as client:
                resp = await client.post(f"{self.base_url}{path}", json=body, headers=self._headers())
        except httpx.TimeoutException as exc:
            raise ProviderError("timeout", "Model request timed out.", retryable=True) from exc
        except httpx.HTTPError as exc:
            raise ProviderError("network", f"Network error: {type(exc).__name__}", retryable=True) from exc
        if not resp.is_success:
            err = classify_http(resp.status_code, resp.headers.get("retry-after"))
            detail = _error_detail(resp)
            if detail:
                err.args = (f"{err.args[0]} {detail}",)
            raise err
        try:
            return resp.json()
        except ValueError as exc:
            raise ProviderError("server", "Provider returned invalid JSON.", retryable=True) from exc

    async def _stream_lines(self, path: str, body: Dict[str, Any], timeout: float):
        try:
            async with self._client(timeout) as client:
                async with client.stream("POST", f"{self.base_url}{path}", json=body,
                                         headers=self._headers(stream=True)) as resp:
                    if not resp.is_success:
                        await resp.aread()
                        err = classify_http(resp.status_code, resp.headers.get("retry-after"))
                        detail = _error_detail(resp)
                        if detail:
                            err.args = (f"{err.args[0]} {detail}",)
                        raise err
                    data: List[str] = []
                    async for line in resp.aiter_lines():
                        if line.startswith("data:"):
                            data.append(line[5:].lstrip())
                        elif not line.strip() and data:
                            payload, data = "\n".join(data), []
                            if payload == "[DONE]":
                                return
                            try:
                                yield json.loads(payload)
                            except ValueError:
                                continue
                    if data and data != ["[DONE]"]:
                        try:
                            yield json.loads("\n".join(data))
                        except ValueError:
                            pass
        except httpx.TimeoutException as exc:
            raise ProviderError("timeout", "Model stream timed out.", retryable=True) from exc
        except httpx.HTTPError as exc:
            raise ProviderError("network", f"Network error: {type(exc).__name__}", retryable=True) from exc

    async def list_models(self) -> Optional[List[str]]:
        try:
            async with self._client(20) as client:
                resp = await client.get(f"{self.base_url}/models", headers=self._headers())
            if not resp.is_success:
                return None
            data = resp.json().get("data") or []
            return sorted({m.get("id") for m in data if isinstance(m, dict) and m.get("id")})
        except (httpx.HTTPError, ValueError, AttributeError):
            return None


class ChatCompletionsAdapter(_HttpAdapter):
    kind = "chat_completions"

    def _body(self, req: ModelRequest, stream: bool) -> Dict[str, Any]:
        messages: List[Dict[str, Any]] = []
        if req.system:
            messages.append({"role": "system", "content": req.system})
        for m in req.messages:
            if m["role"] == "user":
                if m.get("images"):
                    content: Any = [{"type": "text", "text": m.get("content") or ""}] + [
                        {"type": "image_url", "image_url": {"url": u}} for u in m["images"]]
                else:
                    content = m.get("content") or ""
                messages.append({"role": "user", "content": content})
            elif m["role"] == "assistant":
                item: Dict[str, Any] = {"role": "assistant", "content": m.get("content") or None}
                if m.get("tool_calls"):
                    item["tool_calls"] = [{
                        "id": c["id"], "type": "function",
                        "function": {"name": c["name"], "arguments": json.dumps(c["arguments"])
                                     if isinstance(c["arguments"], dict) else str(c["arguments"])},
                    } for c in m["tool_calls"]]
                messages.append(item)
            elif m["role"] == "tool":
                messages.append({"role": "tool", "tool_call_id": m["call_id"], "content": m.get("content") or ""})
        body: Dict[str, Any] = {"model": req.model, "messages": messages}
        if req.tools:
            body["tools"] = [{"type": "function", "function": {
                "name": t.name, "description": t.description, "parameters": t.parameters}} for t in req.tools]
        if req.max_output_tokens:
            body["max_tokens"] = req.max_output_tokens
        if stream:
            body["stream"] = True
            body["stream_options"] = {"include_usage": True}
        return body

    async def complete(self, request: ModelRequest, on_delta: DeltaCallback = None) -> ModelResponse:
        if on_delta is None:
            data = await self._post("/chat/completions", self._body(request, False), request.timeout_s)
            choices = data.get("choices") or []
            if not choices:
                raise ProviderError("server", "Provider returned no choices.", retryable=True)
            msg = choices[0].get("message") or {}
            calls = [ToolCall(c.get("id") or f"call_{i}", (c.get("function") or {}).get("name", ""),
                              _parse_args((c.get("function") or {}).get("arguments")))
                     for i, c in enumerate(msg.get("tool_calls") or [])]
            usage = data.get("usage") or {}
            return ModelResponse(text=msg.get("content") or "", tool_calls=calls,
                                 input_tokens=int(usage.get("prompt_tokens") or 0),
                                 output_tokens=int(usage.get("completion_tokens") or 0),
                                 finish_reason=choices[0].get("finish_reason") or "", raw_model=data.get("model", ""))
        text, calls, usage, finish, model = [], {}, {}, "", ""
        async for chunk in self._stream_lines("/chat/completions", self._body(request, True), request.timeout_s):
            if chunk.get("error"):
                raise ProviderError("server", "Provider stream error.", retryable=True)
            model = chunk.get("model") or model
            if chunk.get("usage"):
                usage = chunk["usage"]
            for choice in chunk.get("choices") or []:
                delta = choice.get("delta") or {}
                if delta.get("content"):
                    text.append(delta["content"])
                    await on_delta(delta["content"])
                for tc in delta.get("tool_calls") or []:
                    slot = calls.setdefault(tc.get("index", 0), {"id": "", "name": "", "args": ""})
                    slot["id"] = tc.get("id") or slot["id"]
                    fn = tc.get("function") or {}
                    slot["name"] += fn.get("name") or ""
                    slot["args"] += fn.get("arguments") or ""
                finish = choice.get("finish_reason") or finish
        return ModelResponse(
            text="".join(text),
            tool_calls=[ToolCall(v["id"] or f"call_{k}", v["name"], _parse_args(v["args"]))
                        for k, v in sorted(calls.items())],
            input_tokens=int(usage.get("prompt_tokens") or 0), output_tokens=int(usage.get("completion_tokens") or 0),
            finish_reason=finish, raw_model=model)


class ResponsesAdapter(_HttpAdapter):
    kind = "openai_responses"

    def _body(self, req: ModelRequest, stream: bool) -> Dict[str, Any]:
        items: List[Dict[str, Any]] = []
        for m in req.messages:
            if m["role"] == "user":
                content = [{"type": "input_text", "text": m.get("content") or ""}] + [
                    {"type": "input_image", "image_url": u} for u in m.get("images") or []]
                items.append({"role": "user", "content": content})
            elif m["role"] == "assistant":
                if m.get("content"):
                    items.append({"role": "assistant", "content": [{"type": "output_text", "text": m["content"]}]})
                for c in m.get("tool_calls") or []:
                    items.append({"type": "function_call", "call_id": c["id"], "name": c["name"],
                                  "arguments": json.dumps(c["arguments"]) if isinstance(c["arguments"], dict)
                                  else str(c["arguments"])})
            elif m["role"] == "tool":
                items.append({"type": "function_call_output", "call_id": m["call_id"], "output": m.get("content") or ""})
        body: Dict[str, Any] = {"model": req.model, "input": items, "store": False}
        if req.system:
            body["instructions"] = req.system
        if req.tools:
            body["tools"] = [{"type": "function", "name": t.name, "description": t.description,
                              "parameters": t.parameters} for t in req.tools]
        if req.max_output_tokens:
            body["max_output_tokens"] = req.max_output_tokens
        if stream:
            body["stream"] = True
        return body

    @staticmethod
    def _parse(data: Dict[str, Any]) -> ModelResponse:
        text, calls = [], []
        for item in data.get("output") or []:
            if item.get("type") == "message":
                for part in item.get("content") or []:
                    if part.get("type") in ("output_text", "refusal"):
                        text.append(part.get("text") or part.get("refusal") or "")
            elif item.get("type") == "function_call":
                calls.append(ToolCall(item.get("call_id") or item.get("id") or "", item.get("name", ""),
                                      _parse_args(item.get("arguments"))))
        usage = data.get("usage") or {}
        status = data.get("status") or ""
        if status in ("failed",):
            raise ProviderError("server", "Responses API reported a failed response.", retryable=True)
        return ModelResponse(text="".join(text), tool_calls=calls,
                             input_tokens=int(usage.get("input_tokens") or 0),
                             output_tokens=int(usage.get("output_tokens") or 0),
                             finish_reason=status, raw_model=data.get("model", ""))

    async def complete(self, request: ModelRequest, on_delta: DeltaCallback = None) -> ModelResponse:
        if on_delta is None:
            return self._parse(await self._post("/responses", self._body(request, False), request.timeout_s))
        final: Optional[Dict[str, Any]] = None
        async for event in self._stream_lines("/responses", self._body(request, True), request.timeout_s):
            et = event.get("type")
            if et == "response.output_text.delta" and event.get("delta"):
                await on_delta(event["delta"])
            elif et in ("response.completed", "response.incomplete"):
                final = event.get("response") or {}
            elif et in ("response.failed", "error"):
                raise ProviderError("server", f"Responses stream ended with {et}.", retryable=True)
        if final is None:
            raise ProviderError("server", "Responses stream ended before completion.", retryable=True)
        return self._parse(final)
