"""Providers backed by agent CLIs: a bot's replies come from ``codex exec``
(ChatGPT sign-in) or ``opencode run`` (e.g. OpenCode Go).

These agents use their *own* tools inside their own sandbox; LowBot's tool
calling is not available through them (capability ``tools: false``), so the
engine runs such bots text-only and records that limitation.
"""

from __future__ import annotations

from typing import Optional

from app.v2.agents_cli import AgentCli, CliError, transcript_prompt
from app.v2.providers.base import DeltaCallback, ModelAdapter, ModelRequest, ModelResponse, ProviderError


def _map(exc: CliError) -> ProviderError:
    msg = str(exc)
    low = msg.lower()
    if "not installed" in low:
        return ProviderError("config", msg)
    if "signed in" in low or "unauthorized" in low or "401" in low or "api key" in low:
        return ProviderError("auth", msg)
    if "rate" in low and "limit" in low or "429" in low:
        return ProviderError("rate_limit", msg, retryable=True, retry_after=60)
    return ProviderError("server", msg)


class CodexCliAdapter(ModelAdapter):
    kind = "codex_cli"

    def __init__(self, cli: AgentCli, sandbox: str = "read-only"):
        self.cli, self.sandbox = cli, sandbox

    async def complete(self, request: ModelRequest, on_delta: DeltaCallback = None) -> ModelResponse:
        cwd = request.workdir or str(self.cli.root)
        try:
            r = await self.cli.run_codex(transcript_prompt(request.system, request.messages), cwd=cwd,
                                         sandbox=self.sandbox, model=None if request.model in ("", "default") else request.model,
                                         timeout=max(request.timeout_s, 600))
        except CliError as exc:
            raise _map(exc) from exc
        if on_delta and r.text:
            await on_delta(r.text)
        return ModelResponse(text=r.text, tool_calls=[], input_tokens=r.input_tokens, output_tokens=r.output_tokens,
                             finish_reason="stop", raw_model="codex")

    async def list_models(self) -> Optional[list]:
        return None


class OpenCodeCliAdapter(ModelAdapter):
    kind = "opencode_cli"

    def __init__(self, cli: AgentCli):
        self.cli = cli

    async def complete(self, request: ModelRequest, on_delta: DeltaCallback = None) -> ModelResponse:
        cwd = request.workdir or str(self.cli.root)
        try:
            r = await self.cli.run_opencode(transcript_prompt(request.system, request.messages), cwd=cwd,
                                            model=request.model, allow_edits=False, timeout=max(request.timeout_s, 600))
        except CliError as exc:
            raise _map(exc) from exc
        if on_delta and r.text:
            await on_delta(r.text)
        return ModelResponse(text=r.text, tool_calls=[], input_tokens=r.input_tokens, output_tokens=r.output_tokens,
                             finish_reason="stop", raw_model=request.model)
