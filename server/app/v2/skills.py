"""Versioned skills: instructions + inputs + tool boundary + completion criteria.

Invoked in chat with ``/slug``. Export/import never contains secrets or
sessions. "Teach a task" turns a consented recording of tool steps into an
editable DRAFT skill — not a promise of reliable replay.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

from app.v2.core import Core
from app.v2.db import loads
from app.v2.util import new_id, now_iso, redact, slugify

FORMAT = "opendots.skill.v1"


class SkillError(ValueError):
    pass


class SkillService:
    def __init__(self, core: Core):
        self.core, self.db = core, core.db

    def _full(self, skill_id: str, version: Optional[int] = None) -> Optional[Dict[str, Any]]:
        s = self.db.one("SELECT * FROM skills WHERE id = ?", (skill_id,))
        if not s:
            return None
        v = self.db.one("SELECT * FROM skill_versions WHERE skill_id = ? AND version = ?",
                        (skill_id, version or s["current_version"]))
        s["version"] = v["version"]
        s["instructions"] = v["instructions"]
        s["inputs"] = loads(v["inputs_json"], [])
        s["tools"] = loads(v["tools_json"], [])
        s["completion_criteria"] = v["completion_criteria"]
        s["source"] = v["source"]
        s["versions"] = [r["version"] for r in self.db.all(
            "SELECT version FROM skill_versions WHERE skill_id = ? ORDER BY version", (skill_id,))]
        s["bot_ids"] = [r["bot_id"] for r in self.db.all("SELECT bot_id FROM bot_skills WHERE skill_id = ?", (skill_id,))]
        return s

    def list(self) -> List[Dict[str, Any]]:
        return [self._full(r["id"]) for r in self.db.all("SELECT id FROM skills ORDER BY name")]

    def by_slug(self, slug: str) -> Optional[Dict[str, Any]]:
        row = self.db.one("SELECT id FROM skills WHERE slug = ?", (slug,))
        return self._full(row["id"]) if row else None

    def create(self, data: Dict[str, Any], source: str = "manual") -> Dict[str, Any]:
        name = (data.get("name") or "").strip()
        if not name or not (data.get("instructions") or "").strip():
            raise SkillError("A skill needs a name and instructions.")
        slug = slugify(data.get("slug") or name)
        if not re.fullmatch(r"[a-z0-9][a-z0-9\-]*", slug):
            raise SkillError("Invalid slug.")
        if self.db.one("SELECT 1 FROM skills WHERE slug = ?", (slug,)):
            raise SkillError(f"Skill /{slug} already exists.")
        sid, now = new_id("skl"), now_iso()
        with self.db.tx():
            self.db.insert("skills", {"id": sid, "slug": slug, "name": name, "current_version": 1,
                                      "created_at": now, "updated_at": now})
            self._insert_version(sid, 1, data, source)
            for b in data.get("bot_ids") or []:
                self.db.execute("INSERT OR IGNORE INTO bot_skills(bot_id, skill_id) VALUES (?, ?)", (b, sid))
            self.core.audit("skill.create", actor_type="user", skill_id=sid, slug=slug)
        return self._full(sid)

    def _insert_version(self, sid: str, version: int, data: Dict[str, Any], source: str) -> None:
        self.db.insert("skill_versions", {
            "skill_id": sid, "version": version, "instructions": data["instructions"],
            "inputs_json": data.get("inputs") or [], "tools_json": data.get("tools") or [],
            "completion_criteria": data.get("completion_criteria") or "", "source": source, "created_at": now_iso()})

    def update(self, sid: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """Each edit creates a new immutable version."""
        cur = self._full(sid)
        if not cur:
            raise SkillError("Unknown skill.")
        merged = {k: data.get(k, cur[k]) for k in ("instructions", "inputs", "tools", "completion_criteria")}
        with self.db.tx():
            v = cur["version"] + 1
            self._insert_version(sid, v, merged, data.get("source", "manual"))
            self.db.update("skills", {"id": sid}, {"current_version": v, "name": data.get("name", cur["name"]),
                                                   "updated_at": now_iso()})
            if "bot_ids" in data:
                self.db.execute("DELETE FROM bot_skills WHERE skill_id = ?", (sid,))
                for b in data["bot_ids"] or []:
                    self.db.execute("INSERT OR IGNORE INTO bot_skills(bot_id, skill_id) VALUES (?, ?)", (b, sid))
        return self._full(sid)

    def rollback(self, sid: str, version: int) -> Dict[str, Any]:
        old = self._full(sid, version)
        if not old:
            raise SkillError("Unknown skill version.")
        return self.update(sid, {k: old[k] for k in ("instructions", "inputs", "tools", "completion_criteria")})

    def delete(self, sid: str) -> None:
        with self.db.tx():
            self.db.execute("DELETE FROM skills WHERE id = ?", (sid,))
            self.db.execute("DELETE FROM bot_skills WHERE skill_id = ?", (sid,))

    def export(self, sid: str) -> Dict[str, Any]:
        s = self._full(sid)
        if not s:
            raise SkillError("Unknown skill.")
        return {"format": FORMAT, "slug": s["slug"], "name": s["name"],
                **redact({k: s[k] for k in ("instructions", "inputs", "tools", "completion_criteria")})}

    def import_(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        if payload.get("format") != FORMAT:
            raise SkillError("Unsupported skill format.")
        return self.create({k: payload.get(k) for k in ("slug", "name", "instructions", "inputs", "tools",
                                                        "completion_criteria")}, source="import")

    # -- teach a task ----------------------------------------------------------
    def draft_from_run(self, run_id: str, name: str, consent: bool) -> Dict[str, Any]:
        """Turn the recorded tool steps of a completed run into an editable
        draft. Requires explicit consent; arguments are redacted and typed
        text is replaced by input placeholders."""
        if not consent:
            raise SkillError("Recording a demonstration requires explicit consent.")
        steps = self.db.all("SELECT * FROM run_steps WHERE run_id = ? AND kind = 'tool' ORDER BY seq", (run_id,))
        if not steps:
            raise SkillError("That run has no tool steps to learn from.")
        lines, tools, inputs = [], [], []
        for i, s in enumerate(steps, 1):
            args = redact(loads(s["input_json"], {}).get("arguments") or {})
            for k, v in list(args.items()):
                if k in ("text", "content", "value") and isinstance(v, str):
                    placeholder = f"input_{len(inputs) + 1}"
                    inputs.append({"name": placeholder, "description": f"value typed for {s['tool_name']}.{k}"})
                    args[k] = "{{" + placeholder + "}}"
            ok = s["status"] == "completed"
            lines.append(f"{i}. {s['tool_name']} {args} — expected: {'success' if ok else 'handle failure: ' + (s['error'] or '')}")
            if s["tool_name"] not in tools:
                tools.append(s["tool_name"])
        instructions = ("DRAFT generated from a demonstration. Review every step; pages change.\n"
                        "Steps:\n" + "\n".join(lines) +
                        "\n\nAfter each step, verify the result (read the page/file) before continuing. "
                        "If a step fails, stop and ask the user via user.ask.")
        return self.create({"name": name, "instructions": instructions, "inputs": inputs, "tools": tools,
                            "completion_criteria": "All steps verified; result reported to the user."},
                           source=f"teach:{run_id}")
