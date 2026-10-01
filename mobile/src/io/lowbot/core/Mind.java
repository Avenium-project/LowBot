package io.lowbot.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A bot's long-lived memory as plain Markdown files in its own folder
 * (filesDir/lowbot/bots/&lt;bot_id&gt;/):
 *
 *   soul.md          purpose and behaviour of the agent (who it is, how it works)
 *   agents.md        the global HANDOFF from the previous session. It is never
 *                    compacted: when a session ends or the context gets long, the file is
 *                    cleared and replaced by a fresh handoff (exact goal, state, next steps)
 *                    written by the previous agent, and the next session starts from it.
 *   memories/*.md    small memories the agent writes for itself (long-term facts)
 *
 * Project rules live with the project: workspace/&lt;project&gt;/AGENTS.md — only how the agent
 * behaves while working on that project.
 */
public final class Mind {
    public static final int SOUL_MAX = 12000, AGENTS_MAX = 12000, MEMORY_MAX = 2000, PROJECT_MAX = 8000;
    private final Backend b;

    Mind(Backend b) { this.b = b; }

    public File dir(String botId) {
        File d = new File(b.core.filesDir, "bots/" + botId.replaceAll("[^A-Za-z0-9_]", "_"));
        new File(d, "memories").mkdirs();
        return d;
    }

    static String read(File f) {
        if (!f.isFile()) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] data = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int off = 0, n;
            while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
            in.close();
            return new String(data, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Atomic replace. */
    static void write(File f, String content) {
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getParentFile(), "." + f.getName() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
            o.close();
            if (!tmp.renameTo(f)) throw new RuntimeException("rename failed");
        } catch (Exception e) {
            throw new ApiError(500, "Could not write " + f.getName());
        }
    }

    static String limit(String s, int max, String what) {
        String v = s == null ? "" : s.trim();
        if (v.length() > max) throw new ApiError(422, what + " is too long (max " + max + " characters).");
        return v;
    }

    // ------------------------------------------------------------------- soul
    public String soul(String botId) { return read(new File(dir(botId), "soul.md")); }

    public void setSoul(String botId, String content) {
        write(new File(dir(botId), "soul.md"), limit(content, SOUL_MAX, "soul.md") + "\n");
        event(botId, "soul");
    }

    /** Initial soul.md from the profile fields of a new bot. */
    void seed(JSONObject bot) {
        File f = new File(dir(bot.optString("id")), "soul.md");
        if (f.exists()) return;
        StringBuilder sb = new StringBuilder("# ").append(bot.optString("name")).append("\n\n## Purpose\n");
        sb.append(bot.optString("role_description").isEmpty() ? "A helpful, persistent AI coworker." : bot.optString("role_description"));
        sb.append("\n\n## Behaviour\n");
        sb.append(bot.optString("instructions").isEmpty()
                ? "- Be concise and concrete.\n- Verify results before saying something is done.\n- Ask when a decision is mine to make."
                : bot.optString("instructions"));
        write(f, sb.append("\n").toString());
    }

    // ---------------------------------------------------------------- handoff
    public String handoff(String botId) { return read(new File(dir(botId), "agents.md")); }

    /** Clears agents.md and replaces it with a new handoff; the next session starts from it. */
    public void replaceHandoff(String botId, String content, String conversationId, long fromSeq, String author) {
        String body = limit(content, AGENTS_MAX, "agents.md");
        write(new File(dir(botId), "agents.md"), "<!-- handoff written " + J.nowIso() + " by " + author + " -->\n" + body + "\n");
        if (conversationId != null) b.core.kvSet("handoff_seq:" + botId + ":" + conversationId, String.valueOf(fromSeq));
        event(botId, "agents");
    }

    public long handoffSeq(String botId, String conversationId) {
        String v = conversationId == null ? null : b.core.kvGet("handoff_seq:" + botId + ":" + conversationId);
        try { return v == null ? 0 : Long.parseLong(v); } catch (NumberFormatException e) { return 0; }
    }

    // ---------------------------------------------------------- small memories
    static String slug(String s) {
        String v = J.slug(s);
        return v.length() > 60 ? v.substring(0, 60) : v;
    }

    public JSONObject remember(String botId, String title, String content) {
        String t = limit(title, 120, "title");
        if (t.isEmpty()) throw new ApiError(422, "A memory needs a short title.");
        String c = limit(content, MEMORY_MAX, "memory");
        String name = slug(t) + ".md";
        write(new File(dir(botId), "memories/" + name), "# " + t + "\n" + c + "\n");
        event(botId, "memories");
        return J.obj("name", name, "title", t);
    }

