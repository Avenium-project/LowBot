"""Provider-neutral model contract used by the engine."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional

DeltaCallback = Optional[Callable[[str], Awaitable[None]]]


@dataclass
class ToolWire:
    name: str  # wire-safe name
    description: str
    parameters: Dict[str, Any]


@dataclass
class ModelRequest:
    model: str
    system: str
    # Normalised transcript:
    #   {"role": "user", "content": str, "images": [data_or_https_url]}
    #   {"role": "assistant", "content": str, "tool_calls": [{"id","name","arguments"}]}
    #   {"role": "tool", "call_id": str, "name": str, "content": str}
    messages: List[Dict[str, Any]]
    tools: List[ToolWire] = field(default_factory=list)
    max_output_tokens: Optional[int] = None
    timeout_s: float = 120.0
    workdir: Optional[str] = None  # used by CLI-agent providers (Codex/OpenCode)


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: Any  # dict when parseable, else raw string (validation fails explicitly)


@dataclass
class ModelResponse:
    text: str
    tool_calls: List[ToolCall]
    input_tokens: int = 0
    output_tokens: int = 0
    finish_reason: str = ""
    raw_model: str = ""


class ProviderError(Exception):
    """kind: auth | rate_limit | server | bad_request | timeout | network | capability | config"""

    def __init__(self, kind: str, message: str, *, retryable: bool = False, retry_after: Optional[float] = None):
        super().__init__(message)
        self.kind = kind
        self.retryable = retryable
        self.retry_after = retry_after


class ModelAdapter:
    kind = "base"
    is_mock = False

    async def complete(self, request: ModelRequest, on_delta: DeltaCallback = None) -> ModelResponse:
        raise NotImplementedError

    async def list_models(self) -> Optional[List[str]]:
        return None


def classify_http(status: int, retry_after: Optional[str] = None) -> ProviderError:
    ra = None
    try:
        ra = float(retry_after) if retry_after else None
    except ValueError:
        ra = None
    if status in (401, 403):
        return ProviderError("auth", f"Provider rejected the credentials (HTTP {status}).")
    if status == 429:
        return ProviderError("rate_limit", "Provider rate limit (HTTP 429).", retryable=True, retry_after=ra)
    if status in (408, 409) or status >= 500:
        return ProviderError("server", f"Provider error (HTTP {status}).", retryable=True, retry_after=ra)
    if status == 404:
        return ProviderError("bad_request", "Endpoint or model not found (HTTP 404).")
    return ProviderError("bad_request", f"Provider refused the request (HTTP {status}).")
