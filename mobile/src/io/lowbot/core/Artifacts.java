package io.lowbot.core;

import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Files produced or uploaded, versioned per name and conversation (server/app/v2/artifacts.py). */
public final class Artifacts {
    public static final int MAX_BYTES = 25 * 1024 * 1024;
    private final Backend b;
    private final Db db;
    final File root;

    Artifacts(Backend b) {
        this.b = b;
        this.db = b.core.db;
        this.root = new File(b.core.filesDir, "artifacts");
        root.mkdirs();
    }

    public static String safeName(String name) {
        String n = new File(name == null ? "file" : name).getName().replaceAll("[^\\w.\\- ]+", "_").replaceAll("^[ .]+|[ .]+$", "");
        if (n.isEmpty()) n = "file";
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    public static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m == null && ext.equals("md")) m = "text/markdown";
        if (m == null && (ext.equals("txt") || ext.equals("log") || ext.equals("csv"))) m = "text/plain";
        return m == null ? "application/octet-stream" : m;
    }

    public JSONObject create(String rawName, byte[] data, String mime, final JSONObject task, final String runId, String botId, String cid) {
        if (data.length > MAX_BYTES) throw new ApiError(413, "Artifact too large.");
        final String name = safeName(rawName);
        final String m = mime == null || mime.isEmpty() ? guessMime(name) : mime;
        final String aid = J.id("art");
        final File f = new File(root, aid);
        try {
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(data);
            fo.close();
        } catch (Exception e) {
            throw new ApiError(500, "Could not store the file.");
        }
        final String conv = cid != null ? cid : task == null ? null : J.str(task, "conversation_id", null);
        final String bot = botId != null ? botId : task == null ? null : task.optString("bot_id");
        final int size = data.length;
        final String sha = sha256(data);
        db.tx(new Runnable() {
            @Override public void run() {
                long version = 1 + db.count("SELECT COALESCE(MAX(version), 0) FROM artifacts WHERE name = ? AND COALESCE(conversation_id, '') = ?", name, conv == null ? "" : conv);
                db.insert("artifacts", J.obj("id", aid, "task_id", task == null ? null : task.optString("id"), "run_id", runId, "bot_id", bot,
                        "conversation_id", conv, "name", name, "mime", m, "size", size, "sha256", sha, "version", version,
                        "storage_path", f.getAbsolutePath(), "created_at", J.nowIso()));
                b.core.emit("artifact.created", conv, task == null ? null : task.optString("id"), null, bot,
                        J.obj("artifact_id", aid, "name", name, "version", version, "size", size));
            }
        });
        return get(aid);
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public JSONObject get(String id) {
        JSONObject r = db.one("SELECT * FROM artifacts WHERE id = ?", id);
        if (r != null) r.remove("storage_path");
        return r;
    }

    public File path(String id) {
        String p = db.scalar("SELECT storage_path FROM artifacts WHERE id = ?", id);
        if (p == null) return null;
        File f = new File(p);
        try {
            return f.exists() && f.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    public List<JSONObject> list(String cid, String taskId) {
        List<JSONObject> rows;
        if (cid != null) rows = db.all("SELECT * FROM artifacts WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 200", cid);
        else if (taskId != null) rows = db.all("SELECT * FROM artifacts WHERE task_id = ? ORDER BY created_at DESC LIMIT 200", taskId);
        else rows = db.all("SELECT * FROM artifacts ORDER BY created_at DESC LIMIT 200");
        for (JSONObject r : rows) r.remove("storage_path");
        return rows;
    }
}
