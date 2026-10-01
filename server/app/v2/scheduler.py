"""Routines: one-off times, cron schedules and signed webhook events.

* Wall-clock schedules are evaluated in the routine's IANA timezone
  (default Europe/Warsaw):
  - a time that does not exist (spring-forward gap) fires at the first valid
    minute after the gap;
  - a time that occurs twice (fall-back) fires once, on its first occurrence.
* Exactly one scheduler acts at a time (DB lease ``scheduler``), so several
  API/worker processes over one database never double-fire.
* Each firing is recorded in ``routine_runs`` with a UNIQUE dedupe key
  (scheduled instant, or webhook delivery id) — restarts and repeated
  webhooks cannot create duplicates.
* After downtime, ``catchup_policy`` decides: ``latest`` (run once for the
  most recent missed slot), ``skip`` (only future slots) or ``all`` (each
  missed slot, capped).
* The scheduler sleeps until the next due time; it does not poll every
  minute.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import re
from datetime import date, datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Set, Tuple
from zoneinfo import ZoneInfo

from app.v2.core import Core
from app.v2.db import loads
from app.v2.util import iso, iso_in, new_id, now_dt, now_iso, parse_iso, token, truncate

UTC = timezone.utc
DOW_NAMES = {"sun": 0, "mon": 1, "tue": 2, "wed": 3, "thu": 4, "fri": 5, "sat": 6}
MON_NAMES = {m: i + 1 for i, m in enumerate("jan feb mar apr may jun jul aug sep oct nov dec".split())}


class ScheduleError(ValueError):
    pass


# =============================================================== cron =====
def _field(spec: str, lo: int, hi: int, names: Dict[str, int]) -> Tuple[Set[int], bool]:
    values: Set[int] = set()
    restricted = spec.strip() != "*"
    for part in spec.lower().split(","):
        step, has_step = 1, "/" in part
        if has_step:
            part, s = part.split("/", 1)
            step = int(s)
            if step < 1:
                raise ScheduleError("Cron step must be >= 1.")
        if part in ("*", ""):
            a, b = lo, hi
        elif "-" in part:
            x, y = part.split("-", 1)
            a, b = int(names.get(x, x)), int(names.get(y, y))
        else:
            a = int(names.get(part, part))
            b = hi if has_step else a
        if a < lo or b > hi or a > b:
            raise ScheduleError(f"Cron value out of range: {spec}")
        values.update(range(a, b + 1, step))
    return values, restricted


class Cron:
    def __init__(self, expr: str):
        parts = expr.split()
        if len(parts) != 5:
            raise ScheduleError("Cron needs 5 fields: minute hour day-of-month month day-of-week.")
        try:
            self.minutes, _ = _field(parts[0], 0, 59, {})
            self.hours, _ = _field(parts[1], 0, 23, {})
            self.dom, self.dom_r = _field(parts[2], 1, 31, {})
            self.months, _ = _field(parts[3], 1, 12, MON_NAMES)
            dow, self.dow_r = _field(parts[4], 0, 7, DOW_NAMES)
        except ValueError as exc:
            raise ScheduleError(f"Invalid cron expression: {exc}") from exc
        self.dow = {d % 7 for d in dow}
        self.expr = expr

    def day_matches(self, d: date) -> bool:
        if d.month not in self.months:
            return False
        cron_dow = (d.weekday() + 1) % 7  # Monday=1 ... Sunday=0
        dom_ok, dow_ok = d.day in self.dom, cron_dow in self.dow
        if self.dom_r and self.dow_r:
            return dom_ok or dow_ok  # classic cron OR semantics
        return dom_ok and dow_ok

    def next_after(self, after: datetime, tz: ZoneInfo) -> datetime:
        """Next fire instant (UTC) strictly after ``after``."""
        after = after.astimezone(UTC)
        local_start = after.astimezone(tz)
        d = local_start.date()
        for _ in range(366 * 5):
            if self.day_matches(d):
                for h in sorted(self.hours):
                    for m in sorted(self.minutes):
                        fire = resolve_local(datetime(d.year, d.month, d.day, h, m), tz)
                        if fire > after:
                            return fire
            d += timedelta(days=1)
        raise ScheduleError("Cron expression never fires.")


def resolve_local(naive: datetime, tz: ZoneInfo) -> datetime:
    """Map a wall-clock time to a UTC instant with explicit DST rules."""
    first = naive.replace(tzinfo=tz, fold=0)
    utc = first.astimezone(UTC)
    if utc.astimezone(tz).replace(tzinfo=None) == naive:
        return utc  # exists (if ambiguous, fold=0 = first occurrence)
    probe = naive
    for _ in range(24 * 60):  # nonexistent: first valid minute after the gap
        probe += timedelta(minutes=1)
        cand = probe.replace(tzinfo=tz, fold=0)
        if cand.astimezone(UTC).astimezone(tz).replace(tzinfo=None) == probe:
            return cand.astimezone(UTC)
    raise ScheduleError("Could not resolve local time.")  # pragma: no cover


# ================================================= natural language =======
_PL_DOW = {"poniedziałek": 1, "poniedzialek": 1, "wtorek": 2, "środę": 3, "środa": 3, "srode": 3, "sroda": 3,
           "czwartek": 4, "piątek": 5, "piatek": 5, "sobotę": 6, "sobota": 6, "sobote": 6, "niedzielę": 0,
           "niedziela": 0, "niedziele": 0}
_EN_DOW = {"monday": 1, "tuesday": 2, "wednesday": 3, "thursday": 4, "friday": 5, "saturday": 6, "sunday": 0}


def _time(text: str) -> Optional[Tuple[int, int]]:
    m = re.search(r"\b(?:o|at|@)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b", text) or \
        re.search(r"\b(\d{1,2}):(\d{2})\s*(am|pm)?\b", text)
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2) or 0)
    if m.group(3) == "pm" and h < 12:
        h += 12
    if m.group(3) == "am" and h == 12:
        h = 0
    if h > 23 or mi > 59:
        raise ScheduleError("Invalid time of day.")
    return h, mi


def parse_schedule(text: str, tz_name: str) -> Dict[str, Any]:
    """Natural language (PL/EN, common patterns) or cron -> structured config."""
    raw = (text or "").strip()
    t = raw.lower()
    tz = ZoneInfo(tz_name)
    if re.fullmatch(r"[\d\*/,\-a-z]+(\s+[\d\*/,\-a-z]+){4}", t) and not re.search(r"[ąęśćżźół]", t):
        Cron(t)
        return {"kind": "cron", "cron": t, "timezone": tz_name, "source": raw}
    m = re.search(r"(\d{4}-\d{2}-\d{2})[ t](\d{1,2}):(\d{2})", t)
    if m:
        y, mo, d = map(int, m.group(1).split("-"))
        at = resolve_local(datetime(y, mo, d, int(m.group(2)), int(m.group(3))), tz)
        return {"kind": "once", "at": iso(at), "timezone": tz_name, "source": raw}
    if re.search(r"\b(jutro|tomorrow|dziś|dzis|dzisiaj|today)\b", t):
        hm = _time(t) or (9, 0)
        base = now_dt().astimezone(tz).date() + timedelta(days=1 if re.search(r"jutro|tomorrow", t) else 0)
        at = resolve_local(datetime(base.year, base.month, base.day, *hm), tz)
        return {"kind": "once", "at": iso(at), "timezone": tz_name, "source": raw}
    m = re.search(r"\b(?:co|every)\s+(\d{1,2})\s*(?:min|minut|minutes?)\b", t)
    if m:
        n = int(m.group(1))
        if not 1 <= n <= 59:
            raise ScheduleError("Minute interval must be 1-59.")
        return {"kind": "cron", "cron": f"*/{n} * * * *", "timezone": tz_name, "source": raw}
    m = re.search(r"\b(?:co|every)\s+(\d{1,2})\s*(?:godz|godziny|godzin|hours?)\b", t)
    if m:
        return {"kind": "cron", "cron": f"0 */{int(m.group(1))} * * *", "timezone": tz_name, "source": raw}
    if re.search(r"\b(co godzinę|co godzine|every hour|hourly)\b", t):
        return {"kind": "cron", "cron": "0 * * * *", "timezone": tz_name, "source": raw}
    hm = _time(t)
    h, mi = hm if hm else (9, 0)
    if re.search(r"dni robocze|w dni powszednie|weekdays?|każdy dzień roboczy", t):
        return {"kind": "cron", "cron": f"{mi} {h} * * 1-5", "timezone": tz_name, "source": raw}
    if re.search(r"weekend", t):
        return {"kind": "cron", "cron": f"{mi} {h} * * 0,6", "timezone": tz_name, "source": raw}
    days = [v for k, v in {**_PL_DOW, **_EN_DOW}.items() if re.search(rf"\b{k}", t)]
    if days:
        return {"kind": "cron", "cron": f"{mi} {h} * * {','.join(str(d) for d in sorted(set(days)))}",
                "timezone": tz_name, "source": raw}
    m = re.search(r"(\d{1,2})\.?\s*(?:dnia|day)?\s*(?:każdego|kazdego)?\s*(?:miesiąca|miesiaca|of (?:the|each|every) month)", t) \
        or re.search(r"monthly on (?:the )?(\d{1,2})", t)
    if m or re.search(r"pierwszego dnia miesiąca|pierwszego dnia miesiaca|first day of (?:the|each) month", t):
        dom = int(m.group(1)) if m else 1
        return {"kind": "cron", "cron": f"{mi} {h} {dom} * *", "timezone": tz_name, "source": raw}
    if re.search(r"codziennie|każdego dnia|kazdego dnia|every day|daily|co dzień|co dzien", t) or hm:
        return {"kind": "cron", "cron": f"{mi} {h} * * *", "timezone": tz_name, "source": raw}
    raise ScheduleError("Could not understand the schedule. Use e.g. 'codziennie o 8:00', "
                        "'weekdays at 7:30', 'every 15 minutes' or a cron expression.")


def next_runs(schedule: Dict[str, Any], tz_name: str, count: int = 5,
              after: Optional[datetime] = None) -> List[str]:
    tz = ZoneInfo(tz_name)
    after = after or now_dt()
    if schedule.get("kind") == "once":
        at = parse_iso(schedule["at"])
        return [iso(at)] if at > after else []
    if schedule.get("kind") != "cron":
        return []
    cron, out, cur = Cron(schedule["cron"]), [], after
    for _ in range(count):
        cur = cron.next_after(cur, tz)
        out.append(iso(cur))
    return out


def describe_local(instants: List[str], tz_name: str) -> List[str]:
    tz = ZoneInfo(tz_name)
    return [parse_iso(i).astimezone(tz).strftime("%a %Y-%m-%d %H:%M %Z") for i in instants]


# ================================================================ service ==
class RoutineService:
    def __init__(self, core: Core, tasks):
        self.core, self.db, self.tasks = core, core.db, tasks

    def _row(self, r: Dict[str, Any]) -> Dict[str, Any]:
        r = dict(r)
        r["schedule"] = loads(r.pop("schedule_json"), {})
        r["enabled"] = bool(r["enabled"])
        r["has_webhook"] = bool(r.pop("webhook_secret_id"))
        if r["kind"] != "event":
            r["preview"] = describe_local(next_runs(r["schedule"], r["timezone"], 3), r["timezone"]) if r["enabled"] else []
        return r

    def get(self, rid: str) -> Optional[Dict[str, Any]]:
        row = self.db.one("SELECT * FROM routines WHERE id = ?", (rid,))
        return self._row(row) if row else None

    def list(self, bot_id: Optional[str] = None) -> List[Dict[str, Any]]:
        if bot_id:
            rows = self.db.all("SELECT * FROM routines WHERE bot_id = ? ORDER BY created_at", (bot_id,))
        else:
            rows = self.db.all("SELECT * FROM routines ORDER BY created_at")
        return [self._row(r) for r in rows]

    def create(self, *, bot_id: str, name: str, prompt: str, schedule: Optional[str] = None,
               kind: Optional[str] = None, timezone_name: Optional[str] = None, conversation_id: Optional[str] = None,
               overlap_policy: str = "skip", catchup_policy: str = "latest", enabled: bool = True) -> Dict[str, Any]:
        tz_name = timezone_name or self.core.settings.default_timezone
        ZoneInfo(tz_name)
        if overlap_policy not in ("skip", "queue") or catchup_policy not in ("latest", "skip", "all"):
            raise ScheduleError("Invalid overlap/catchup policy.")
        secret_plain = None
        if kind == "event":
            sched = {"kind": "event"}
        else:
            sched = parse_schedule(schedule or "", tz_name)
        rid, now = new_id("rtn"), now_iso()
        secret_id = None
        if sched["kind"] == "event":
            secret_plain = token(24)
            secret_id = self.core.secrets.put(f"webhook:{rid}", secret_plain, "webhook")
        nxt = next_runs(sched, tz_name, 1)
        with self.db.tx():
            self.db.insert("routines", {
                "id": rid, "bot_id": bot_id, "name": name, "kind": sched["kind"], "schedule_json": sched,
                "timezone": tz_name, "prompt": prompt, "conversation_id": conversation_id, "enabled": enabled,
                "overlap_policy": overlap_policy, "catchup_policy": catchup_policy, "webhook_secret_id": secret_id,
                "next_run_at": nxt[0] if (nxt and enabled) else None, "created_at": now, "updated_at": now,
            })
            self.core.emit("routine.created", bot_id=bot_id, routine_id=rid)
            self.core.audit("routine.create", actor_type="user", routine_id=rid, schedule=sched)
        self.core.bus.wake_workers()
        out = self.get(rid)
        if secret_plain:
            out["webhook_secret"] = secret_plain  # shown once
            out["webhook_path"] = f"/api/v2/hooks/{rid}"
        return out

    def update(self, rid: str, data: Dict[str, Any]) -> Dict[str, Any]:
        r = self.get(rid)
        if not r:
            raise ScheduleError("Unknown routine.")
        values: Dict[str, Any] = {"updated_at": now_iso()}
        tz_name = data.get("timezone") or r["timezone"]
        ZoneInfo(tz_name)
        values["timezone"] = tz_name
        sched = r["schedule"]
        if data.get("schedule") and r["kind"] != "event":
            sched = parse_schedule(data["schedule"], tz_name)
            values["schedule_json"] = sched
            values["kind"] = sched["kind"]
        for k in ("name", "prompt", "overlap_policy", "catchup_policy", "conversation_id"):
            if k in data:
                values[k] = data[k]
        enabled = data.get("enabled", r["enabled"])
        values["enabled"] = bool(enabled)
        nxt = next_runs(sched, tz_name, 1) if enabled else []
        values["next_run_at"] = nxt[0] if nxt else None
        with self.db.tx():
            self.db.update("routines", {"id": rid}, values)
            self.core.emit("routine.updated", bot_id=r["bot_id"], routine_id=rid, enabled=bool(enabled))
            self.core.audit("routine.update", actor_type="user", routine_id=rid, fields=sorted(data))
        self.core.bus.wake_workers()
        return self.get(rid)

    def delete(self, rid: str) -> None:
        sid = self.db.scalar("SELECT webhook_secret_id FROM routines WHERE id = ?", (rid,))
        with self.db.tx():
            self.db.execute("DELETE FROM routines WHERE id = ?", (rid,))
            self.core.audit("routine.delete", actor_type="user", routine_id=rid)
        self.core.secrets.delete(sid)

    def history(self, rid: str) -> List[Dict[str, Any]]:
        rows = self.db.all(
            "SELECT rr.*, t.status AS task_status, t.result_text, t.error AS task_error, t.completed_at, t.created_at AS task_created "
            "FROM routine_runs rr LEFT JOIN tasks t ON t.id = rr.task_id WHERE rr.routine_id = ? "
            "ORDER BY rr.created_at DESC LIMIT 100", (rid,))
        for r in rows:
            r["input"] = loads(r.pop("input_json"), {})
            r["cost"] = self.db.scalar(
                "SELECT COALESCE(SUM(COALESCE(cost_confirmed, cost_estimated)), 0) FROM usage_entries u "
                "JOIN runs ru ON ru.id = u.run_id WHERE ru.task_id = ?", (r["task_id"],)) if r["task_id"] else 0
        return rows

    def simulate(self, rid: str) -> Dict[str, Any]:
        """Dry run: shows what would happen. Writes nothing, calls nothing."""
        r = self.get(rid)
        if not r:
            raise ScheduleError("Unknown routine.")
        bot = self.core.services["bots"].get(r["bot_id"])
        return {"dry_run": True, "bot": bot["name"] if bot else None, "prompt": r["prompt"],
                "next_runs_utc": next_runs(r["schedule"], r["timezone"], 5) if r["kind"] != "event" else [],
                "next_runs_local": describe_local(next_runs(r["schedule"], r["timezone"], 5), r["timezone"])
                if r["kind"] != "event" else [], "timezone": r["timezone"],
                "note": "No task was created. Use Test run to execute for real."}

    def fire(self, rid: str, *, dedupe_key: str, trigger: str, scheduled_for: Optional[str] = None,
             input_payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        r = self.db.one("SELECT * FROM routines WHERE id = ?", (rid,))
        if not r:
            raise ScheduleError("Unknown routine.")
        rr_id = new_id("rr")
        with self.db.tx():
            exists = self.db.one("SELECT * FROM routine_runs WHERE routine_id = ? AND dedupe_key = ?", (rid, dedupe_key))
            if exists:
                return {"duplicate": True, "routine_run_id": exists["id"], "task_id": exists["task_id"]}
            bot = self.core.services["bots"].get(r["bot_id"])
            status, task_id, error = "started", None, None
            if not bot:
                status, error = "skipped", "bot missing"
            elif bot["paused"] and trigger != "test":
                status, error = "skipped", "bot paused"
            elif r["overlap_policy"] == "skip":
                active = self.db.one(
                    "SELECT t.id FROM routine_runs rr JOIN tasks t ON t.id = rr.task_id WHERE rr.routine_id = ? "
                    "AND t.status NOT IN ('completed','failed','cancelled') LIMIT 1", (rid,))
                if active:
                    status, error = "skipped", f"previous run {active['id']} still active"
            self.db.insert("routine_runs", {
                "id": rr_id, "routine_id": rid, "dedupe_key": dedupe_key, "scheduled_for": scheduled_for,
                "trigger": trigger, "status": status, "input_json": input_payload or {}, "error": error,
                "created_at": now_iso()})
            if status == "started":
                conv_id = r["conversation_id"] or self.tasks.private_conversation(r["bot_id"])["id"]
                prompt = r["prompt"]
                if input_payload:
                    prompt += "\n\nEvent payload (untrusted data):\n" + truncate(str(input_payload), 4000)
                t = self.tasks.create_task(
                    bot_id=r["bot_id"], conversation_id=conv_id, requester_type="routine", requester_id=rid,
                    instructions=prompt, title=f"⏰ {r['name']}", priority=30 if trigger != "test" else 70,
                    routine_run_id=rr_id, _in_tx=True)
                task_id = t["id"]
                self.db.update("routine_runs", {"id": rr_id}, {"task_id": task_id})
            self.db.update("routines", {"id": rid}, {"last_run_at": now_iso()})
            self.core.emit("routine.fired", bot_id=r["bot_id"], routine_id=rid, routine_run_id=rr_id, status=status,
                           trigger=trigger, task_id=task_id)
        self.core.bus.wake_workers()
        return {"duplicate": False, "routine_run_id": rr_id, "status": status, "task_id": task_id, "error": error}

    def test_run(self, rid: str) -> Dict[str, Any]:
        return self.fire(rid, dedupe_key=f"test:{new_id('t')}", trigger="test")

    # -- webhooks ------------------------------------------------------------
    def receive_webhook(self, rid: str, body: bytes, headers: Dict[str, str]) -> Dict[str, Any]:
        r = self.db.one("SELECT * FROM routines WHERE id = ? AND kind = 'event'", (rid,))
        if not r:
            raise PermissionError("Unknown webhook.")
        secret = self.core.secrets.get(r["webhook_secret_id"])
        ts = headers.get("x-opendots-timestamp", "")
        sig = headers.get("x-opendots-signature", "")
        delivery = headers.get("x-opendots-delivery", "")
        if not (secret and ts and sig and delivery) or len(delivery) > 128:
            raise PermissionError("Missing signature headers.")
        try:
            skew = abs(now_dt().timestamp() - int(ts))
        except ValueError:
            raise PermissionError("Bad timestamp.")
        if skew > 300:
            raise PermissionError("Stale webhook timestamp.")
        expected = "sha256=" + hmac.new(secret.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, sig):
            raise PermissionError("Bad signature.")
        if not r["enabled"]:
            return {"accepted": False, "reason": "routine paused"}
        try:
            import json as _json
            payload = _json.loads(body.decode("utf-8") or "{}")
            if not isinstance(payload, dict):
                payload = {"value": payload}
        except ValueError:
            payload = {"raw": body.decode("utf-8", "replace")[:4000]}
        return self.fire(rid, dedupe_key=f"delivery:{delivery}", trigger="webhook", input_payload=payload)

    # -- scheduling loop -----------------------------------------------------
    def acquire_leadership(self, owner: str, ttl: float) -> bool:
        now = now_iso()
        with self.db.tx():
            row = self.db.one("SELECT * FROM leases WHERE name = 'scheduler'")
            if row and row["owner"] != owner and row["expires_at"] > now:
                return False
            self.db.execute(
                "INSERT INTO leases(name, owner, expires_at, version) VALUES ('scheduler', ?, ?, 1) "
                "ON CONFLICT(name) DO UPDATE SET owner = excluded.owner, expires_at = excluded.expires_at, "
                "version = leases.version + 1", (owner, iso_in(ttl)))
        return True

    def tick(self) -> int:
        """Fire everything due. Returns number of fired/skipped records."""
        now = now_dt()
        due = self.db.all("SELECT * FROM routines WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?",
                          (iso(now),))
        count = 0
        for r in due:
            sched = loads(r["schedule_json"], {})
            tz = ZoneInfo(r["timezone"])
            slot = parse_iso(r["next_run_at"])
            if sched.get("kind") == "once":
                slots = [slot]
            else:
                cron = Cron(sched["cron"])
                slots = [slot]
                while len(slots) < 1000:
                    nxt = cron.next_after(slots[-1], tz)
                    if nxt > now:
                        break
                    slots.append(nxt)
            grace = timedelta(minutes=5)
            if r["catchup_policy"] == "latest":
                fire_slots, skipped = slots[-1:], slots[:-1]
            elif r["catchup_policy"] == "all":
                fire_slots, skipped = slots[-10:], slots[:-10]
            else:  # skip: only fire if we are (nearly) on time
                fire_slots = [s for s in slots[-1:] if now - s <= grace]
                skipped = [s for s in slots if s not in fire_slots]
            for s in skipped:
                with self.db.tx():
                    self.db.execute(
                        "INSERT OR IGNORE INTO routine_runs(id, routine_id, dedupe_key, scheduled_for, trigger, status, "
                        "error, created_at) VALUES (?, ?, ?, ?, 'schedule', 'missed', 'scheduler was not running', ?)",
                        (new_id("rr"), r["id"], f"slot:{iso(s)}", iso(s), now_iso()))
            for s in fire_slots:
                self.fire(r["id"], dedupe_key=f"slot:{iso(s)}", trigger="schedule", scheduled_for=iso(s))
                count += 1
            nxt = None if sched.get("kind") == "once" else iso(Cron(sched["cron"]).next_after(now, tz))
            with self.db.tx():
                self.db.update("routines", {"id": r["id"]},
                               {"next_run_at": nxt, "enabled": r["enabled"] if nxt else 0, "updated_at": now_iso()})
        return count

    def seconds_until_next(self, max_sleep: float) -> float:
        nxt = self.db.scalar("SELECT MIN(next_run_at) FROM routines WHERE enabled = 1 AND next_run_at IS NOT NULL")
        if not nxt:
            return max_sleep
        return max(0.05, min(max_sleep, (parse_iso(nxt) - now_dt()).total_seconds()))

    async def run_forever(self, stop: asyncio.Event, owner: str) -> None:
        while not stop.is_set():
            ttl = self.core.settings.scheduler_max_sleep_s + 30
            if self.acquire_leadership(owner, ttl):
                self.tick()
                self.core.services["approvals"].sweep_expired()
                timeout = self.seconds_until_next(self.core.settings.scheduler_max_sleep_s)
                exp = self.core.services["approvals"].next_expiry()
                if exp:
                    timeout = min(timeout, max(0.05, (parse_iso(exp) - now_dt()).total_seconds()))
            else:
                timeout = 15.0
            try:
                await asyncio.wait_for(self.core.bus.wait(timeout), timeout + 1)
            except asyncio.TimeoutError:
                pass
