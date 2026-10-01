"""Provider profiles, adapter resolution, capability testing and usage/budget."""

from __future__ import annotations

import base64
import json
from typing import Any, Dict, List, Optional, Tuple

from app.v2.core import Core
from app.v2.db import loads
from app.v2.providers.base import ModelAdapter, ModelRequest, ProviderError, ToolWire
from app.v2.providers.openai_compat import ChatCompletionsAdapter, ResponsesAdapter
from app.v2.providers.scripted import ScriptedAdapter, script_from_json
from app.v2.util import new_id, now_dt, now_iso

# Presets carry only documented public base URLs. Model ids are never
# invented: the user enters them or picks from the provider's /models list.
PRESETS: Dict[str, Dict[str, Any]] = {
    "openai_responses": {"label": "OpenAI Responses API", "adapter": "responses",
                         "base_url": "https://api.openai.com/v1", "key_required": True},
    "chat_completions": {"label": "Chat Completions-compatible", "adapter": "chat",
                         "base_url": "", "key_required": False},
    "xai": {"label": "xAI API (Chat Completions)", "adapter": "chat",
            "base_url": "https://api.x.ai/v1", "key_required": True},
    "openrouter": {"label": "OpenRouter", "adapter": "chat",
                   "base_url": "https://openrouter.ai/api/v1", "key_required": True},
    "opencode_go": {"label": "OpenCode Go (subscription, API key)", "adapter": "chat",
                    "base_url": "https://opencode.ai/zen/go/v1", "key_required": True},
    "codex_cli": {"label": "ChatGPT via Codex CLI (sign in with ChatGPT)", "adapter": "codex",
                  "base_url": "", "key_required": False},
    "opencode_cli": {"label": "OpenCode CLI agent (e.g. opencode-go/<model>)", "adapter": "opencode",
                     "base_url": "", "key_required": False},
    "local": {"label": "Local OpenAI-compatible endpoint", "adapter": "chat",
              "base_url": "http://127.0.0.1:11434/v1", "key_required": False},
    "scripted_mock": {"label": "Scripted mock (offline, NOT a model)", "adapter": "mock",
                      "base_url": "", "key_required": False},
}

def _solid_png(rgb=(220, 20, 20), size=32) -> bytes:
    """Build a valid solid-colour PNG for the optional vision probe."""
    import struct
    import zlib

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(rgb) * size for _ in range(size))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


_RED_PNG = _solid_png()


