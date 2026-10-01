package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routines (server/app/v2/scheduler.py): one-off and recurring wall-clock schedules in an
 * IANA zone (default Europe/Warsaw). A time inside the spring-forward gap fires at the
 * first valid minute after it; a repeated fall-back time fires once (first occurrence).
 * routine_runs has a UNIQUE dedupe key per slot, so restarts never double-fire.
 * Webhook events need a public address, which a phone does not have.
 */
public final class Routines {
    private final Backend b;
    private final Db db;

    Routines(Backend b) { this.b = b; this.db = b.core.db; }

    // ===================================================================== cron
    static final Map<String, Integer> DOW = new HashMap<String, Integer>();
    static final Map<String, Integer> MON = new HashMap<String, Integer>();
    static {
        String[] d = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
        for (int i = 0; i < d.length; i++) DOW.put(d[i], i);
        String[] m = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < m.length; i++) MON.put(m[i], i + 1);
    }

    static int num(String s, Map<String, Integer> names) {
        Integer v = names.get(s);
        return v != null ? v : Integer.parseInt(s);
    }

    static TreeSet<Integer> field(String spec, int lo, int hi, Map<String, Integer> names) {
        TreeSet<Integer> out = new TreeSet<Integer>();
        for (String part : spec.toLowerCase(Locale.ROOT).split(",")) {
            int step = 1;
            boolean hasStep = part.contains("/");
            if (hasStep) {
                String[] p = part.split("/", 2);
                part = p[0];
                step = Integer.parseInt(p[1]);
                if (step < 1) throw new ApiError(422, "Cron step must be >= 1.");
            }
            int a, z;
            if (part.equals("*") || part.isEmpty()) { a = lo; z = hi; }
            else if (part.contains("-")) { String[] p = part.split("-", 2); a = num(p[0], names); z = num(p[1], names); }
            else { a = num(part, names); z = hasStep ? hi : a; }
            if (a < lo || z > hi || a > z) throw new ApiError(422, "Cron value out of range: " + spec);
            for (int i = a; i <= z; i += step) out.add(i);
        }
        return out;
    }

    public static final class Cron {
        final TreeSet<Integer> minutes, hours, dom, months, dow;
        final boolean domR, dowR;

        public Cron(String expr) {
            String[] p = expr.trim().split("\\s+");
            if (p.length != 5) throw new ApiError(422, "Cron needs 5 fields: minute hour day-of-month month day-of-week.");
            try {
                minutes = field(p[0], 0, 59, new HashMap<String, Integer>());
                hours = field(p[1], 0, 23, new HashMap<String, Integer>());
                dom = field(p[2], 1, 31, new HashMap<String, Integer>());
                months = field(p[3], 1, 12, MON);
                TreeSet<Integer> d = field(p[4], 0, 7, DOW);
                dow = new TreeSet<Integer>();
                for (int x : d) dow.add(x % 7);
            } catch (NumberFormatException e) {
                throw new ApiError(422, "Invalid cron expression: " + expr);
            }
            domR = !p[2].trim().equals("*");
            dowR = !p[4].trim().equals("*");
        }

        boolean dayMatches(LocalDate d) {
            if (!months.contains(d.getMonthValue())) return false;
            int cronDow = d.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : d.getDayOfWeek().getValue();
            boolean domOk = dom.contains(d.getDayOfMonth()), dowOk = dow.contains(cronDow);
            return domR && dowR ? domOk || dowOk : domOk && dowOk;
        }

        /** Next fire instant strictly after `after`. */
        public Instant nextAfter(Instant after, ZoneId tz) {
            LocalDate d = after.atZone(tz).toLocalDate();
            for (int i = 0; i < 366 * 5; i++) {
                if (dayMatches(d)) for (int h : hours) for (int m : minutes) {
                    Instant fire = resolveLocal(d.atTime(h, m), tz);
                    if (fire.isAfter(after)) return fire;
                }
                d = d.plusDays(1);
            }
            throw new ApiError(422, "Cron expression never fires.");
        }
    }

    /** Wall-clock → instant with explicit DST rules. */
    public static Instant resolveLocal(LocalDateTime naive, ZoneId tz) {
        ZonedDateTime z = naive.atZone(tz).withEarlierOffsetAtOverlap();
        if (z.toLocalDateTime().equals(naive)) return z.toInstant();
        LocalDateTime probe = naive;
        for (int i = 0; i < 24 * 60; i++) {
            probe = probe.plusMinutes(1);
            ZonedDateTime c = probe.atZone(tz);
            if (c.toLocalDateTime().equals(probe)) return c.toInstant();
        }
        throw new ApiError(422, "Could not resolve local time.");
    }

    // ========================================================= natural language
    static final String[][] PL_EN_DOW = {
        {"poniedziałek", "1"}, {"poniedzialek", "1"}, {"wtorek", "2"}, {"środę", "3"}, {"środa", "3"}, {"srode", "3"}, {"sroda", "3"},
        {"czwartek", "4"}, {"piątek", "5"}, {"piatek", "5"}, {"sobotę", "6"}, {"sobota", "6"}, {"sobote", "6"},
        {"niedzielę", "0"}, {"niedziela", "0"}, {"niedziele", "0"},
        {"monday", "1"}, {"tuesday", "2"}, {"wednesday", "3"}, {"thursday", "4"}, {"friday", "5"}, {"saturday", "6"}, {"sunday", "0"}};

    static int[] time(String t) {
        Matcher m = Pattern.compile("(?:^|\\s)(?:o|at|@)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").matcher(t);
        if (!m.find()) {
            m = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\s*(am|pm)?\\b").matcher(t);
            if (!m.find()) return null;
        }
        int h = Integer.parseInt(m.group(1)), mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if ("pm".equals(m.group(3)) && h < 12) h += 12;
        if ("am".equals(m.group(3)) && h == 12) h = 0;
        if (h > 23 || mi > 59) throw new ApiError(422, "Invalid time of day.");
        return new int[]{h, mi};
    }

    static boolean has(String t, String re) { return Pattern.compile(re).matcher(t).find(); }

    static JSONObject cron(String c, String tz, String raw) {
        new Cron(c);
        return J.obj("kind", "cron", "cron", c, "timezone", tz, "source", raw);
    }

    /** Natural language (PL/EN common patterns) or cron → structured schedule. */
    public static JSONObject parse(String text, String tzName) {
        String raw = text == null ? "" : text.trim();
        String t = raw.toLowerCase(Locale.ROOT);
        ZoneId tz = zone(tzName);
        if (t.matches("[\\d*/,\\-a-z]+(\\s+[\\d*/,\\-a-z]+){4}")) return cron(t, tzName, raw);
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})[ t](\\d{1,2}):(\\d{2})").matcher(t);
        if (m.find()) {
            Instant at = resolveLocal(LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))), tz);
            return J.obj("kind", "once", "at", J.iso(at.toEpochMilli()), "timezone", tzName, "source", raw);
        }
        if (has(t, "\\b(jutro|tomorrow|dziś|dzis|dzisiaj|today)\\b") || has(t, "(^|\\s)(jutro|dziś|dzisiaj)")) {
            int[] hm = time(t);
            if (hm == null) hm = new int[]{9, 0};
            LocalDate base = Instant.ofEpochMilli(J.now()).atZone(tz).toLocalDate().plusDays(has(t, "jutro|tomorrow") ? 1 : 0);
            Instant at = resolveLocal(base.atTime(hm[0], hm[1]), tz);
            return J.obj("kind", "once", "at", J.iso(at.toEpochMilli()), "timezone", tzName, "source", raw);
        }
        m = Pattern.compile("\\b(?:co|every)\\s+(\\d{1,2})\\s*(?:min|minut|minutes?)").matcher(t);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > 59) throw new ApiError(422, "Minute interval must be 1-59.");
            return cron("*/" + n + " * * * *", tzName, raw);
        }
        m = Pattern.compile("\\b(?:co|every)\\s+(\\d{1,2})\\s*(?:godz|godziny|godzin|hours?)").matcher(t);
        if (m.find()) return cron("0 */" + Integer.parseInt(m.group(1)) + " * * *", tzName, raw);
        if (has(t, "co godzinę|co godzine|every hour|hourly")) return cron("0 * * * *", tzName, raw);
        int[] hm = time(t);
        int h = hm == null ? 9 : hm[0], mi = hm == null ? 0 : hm[1];
        if (has(t, "dni robocze|w dni powszednie|weekdays?|każdy dzień roboczy|every weekday")) return cron(mi + " " + h + " * * 1-5", tzName, raw);
        if (has(t, "weekend")) return cron(mi + " " + h + " * * 0,6", tzName, raw);
        TreeSet<Integer> days = new TreeSet<Integer>();
        for (String[] d : PL_EN_DOW) if (t.contains(d[0])) days.add(Integer.parseInt(d[1]));
        if (!days.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int d : days) sb.append(sb.length() > 0 ? "," : "").append(d);
            return cron(mi + " " + h + " * * " + sb, tzName, raw);
        }
        m = Pattern.compile("(\\d{1,2})\\.?\\s*(?:dnia|day)?\\s*(?:każdego|kazdego)?\\s*(?:miesiąca|miesiaca|of (?:the|each|every) month)").matcher(t);
        Matcher m2 = Pattern.compile("monthly on (?:the )?(\\d{1,2})").matcher(t);
        boolean mf = m.find(), m2f = m2.find();
        if (mf || m2f || has(t, "pierwszego dnia miesiąca|pierwszego dnia miesiaca|first day of (?:the|each) month")) {
            int dom = mf ? Integer.parseInt(m.group(1)) : m2f ? Integer.parseInt(m2.group(1)) : 1;
            return cron(mi + " " + h + " " + dom + " * *", tzName, raw);
        }
        if (has(t, "codziennie|każdego dnia|kazdego dnia|every day|daily|co dzień|co dzien") || hm != null)
            return cron(mi + " " + h + " * * *", tzName, raw);
        throw new ApiError(422, "Could not understand the schedule. Use e.g. 'codziennie o 8:00', 'every weekday at 8:00 AM', 'every 15 minutes' or a cron expression.");
    }

    static ZoneId zone(String name) {
        try {
            return ZoneId.of(name);
        } catch (Exception e) {
            throw new ApiError(422, "Unknown timezone: " + name);
        }
    }

    public static List<String> nextRuns(JSONObject sched, String tzName, int count, Instant after) {
        List<String> out = new ArrayList<String>();
        if (after == null) after = Instant.ofEpochMilli(J.now());
        if ("once".equals(sched.optString("kind"))) {
            long at = J.parseIso(sched.optString("at"));
            if (at > after.toEpochMilli()) out.add(J.iso(at));
            return out;
        }
        if (!"cron".equals(sched.optString("kind"))) return out;
        Cron c = new Cron(sched.optString("cron"));
        ZoneId tz = zone(tzName);
        Instant cur = after;
        for (int i = 0; i < count; i++) {
            cur = c.nextAfter(cur, tz);
            out.add(J.iso(cur.toEpochMilli()));
        }
        return out;
    }

    public static List<String> describeLocal(List<String> instants, String tzName) {
        ZoneId tz = zone(tzName);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm zzz", Locale.ENGLISH);
        List<String> out = new ArrayList<String>();
        for (String i : instants) out.add(Instant.ofEpochMilli(J.parseIso(i)).atZone(tz).format(f));
        return out;
    }

    // ================================================================== service
    JSONObject row(JSONObject r) {
        JSONObject s = J.parse(r.optString("schedule_json"));
        J.put(r, "schedule", s);
        r.remove("schedule_json");
        J.put(r, "enabled", J.bool(r, "enabled"));
        J.put(r, "has_webhook", false);
        J.put(r, "preview", r.optBoolean("enabled") ? J.arr(describeLocal(nextRuns(s, r.optString("timezone"), 3, null), r.optString("timezone"))) : new JSONArray());
        return r;
    }

    public JSONObject get(String id) {
        JSONObject r = db.one("SELECT * FROM routines WHERE id = ?", id);
        return r == null ? null : row(r);
    }

    public List<JSONObject> list(String botId) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject r : botId == null ? db.all("SELECT * FROM routines ORDER BY created_at") : db.all("SELECT * FROM routines WHERE bot_id = ? ORDER BY created_at", botId))
            out.add(row(r));
        return out;
    }

    public JSONObject create(final String botId, final String name, final String prompt, String schedule, String kind, String tzName,
                             final String cid, final String overlap, final String catchup, final boolean enabled) {
        b.bots.require(botId);
        if ("event".equals(kind)) throw new ApiError(422, "Event (webhook) routines need a public server address; on the phone use a schedule instead.");
        final String tz = tzName == null || tzName.isEmpty() ? b.core.settings.timezone : tzName;
        zone(tz);
        if (!("skip".equals(overlap) || "queue".equals(overlap)) || !("latest".equals(catchup) || "skip".equals(catchup) || "all".equals(catchup)))
            throw new ApiError(422, "Invalid overlap/catchup policy.");
        if (prompt == null || prompt.trim().isEmpty()) throw new ApiError(422, "A routine needs instructions.");
        final JSONObject sched = parse(schedule, tz);
        final List<String> nxt = nextRuns(sched, tz, 1, null);
        final String rid = J.id("rtn");
        db.tx(new Runnable() {
            @Override public void run() {
                db.insert("routines", J.obj("id", rid, "bot_id", botId, "name", name == null || name.isEmpty() ? "Routine" : name,
                        "kind", sched.optString("kind"), "schedule_json", sched.toString(), "timezone", tz, "prompt", prompt,
                        "conversation_id", cid, "enabled", enabled, "overlap_policy", overlap, "catchup_policy", catchup,
                        "next_run_at", enabled && !nxt.isEmpty() ? nxt.get(0) : null, "created_at", J.nowIso(), "updated_at", J.nowIso()));
                b.core.emit("routine.created", null, null, null, botId, J.obj("routine_id", rid));
                b.core.audit("routine.create", "user", null, null, null, null, null, null, J.obj("routine_id", rid, "schedule", sched));
            }
        });
        b.scheduleAlarm();
        return get(rid);
    }

    public JSONObject update(final String rid, final JSONObject d) {
        final JSONObject r = get(rid);
        if (r == null) throw new ApiError(404, "Unknown routine.");
        final String tz = J.str(d, "timezone", r.optString("timezone"));
        zone(tz);
        JSONObject sched = r.optJSONObject("schedule");
        if (!J.str(d, "schedule", "").isEmpty()) sched = parse(d.optString("schedule"), tz);
        final JSONObject s = sched;
        final boolean enabled = d.has("enabled") ? J.bool(d, "enabled") : r.optBoolean("enabled");
        final List<String> nxt = enabled ? nextRuns(s, tz, 1, null) : new ArrayList<String>();
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("UPDATE routines SET timezone = ?, schedule_json = ?, kind = ?, enabled = ?, next_run_at = ?, updated_at = ? WHERE id = ?",
                        tz, s.toString(), s.optString("kind"), enabled, nxt.isEmpty() ? null : nxt.get(0), J.nowIso(), rid);
                for (String k : new String[]{"name", "prompt", "overlap_policy", "catchup_policy", "conversation_id"})
                    if (d.has(k)) db.exec("UPDATE routines SET " + k + " = ? WHERE id = ?", d.isNull(k) ? null : d.optString(k), rid);
                b.core.emit("routine.updated", null, null, null, r.optString("bot_id"), J.obj("routine_id", rid, "enabled", enabled));
                b.core.audit("routine.update", "user", null, null, null, null, null, null, J.obj("routine_id", rid));
            }
        });
        b.scheduleAlarm();
        return get(rid);
    }

    public void delete(final String rid) {
        db.tx(new Runnable() {
            @Override public void run() {
                db.exec("DELETE FROM routines WHERE id = ?", rid);
                b.core.audit("routine.delete", "user", null, null, null, null, null, null, J.obj("routine_id", rid));
            }
        });
        b.scheduleAlarm();
    }

    public List<JSONObject> history(String rid) {
        List<JSONObject> rows = db.all("SELECT rr.*, t.status AS task_status, t.result_text, t.error AS task_error, t.completed_at, t.created_at AS task_created "
                + "FROM routine_runs rr LEFT JOIN tasks t ON t.id = rr.task_id WHERE rr.routine_id = ? ORDER BY rr.created_at DESC LIMIT 100", rid);
        for (JSONObject r : rows) {
            J.put(r, "input", J.parse(r.optString("input_json")));
            r.remove("input_json");
            String tid = J.str(r, "task_id", null);
            J.put(r, "cost", tid == null ? 0 : Double.parseDouble(db.scalar("SELECT COALESCE(SUM(COALESCE(cost_confirmed, cost_estimated)), 0) FROM usage_entries u JOIN runs ru ON ru.id = u.run_id WHERE ru.task_id = ?", tid)));
        }
        return rows;
    }

    /** Dry run: shows what would happen; writes and calls nothing. */
    public JSONObject simulate(String rid) {
        JSONObject r = get(rid);
        if (r == null) throw new ApiError(404, "Unknown routine.");
        JSONObject bot = b.bots.get(r.optString("bot_id"));
        List<String> runs = nextRuns(r.optJSONObject("schedule"), r.optString("timezone"), 5, null);
        return J.obj("dry_run", true, "bot", bot == null ? null : bot.optString("name"), "prompt", r.optString("prompt"),
                "next_runs_utc", J.arr(runs), "next_runs_local", J.arr(describeLocal(runs, r.optString("timezone"))),
                "timezone", r.optString("timezone"), "note", "No task was created. Use Test run to execute for real.");
    }

    public JSONObject testRun(String rid) { return fire(rid, "test:" + J.id("t"), "test", null); }

    public JSONObject fire(final String rid, final String dedupe, final String trigger, final String scheduledFor) {
        final JSONObject r = db.one("SELECT * FROM routines WHERE id = ?", rid);
        if (r == null) throw new ApiError(404, "Unknown routine.");
        final JSONObject[] out = new JSONObject[1];
        db.tx(new Runnable() {
            @Override public void run() {
                JSONObject ex = db.one("SELECT * FROM routine_runs WHERE routine_id = ? AND dedupe_key = ?", rid, dedupe);
                if (ex != null) {
                    out[0] = J.obj("duplicate", true, "routine_run_id", ex.optString("id"), "task_id", ex.opt("task_id"));
                    return;
                }
                JSONObject bot = b.bots.get(r.optString("bot_id"));
                String status = "started", error = null, taskId = null;
                if (bot == null) { status = "skipped"; error = "bot missing"; }
                else if (bot.optBoolean("paused") && !"test".equals(trigger)) { status = "skipped"; error = "bot paused"; }
                else if ("skip".equals(r.optString("overlap_policy"))) {
                    String active = db.scalar("SELECT t.id FROM routine_runs rr JOIN tasks t ON t.id = rr.task_id WHERE rr.routine_id = ? AND t.status NOT IN ('completed','failed','cancelled')", rid);
                    if (active != null) { status = "skipped"; error = "previous run " + active + " still active"; }
                }
                String rrId = J.id("rr");
                db.insert("routine_runs", J.obj("id", rrId, "routine_id", rid, "dedupe_key", dedupe, "scheduled_for", scheduledFor,
                        "trigger", trigger, "status", status, "error", error, "created_at", J.nowIso()));
                if ("started".equals(status)) {
                    String cid = J.str(r, "conversation_id", null);
                    if (cid == null || db.one("SELECT 1 FROM conversations WHERE id = ?", cid) == null)
                        cid = b.tasks.privateConversation(r.optString("bot_id")).optString("id");
                    JSONObject t = b.tasks.createTask(r.optString("bot_id"), cid, "routine", rid, r.optString("prompt"), "⏰ " + r.optString("name"),
                            "", null, "test".equals(trigger) ? 70 : Tasks.PRIORITY_BACKGROUND, null, null, rrId);
                    taskId = t.optString("id");
                    db.exec("UPDATE routine_runs SET task_id = ? WHERE id = ?", taskId, rrId);
                }
                db.exec("UPDATE routines SET last_run_at = ? WHERE id = ?", J.nowIso(), rid);
                b.core.emit("routine.fired", null, taskId, null, r.optString("bot_id"),
                        J.obj("routine_id", rid, "routine_run_id", rrId, "status", status, "trigger", trigger));
                out[0] = J.obj("duplicate", false, "routine_run_id", rrId, "status", status, "task_id", taskId, "error", error);
            }
        });
        b.wake();
        return out[0];
    }

    /** Fire everything due; called from AlarmManager and on app start. */
    public synchronized int tick() {
        Instant now = Instant.ofEpochMilli(J.now());
        int count = 0;
        for (final JSONObject r : db.all("SELECT * FROM routines WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?", J.iso(now.toEpochMilli()))) {
            JSONObject sched = J.parse(r.optString("schedule_json"));
            ZoneId tz = zone(r.optString("timezone"));
            Instant slot = Instant.ofEpochMilli(J.parseIso(r.optString("next_run_at")));
            List<Instant> slots = new ArrayList<Instant>();
            slots.add(slot);
            if (!"once".equals(sched.optString("kind"))) {
                Cron c = new Cron(sched.optString("cron"));
                while (slots.size() < 1000) {
                    Instant nx = c.nextAfter(slots.get(slots.size() - 1), tz);
                    if (nx.isAfter(now)) break;
                    slots.add(nx);
                }
            }
            List<Instant> fire = new ArrayList<Instant>(), skipped = new ArrayList<Instant>();
            String cp = r.optString("catchup_policy");
            int n = slots.size();
            if ("latest".equals(cp)) { fire.add(slots.get(n - 1)); skipped.addAll(slots.subList(0, n - 1)); }
            else if ("all".equals(cp)) { fire.addAll(slots.subList(Math.max(0, n - 10), n)); skipped.addAll(slots.subList(0, Math.max(0, n - 10))); }
            else {
                Instant last = slots.get(n - 1);
                if (now.toEpochMilli() - last.toEpochMilli() <= 5 * 60 * 1000) fire.add(last);
                for (Instant s : slots) if (!fire.contains(s)) skipped.add(s);
            }
            for (final Instant s : skipped) {
                db.tx(new Runnable() {
                    @Override public void run() {
                        db.exec("INSERT OR IGNORE INTO routine_runs(id, routine_id, dedupe_key, scheduled_for, trigger, status, error, created_at) "
                                + "VALUES (?, ?, ?, ?, 'schedule', 'missed', 'phone was off or the app was stopped', ?)",
                                J.id("rr"), r.optString("id"), "slot:" + J.iso(s.toEpochMilli()), J.iso(s.toEpochMilli()), J.nowIso());
                    }
                });
            }
            for (Instant s : fire) {
                fire(r.optString("id"), "slot:" + J.iso(s.toEpochMilli()), "schedule", J.iso(s.toEpochMilli()));
                count++;
            }
            final String nxt = "once".equals(sched.optString("kind")) ? null : J.iso(new Cron(sched.optString("cron")).nextAfter(now, tz).toEpochMilli());
            db.tx(new Runnable() {
                @Override public void run() {
                    db.exec("UPDATE routines SET next_run_at = ?, enabled = ?, updated_at = ? WHERE id = ?", nxt, nxt != null, J.nowIso(), r.optString("id"));
                }
            });
        }
        b.scheduleAlarm();
        return count;
    }

    public long nextDueMillis() {
        String n = db.scalar("SELECT MIN(next_run_at) FROM routines WHERE enabled = 1 AND next_run_at IS NOT NULL");
        return n == null ? 0 : J.parseIso(n);
    }
}
