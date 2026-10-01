"""allow / ask / deny evaluation, enforced in code for every tool call.

Resolution:
1. Collect explicit rules matching the tool from: global rules, the bot's
   rules, and the active skill's permission boundary.
2. If any explicit rule matches, the MOST restrictive one wins
   (deny > ask > allow).
3. Otherwise the tool's default applies.
4. ``hard_ask`` tools (payments, transfers, publishing, permission changes)
   can be denied but never silently allowed.

System prompts never replace this check.
"""

from __future__ import annotations

import fnmatch
from typing import Any, Dict, Iterable, List, Optional

RANK = {"allow": 0, "ask": 1, "deny": 2}


def _matching(rules: Iterable[Dict[str, Any]], tool: str) -> List[str]:
    return [r["effect"] for r in rules if fnmatch.fnmatchcase(tool, r.get("tool") or r.get("tool_pattern") or "")]


def evaluate(tool: str, *, default: str, hard_ask: bool, global_rules: List[Dict[str, Any]],
             bot_rules: List[Dict[str, Any]], skill_rules: Optional[List[Dict[str, Any]]] = None) -> str:
    effects = _matching(global_rules, tool) + _matching(bot_rules, tool) + _matching(skill_rules or [], tool)
    decision = max(effects, key=RANK.__getitem__) if effects else default
    if hard_ask and decision == "allow":
        decision = "ask"
    return decision