    public void forget(String botId, String name) {
        File f = new File(dir(botId), "memories/" + new File(name).getName());
        if (f.isFile()) f.delete();
        event(botId, "memories");
    }

    public List<JSONObject> memories(String botId) {
        File[] fs = new File(dir(botId), "memories").listFiles();
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (fs == null) return out;
        Arrays.sort(fs, new Comparator<File>() {
            public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
        });
        for (File f : fs) {
            if (!f.getName().endsWith(".md")) continue;
            String text = read(f);
            String title = text.startsWith("# ") ? text.substring(2, text.indexOf('\n') > 0 ? text.indexOf('\n') : text.length()) : f.getName();
            String body = text.indexOf('\n') > 0 ? text.substring(text.indexOf('\n') + 1).trim() : "";
            out.add(J.obj("name", f.getName(), "title", title, "content", body, "updated_at", J.iso(f.lastModified())));
        }
        return out;
    }

    // ---------------------------------------------------------------- projects
    public File projectDir(String project) {
        String p = project == null ? "" : project.trim().replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("^[ .]+", "");
        if (p.isEmpty() || p.contains("..")) throw new ApiError(422, "Invalid project name.");
        if (p.length() > 60) p = p.substring(0, 60);
        return new File(b.core.workspace, p);
    }

    public String projectRules(String project) {
        return project == null || project.isEmpty() ? "" : read(new File(projectDir(project), "AGENTS.md"));
    }

    public void setProjectRules(String project, String content) {
        File d = projectDir(project);
        d.mkdirs();
        write(new File(d, "AGENTS.md"), limit(content, PROJECT_MAX, "AGENTS.md") + "\n");
        b.core.db.tx(new Runnable() { public void run() { b.core.emit("project.updated", null, null, null, null, null); } });
    }

    public List<JSONObject> projects() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        File[] ds = b.core.workspace.listFiles();
        if (ds == null) return out;
        Arrays.sort(ds);
        for (File d : ds) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            out.add(J.obj("name", d.getName(), "agents_md", read(new File(d, "AGENTS.md"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ misc
    public JSONObject all(String botId) {
        return J.obj("soul", soul(botId), "agents", handoff(botId), "memories", Db.toArray(memories(botId)),
                "projects", Db.toArray(projects()), "folder", "bots/" + botId);
    }

    public void copy(String fromBot, String toBot) {
        String s = soul(fromBot);
        if (!s.isEmpty()) write(new File(dir(toBot), "soul.md"), s);
    }

    public void deleteAll(String botId) {
        File d = dir(botId);
        File[] mem = new File(d, "memories").listFiles();
        if (mem != null) for (File f : mem) f.delete();
        new File(d, "memories").delete();
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    void event(final String botId, final String what) {
        b.core.db.tx(new Runnable() { public void run() { b.core.emit("bot.mind_updated", null, null, null, botId, J.obj("file", what)); } });
    }

    /** Prompt section built from the files (soul first, then handoff, project rules, small memories). */
    public String promptSection(String botId, String project) {
        StringBuilder sb = new StringBuilder();
        String soul = soul(botId);
        if (!soul.isEmpty()) sb.append("=== soul.md (your purpose and behaviour) ===\n").append(soul.trim()).append("\n\n");
        String h = handoff(botId).replaceAll("<!--.*?-->\\n?", "").trim();
        if (!h.isEmpty()) sb.append("=== agents.md (handoff from your previous session — continue from here) ===\n").append(h).append("\n\n");
        if (project != null && !project.isEmpty()) {
            String r = projectRules(project).trim();
            sb.append("=== Current project: ").append(project).append(" (folder ").append(project).append("/ in the workspace) ===\n");
            sb.append(r.isEmpty() ? "No AGENTS.md yet. Use project.update_rules to record how to work on this project." : "AGENTS.md:\n" + r).append("\n\n");
        }
        List<JSONObject> mems = memories(botId);
        if (!mems.isEmpty()) {
            sb.append("=== Small memories (memories/*.md; may be stale — verify changing facts) ===\n");
            int used = 0;
            for (JSONObject m : mems) {
                String line = "- " + m.optString("title") + ": " + J.truncate(m.optString("content").replace('\n', ' '), 300) + "\n";
                if (used + line.length() > 6000) { sb.append("- … (more in memories/)\n"); break; }
                sb.append(line);
                used += line.length();
            }
        }
        return sb.toString().trim();
    }

    static JSONArray names(List<JSONObject> rows) {
        JSONArray a = new JSONArray();
        for (JSONObject r : rows) a.put(r.optString("name"));
        return a;
    }
}
