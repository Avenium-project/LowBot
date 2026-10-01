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
        projectEvent();
    }

    // ------------------------------------------------- shared workspaces
    // A workspace is a folder in the shared /workspace (workspace/<name>/) with its own
    // AGENTS.md (rules every member follows), its files, and a list of member bots.
    // Membership is kept in the database (not in the folder), so a bot cannot add itself
    // to a workspace by writing a file.

    public List<String> members(String project) {
        List<String> out = new ArrayList<String>();
        JSONArray a = J.parseArr(b.core.kvGet("ws_members:" + projectDir(project).getName()));
        for (int i = 0; i < a.length(); i++) if (b.bots.get(a.optString(i)) != null && !out.contains(a.optString(i))) out.add(a.optString(i));
        return out;
    }

    public void setMembers(String project, List<String> botIds) {
        JSONArray a = new JSONArray();
        for (String id : botIds) { if (b.bots.get(id) == null) throw new ApiError(422, "Unknown bot: " + id); boolean dup = false;
            for (int i = 0; i < a.length(); i++) if (a.optString(i).equals(id)) dup = true;
            if (!dup) a.put(id); }
        b.core.kvSet("ws_members:" + projectDir(project).getName(), a.toString());
        projectEvent();
    }

    public void addMember(String project, String botId) {
        List<String> m = members(project);
        if (m.contains(botId)) return;
        m.add(botId);
        setMembers(project, m);
    }

    public List<String> workspacesOf(String botId) {
        List<String> out = new ArrayList<String>();
        for (JSONObject p : projects()) if (members(p.optString("name")).contains(botId)) out.add(p.optString("name"));
        return out;
    }

    public JSONObject createWorkspace(String name, String rules, List<String> memberIds) {
        File d = projectDir(name);
        if (d.isDirectory() && new File(d, "AGENTS.md").isFile() && !members(d.getName()).isEmpty())
            throw new ApiError(409, "A workspace named " + d.getName() + " already exists.");
        d.mkdirs();
        if (rules != null && !rules.trim().isEmpty()) write(new File(d, "AGENTS.md"), limit(rules, PROJECT_MAX, "AGENTS.md") + "\n");
        else if (!new File(d, "AGENTS.md").isFile()) write(new File(d, "AGENTS.md"), "# " + d.getName() + "\n\nShared rules for every bot in this workspace.\n");
        List<String> m = members(d.getName());
        for (String id : memberIds) if (!m.contains(id)) m.add(id);
        setMembers(d.getName(), m);
        return workspace(d.getName());
    }

    /** Removes the folder (files and AGENTS.md) and its membership. Chats bound to it are unbound. */
    public void deleteWorkspace(String name) {
        final File d = projectDir(name);
        if (!d.isDirectory()) throw new ApiError(404, "Unknown workspace: " + name);
        deleteTree(d);
        b.core.kvSet("ws_members:" + d.getName(), null);
        b.core.db.exec("UPDATE conversations SET project = NULL WHERE project = ?", d.getName());
        projectEvent();
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) {
            // never follow symlinks out of the workspace
            try { if (k.getCanonicalPath().startsWith(f.getCanonicalPath() + File.separator)) deleteTree(k); else k.delete(); }
            catch (Exception e) { k.delete(); }
        }
        f.delete();
    }

    public JSONArray files(String project, int max) {
        JSONArray out = new JSONArray();
        File root = projectDir(project);
        collect(root, root, out, max);
        return out;
    }

    private static void collect(File root, File dir, JSONArray out, int max) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (out.length() >= max) return;
            if (f.getName().startsWith(".")) continue;
            String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
            if (f.isDirectory()) collect(root, f, out, max);
            else out.put(J.obj("path", rel, "size", f.length(), "updated_at", J.iso(f.lastModified())));
        }
    }

    public JSONObject workspace(String name) {
        File d = projectDir(name);
        if (!d.isDirectory()) throw new ApiError(404, "Unknown workspace: " + name);
        JSONArray mem = new JSONArray();
        for (String id : members(d.getName())) mem.put(id);
        JSONArray fs = files(d.getName(), 500);
        return J.obj("name", d.getName(), "agents_md", read(new File(d, "AGENTS.md")), "members", mem, "files_count", fs.length(),
                "folder", d.getName() + "/", "updated_at", J.iso(d.lastModified()));
    }

    public List<JSONObject> projects() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        File[] ds = b.core.workspace.listFiles();
        if (ds == null) return out;
        Arrays.sort(ds);
        for (File d : ds) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            // Only folders that are workspaces (rules or members), not every folder a tool created.
            if (!new File(d, "AGENTS.md").isFile() && b.core.kvGet("ws_members:" + d.getName()) == null) continue;
            out.add(workspace(d.getName()));
        }
        return out;
    }

    void projectEvent() {
        b.core.db.tx(new Runnable() { public void run() { b.core.emit("project.updated", null, null, null, null, null); } });
    }

    // ------------------------------------------------------------------ misc
    public JSONObject all(String botId) {
        List<JSONObject> mine = new ArrayList<JSONObject>();
        for (String w : workspacesOf(botId)) mine.add(workspace(w));
        return J.obj("soul", soul(botId), "agents", handoff(botId), "memories", Db.toArray(memories(botId)),
                "projects", Db.toArray(mine), "folder", "bots/" + botId);
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
            sb.append("=== Current workspace: ").append(project).append(" (shared folder ").append(project).append("/ — every member bot sees the same files and rules) ===\n");
            sb.append(r.isEmpty() ? "No AGENTS.md yet. Use project.update_rules to record how to work in this workspace." : "AGENTS.md (shared rules — follow them):\n" + r).append("\n");
            StringBuilder who = new StringBuilder();
            for (String id : members(project)) {
                JSONObject o = b.bots.get(id);
                if (o != null && !id.equals(botId)) who.append(who.length() > 0 ? ", " : "").append("@").append(o.optString("handle"));
            }
            if (who.length() > 0) sb.append("Other members: ").append(who).append("\n");
            JSONArray fs = files(project, 30);
            if (fs.length() > 0) {
                sb.append("Shared files:");
                for (int i = 0; i < fs.length(); i++) sb.append(i == 0 ? " " : ", ").append(project).append("/").append(fs.optJSONObject(i).optString("path"));
                if (fs.length() >= 30) sb.append(", …");
                sb.append("\n");
            }
            sb.append("\n");
        }
        List<String> mine = workspacesOf(botId);
        mine.remove(project == null ? "" : project);
        if (!mine.isEmpty()) {
            sb.append("=== Your other workspaces (project.use to switch) ===\n");
            for (String w : mine) sb.append("- ").append(w).append(": ").append(J.truncate(projectRules(w).replaceAll("\\s+", " ").trim(), 160)).append("\n");
            sb.append("\n");
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