class ProviderService:
    def __init__(self, core: Core):
        self.core, self.db = core, core.db
        # Tests may inject adapters (e.g. a ScriptedAdapter with a Python fn)
        # or an httpx transport per profile.
        self.overrides: Dict[str, ModelAdapter] = {}
        self.transports: Dict[str, Any] = {}

    # -- profiles ------------------------------------------------------------
    def _row(self, row: Dict[str, Any]) -> Dict[str, Any]:
        p = dict(row)
        for k, d in (("models_json", []), ("capabilities_json", {}), ("prices_json", {}), ("last_test_json", {})):
            p[k.replace("_json", "")] = loads(p.pop(k), d)
        p["api_key_configured"] = bool(p.pop("api_key_secret_id"))
        p["allow_fallback"] = bool(p["allow_fallback"])
        p["is_mock"] = p["kind"] == "scripted_mock"
        return p

    def list(self) -> List[Dict[str, Any]]:
        return [self._row(r) for r in self.db.all("SELECT * FROM provider_profiles ORDER BY created_at")]

    def get(self, profile_id: str) -> Optional[Dict[str, Any]]:
        row = self.db.one("SELECT * FROM provider_profiles WHERE id = ?", (profile_id,))
        return self._row(row) if row else None

    def upsert(self, data: Dict[str, Any], profile_id: Optional[str] = None) -> Dict[str, Any]:
        kind = data.get("kind") or (self.get(profile_id) or {}).get("kind")
        if kind not in PRESETS:
            raise ValueError(f"Unknown provider kind. Use one of: {', '.join(PRESETS)}")
        now = now_iso()
        values: Dict[str, Any] = {"updated_at": now}
        for key in ("name", "base_url", "default_model", "fallback_profile_id"):
            if key in data:
                values[key] = (data[key] or "").strip() or None if key == "fallback_profile_id" else (data[key] or "").strip()
        if "base_url" in values:
            from app.v2.netguard import validate_provider_url
            validate_provider_url(values["base_url"], kind)
        for key in ("models", "capabilities", "prices"):
            if key in data:
                values[f"{key}_json"] = data[key] or ([] if key == "models" else {})
        if "allow_fallback" in data:
            values["allow_fallback"] = bool(data["allow_fallback"])
        if kind in ("codex_cli", "opencode_cli") and profile_id is None:
            values["capabilities_json"] = {"tools": False, "vision": False, "streaming": False,
                                           "note": "agent CLI: uses its own sandboxed tools, not LowBot tools"}
        if kind == "scripted_mock" and "script" in data:
            script_from_json(json.dumps(data["script"]))  # validate
            values["capabilities_json"] = {**(data.get("capabilities") or {}), "script": data["script"],
                                           "tools": True, "vision": False, "streaming": True}
        with self.db.tx():
            if profile_id is None:
                profile_id = new_id("prov")
                preset = PRESETS[kind]
                self.db.insert("provider_profiles", {
                    "id": profile_id, "name": values.get("name") or preset["label"], "kind": kind,
                    "base_url": values.get("base_url", preset["base_url"]) or preset["base_url"],
                    "default_model": values.get("default_model", ""),
                    "models_json": values.get("models_json", []),
                    "capabilities_json": values.get("capabilities_json", {}),
                    "prices_json": values.get("prices_json", {}),
                    "fallback_profile_id": values.get("fallback_profile_id"),
                    "allow_fallback": values.get("allow_fallback", False),
                    "created_at": now, "updated_at": now,
                })
            else:
                if not self.get(profile_id):
                    raise ValueError("Unknown provider profile.")
                self.db.update("provider_profiles", {"id": profile_id}, values)
            if data.get("api_key"):
                existing = self.db.scalar("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", (profile_id,))
                sid = self.core.secrets.put(f"provider:{profile_id}", data["api_key"], "api_key", existing)
                self.db.update("provider_profiles", {"id": profile_id}, {"api_key_secret_id": sid})
            if data.get("clear_api_key"):
                sid = self.db.scalar("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", (profile_id,))
                self.core.secrets.delete(sid)
                self.db.update("provider_profiles", {"id": profile_id}, {"api_key_secret_id": None})
            if self.core.kv_get("default_provider_profile_id") is None:
                self.db.execute("INSERT OR REPLACE INTO kv(key, value) VALUES ('default_provider_profile_id', ?)",
                                (json.dumps(profile_id),))
            self.core.audit("provider.save", actor_type="user", profile_id=profile_id, kind=kind)
        return self.get(profile_id)

    def delete(self, profile_id: str) -> None:
        sid = self.db.scalar("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", (profile_id,))
        with self.db.tx():
            self.db.execute("DELETE FROM provider_profiles WHERE id = ?", (profile_id,))
            self.db.execute("UPDATE bots SET provider_profile_id = NULL WHERE provider_profile_id = ?", (profile_id,))
        self.core.secrets.delete(sid)

    # -- resolution ----------------------------------------------------------
    def resolve(self, bot: Dict[str, Any]) -> Tuple[Dict[str, Any], ModelAdapter, str]:
        profile_id = bot.get("provider_profile_id") or self.core.kv_get("default_provider_profile_id")
        if not profile_id:
            raise ProviderError("config", "No model provider configured. Add one in Settings → Models.")
        profile = self.get(profile_id)
        if not profile:
            raise ProviderError("config", "The bot's provider profile no longer exists.")
        model = bot.get("model") or profile["default_model"]
        if not model and profile["kind"] == "codex_cli":
            model = "default"  # let Codex use the plan's default model
        if not model:
            raise ProviderError("config", "No model id set for this bot or provider profile.")
        return profile, self.adapter(profile), model

    def adapter(self, profile: Dict[str, Any]) -> ModelAdapter:
        if profile["id"] in self.overrides:
            return self.overrides[profile["id"]]
        preset = PRESETS[profile["kind"]]
        if preset["adapter"] == "mock":
            return ScriptedAdapter(script=(profile.get("capabilities") or {}).get("script") or [])
        if preset["adapter"] in ("codex", "opencode"):
            from app.v2.providers.cli import CodexCliAdapter, OpenCodeCliAdapter
            cli = self.core.services.get("agent_cli")
            if cli is None:
                raise ProviderError("config", "Agent CLI integration is not available.")
            return CodexCliAdapter(cli) if preset["adapter"] == "codex" else OpenCodeCliAdapter(cli)
        row = self.db.one("SELECT api_key_secret_id FROM provider_profiles WHERE id = ?", (profile["id"],))
        key = self.core.secrets.get(row["api_key_secret_id"]) if row else None
        if not key and profile["kind"] == "opencode_go":
            key = self.core.secrets.get(self.core.kv_get("opencode_go_secret_id"))
        if preset["key_required"] and not key:
            raise ProviderError("config", f"API key missing for provider '{profile['name']}'.")
        headers = {}
        if profile["kind"] == "openrouter":
            headers = {"X-Title": "LowBot"}
        cls = ResponsesAdapter if preset["adapter"] == "responses" else ChatCompletionsAdapter
        return cls(profile["base_url"], key, headers, transport=self.transports.get(profile["id"]))

    # -- test connection -----------------------------------------------------
    async def test(self, profile_id: str, model: Optional[str] = None, probe_vision: bool = False) -> Dict[str, Any]:
        """Probe real capabilities; nothing is assumed from the preset."""
        profile = self.get(profile_id)
        if not profile:
            raise ValueError("Unknown provider profile.")
        result: Dict[str, Any] = {"at": now_iso(), "is_mock": profile["is_mock"], "checks": {}}
        try:
            adapter = self.adapter(profile)
        except ProviderError as exc:
            result["checks"]["config"] = {"ok": False, "error": str(exc)}
            return self._save_test(profile_id, result, {})
        result["models"] = await adapter.list_models()
        model = model or profile["default_model"] or (result["models"] or [None])[0]
        if not model:
            result["checks"]["model"] = {"ok": False, "error": "Enter a model id (the provider did not list models)."}
            return self._save_test(profile_id, result, {})
        result["model"] = model
        caps: Dict[str, Any] = {}
        # 1. text
        try:
            r = await adapter.complete(ModelRequest(model=model, system="", timeout_s=60,
                                                    messages=[{"role": "user", "content": "Reply with the single word OK."}]))
            result["checks"]["text"] = {"ok": True, "sample": r.text[:60], "usage": [r.input_tokens, r.output_tokens]}
            caps["text"] = True
        except ProviderError as exc:
            result["checks"]["text"] = {"ok": False, "kind": exc.kind, "error": str(exc)}
            return self._save_test(profile_id, result, {"text": False})
        if PRESETS[profile["kind"]]["adapter"] in ("codex", "opencode"):
            result["checks"]["tools"] = {"ok": False, "detail": "agent CLI: LowBot tools are not exposed to it"}
            return self._save_test(profile_id, result, {**caps, "tools": False, "streaming": False})
        # 2. tool calling
        try:
            r = await adapter.complete(ModelRequest(
                model=model, system="You must call the provided tool.", timeout_s=60,
                messages=[{"role": "user", "content": "Call the ping tool with value 'abc'."}],
                tools=[ToolWire("ping", "Echo a value.", {"type": "object", "properties": {"value": {"type": "string"}},
                                                          "required": ["value"]})]))
            ok = any(c.name == "ping" for c in r.tool_calls)
            result["checks"]["tools"] = {"ok": ok, "detail": "tool call returned" if ok else "model answered without calling the tool"}
            caps["tools"] = ok
        except ProviderError as exc:
            result["checks"]["tools"] = {"ok": False, "kind": exc.kind, "error": str(exc)}
            caps["tools"] = False
        # 3. streaming
        try:
            chunks: List[str] = []

            async def _d(t: str) -> None:
                chunks.append(t)
            await adapter.complete(ModelRequest(model=model, system="", timeout_s=60,
                                                messages=[{"role": "user", "content": "Say hi."}]), on_delta=_d)
            caps["streaming"] = bool(chunks)
            result["checks"]["streaming"] = {"ok": bool(chunks), "chunks": len(chunks)}
        except ProviderError as exc:
            caps["streaming"] = False
            result["checks"]["streaming"] = {"ok": False, "kind": exc.kind, "error": str(exc)}
        # 4. vision (opt-in; costs a request with an image)
        if probe_vision:
            url = "data:image/png;base64," + base64.b64encode(_RED_PNG).decode()
            try:
                r = await adapter.complete(ModelRequest(model=model, system="", timeout_s=60, messages=[
                    {"role": "user", "content": "What colour is this image? One word.", "images": [url]}]))
                ok = "red" in r.text.lower()
                caps["vision"] = ok
                result["checks"]["vision"] = {"ok": ok, "sample": r.text[:40]}
            except ProviderError as exc:
                caps["vision"] = False
                result["checks"]["vision"] = {"ok": False, "kind": exc.kind, "error": str(exc)}
        return self._save_test(profile_id, result, caps)

    def _save_test(self, profile_id: str, result: Dict[str, Any], caps: Dict[str, Any]) -> Dict[str, Any]:
        profile = self.get(profile_id)
        merged = {**(profile.get("capabilities") or {}), **caps, "tested_at": result["at"]}
        with self.db.tx():
            self.db.update("provider_profiles", {"id": profile_id},
                           {"last_test_json": result, "capabilities_json": merged, "updated_at": now_iso()})
        result["capabilities"] = merged
        return result

    # -- usage & budget ------------------------------------------------------
    @staticmethod
    def price(profile: Dict[str, Any], model: str, tin: int, tout: int) -> Optional[float]:
        prices = (profile.get("prices") or {}).get(model)
        if not prices:
            return None  # unpriced: never invent a price
        return tin / 1e6 * float(prices.get("input_per_mtok", 0)) + tout / 1e6 * float(prices.get("output_per_mtok", 0))

    def spent_today(self, bot_id: str) -> float:
        start = now_dt().replace(hour=0, minute=0, second=0, microsecond=0)
        from app.v2.util import iso
        return float(self.db.scalar(
            "SELECT COALESCE(SUM(CASE WHEN status = 'confirmed' THEN COALESCE(cost_confirmed, cost_estimated) "
            "WHEN status = 'reserved' THEN cost_estimated ELSE 0 END), 0) FROM usage_entries "
            "WHERE bot_id = ? AND created_at >= ?", (bot_id, iso(start))) or 0)

    def reserve(self, *, bot: Dict[str, Any], run_id: str, profile: Dict[str, Any], model: str,
                est_in: int, est_out: int) -> str:
        """Budget is checked and reserved atomically BEFORE the model call so
        parallel runs of one bot cannot overspend together."""
        budget = bot.get("budget") or {}
        cost = self.price(profile, model, est_in, est_out) or 0.0
        uid = new_id("use")
        with self.db.tx():
            limit = budget.get("max_cost_per_day")
            if limit is not None and self.spent_today(bot["id"]) + cost > float(limit):
                raise ProviderError("budget", f"Daily cost budget of {limit} reached for {bot['name']}.")
            max_tokens = budget.get("max_tokens_per_run")
            if max_tokens is not None:
                used = int(self.db.scalar(
                    "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM usage_entries WHERE run_id = ? AND status = 'confirmed'",
                    (run_id,)) or 0)
                if used + est_in > int(max_tokens):
                    raise ProviderError("budget", f"Token budget per run ({max_tokens}) reached.")
            self.db.insert("usage_entries", {
                "id": uid, "run_id": run_id, "bot_id": bot["id"], "kind": "tokens", "status": "reserved",
                "provider": profile["kind"], "model": model, "input_tokens": 0, "output_tokens": 0,
                "cost_estimated": cost, "created_at": now_iso(),
            })
        return uid

    def confirm(self, usage_id: str, profile: Dict[str, Any], model: str, tin: int, tout: int) -> None:
        cost = self.price(profile, model, tin, tout)
        with self.db.tx():
            self.db.update("usage_entries", {"id": usage_id}, {
                "status": "confirmed", "input_tokens": tin, "output_tokens": tout,
                "cost_estimated": cost or 0.0, "cost_confirmed": cost,
            })

    def release(self, usage_id: str) -> None:
        with self.db.tx():
            self.db.update("usage_entries", {"id": usage_id}, {"status": "released", "cost_estimated": 0})

    def usage_summary(self, bot_id: Optional[str] = None) -> Dict[str, Any]:
        where, params = ("WHERE bot_id = ?", (bot_id,)) if bot_id else ("", ())
        rows = self.db.all(
            f"SELECT bot_id, kind, status, SUM(input_tokens) tin, SUM(output_tokens) tout, "
            f"SUM(cost_estimated) est, SUM(cost_confirmed) conf, COUNT(*) n FROM usage_entries {where} "
            f"GROUP BY bot_id, kind, status", params)
        return {"rows": rows, "note": "Costs are only computed for models with user-entered prices; "
                                      "'est' is a pre-call reservation, 'conf' uses reported token usage."}
