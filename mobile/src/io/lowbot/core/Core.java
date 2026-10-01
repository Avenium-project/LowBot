package io.lowbot.core;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Shared container: database, settings, event outbox, audit log, inbox and the
 * encrypted secret vault. One instance per process (see Backend).
 */
public final class Core {
    public static final String OWNER = "local-user";

    public interface EventListener { void onEvent(JSONObject event); }

    /** Implemented by the Android layer to show OS notifications. */
    public interface Notifier { void show(JSONObject notification); }

    public final Context ctx;
    public final Db db;
    public final File filesDir;
    public final File workspace;
    public final List<EventListener> listeners = new CopyOnWriteArrayList<EventListener>();
    public volatile Notifier notifier;
    public final Settings settings = new Settings();

    public static final class Settings {
        public int maxActiveRuns = 4;
        public int maxActiveSurfaces = 2;
        public int defaultMaxSteps = 24;
        public int runTimeoutS = 1800;
        public int maxAttempts = 4;
        public int maxDelegationDepth = 4;
        public int maxTasksPerCorrelation = 40;
        public int approvalTtlS = 86400;
        public long retryBaseMs = 2000;
        public long retryMaxMs = 300000;
        public String timezone = "Europe/Warsaw";
        public boolean allowPrivateNetwork = false;
        /** Instead of compacting context: past these limits the bot writes a handoff (agents.md) and starts fresh. */
        public int handoffMaxMessages = 40;
        public int handoffMaxChars = 48000;
    }

    public Core(Context ctx, String dbName) {
        this.ctx = ctx;
        this.filesDir = new File(ctx.getFilesDir(), "lowbot");
        this.workspace = new File(filesDir, "workspace");
        if (!workspace.exists()) workspace.mkdirs();
        new File(filesDir, "artifacts").mkdirs();
        this.db = new Db(ctx, dbName);
        db.getWritableDatabase();
    }

    // ---------------------------------------------------------------- events
    /** Append an event inside the caller's transaction; listeners run after commit. */
    public void emit(String type, String conversationId, String taskId, String runId, String botId, JSONObject payload) {
        if (conversationId == null && taskId != null) conversationId = db.scalar("SELECT conversation_id FROM tasks WHERE id = ?", taskId);
        final JSONObject ev = J.obj("type", type, "conversation_id", conversationId, "task_id", taskId, "run_id", runId,
                "bot_id", botId, "payload_json", J.redactObj(payload == null ? new JSONObject() : payload).toString(),
                "created_at", J.nowIso());
        db.insert("events", ev);
        final String id = db.scalar("SELECT last_insert_rowid()");
        db.afterCommit(new Runnable() {
            @Override public void run() {
                JSONObject out = J.parse(ev.toString());
                J.put(out, "id", Long.parseLong(id == null ? "0" : id));
                J.put(out, "payload", J.parse(ev.optString("payload_json")));
                out.remove("payload_json");
                for (EventListener l : listeners) {
                    try { l.onEvent(out); } catch (Exception ignored) { }
                }
            }
        });
    }

    public void emit(String type, JSONObject payload) {
        emit(type, null, null, null, null, payload);
    }

    public List<JSONObject> eventsAfter(long cursor, int limit, String conversationId) {
        List<JSONObject> rows = conversationId == null
                ? db.all("SELECT * FROM events WHERE id > ? ORDER BY id LIMIT ?", cursor, limit)
                : db.all("SELECT * FROM events WHERE id > ? AND conversation_id = ? ORDER BY id LIMIT ?", cursor, conversationId, limit);
        for (JSONObject r : rows) {
            J.put(r, "payload", J.parse(r.optString("payload_json")));
            r.remove("payload_json");
        }
        return rows;
    }

    // ----------------------------------------------------------------- audit
    public void audit(String action, String actorType, String actorId, String taskId, String runId, String approvalId,
                      String decision, String outcome, JSONObject summary) {
        db.insert("audit_log", J.obj("actor_type", actorType, "actor_id", actorId, "action", action, "task_id", taskId,
                "run_id", runId, "approval_id", approvalId, "decision", decision, "outcome", outcome,
                "summary_json", J.redactObj(summary == null ? new JSONObject() : summary).toString(), "created_at", J.nowIso()));
    }

    // ----------------------------------------------------------------- inbox
    /** The inbox table is the source of truth; the OS notification is an extra. */
    public String notify(String kind, String title, String body, String taskId, String approvalId, String conversationId, String botId) {
        final String nid = J.id("ntf");
        final JSONObject n = J.obj("id", nid, "kind", kind, "title", J.truncate(title, 200), "body", J.truncate(J.redact(body), 2000),
                "task_id", taskId, "approval_id", approvalId, "conversation_id", conversationId, "bot_id", botId,
                "created_at", J.nowIso());
        db.insert("notifications", n);
        emit("notification.created", conversationId, taskId, null, botId, J.obj("notification_id", nid, "kind", kind, "title", title));
        db.afterCommit(new Runnable() {
            @Override public void run() {
                Notifier nf = notifier;
                if (nf != null) {
                    try { nf.show(n); } catch (Exception ignored) { }
                }
            }
        });
        return nid;
    }

    // -------------------------------------------------------------------- kv
    public String kvGet(String key) {
        return db.scalar("SELECT value FROM kv WHERE key = ?", key);
    }

    public void kvSet(final String key, final String value) {
        db.tx(new Runnable() {
            @Override public void run() {
                if (value == null) db.exec("DELETE FROM kv WHERE key = ?", key);
                else db.exec("INSERT INTO kv(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
            }
        });
    }

    public boolean kvBool(String key, boolean def) {
        String v = kvGet(key);
        return v == null ? def : "1".equals(v) || "true".equals(v);
    }

    // ----------------------------------------------------------------- vault
    private static final String VAULT_KEY = "lowbot_vault_key";

    private SecretKey vaultKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(VAULT_KEY)) return ((KeyStore.SecretKeyEntry) ks.getEntry(VAULT_KEY, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(VAULT_KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return gen.generateKey();
    }

    /** Stores an encrypted secret (API keys, MCP tokens). Plaintext never reaches the DB, events or the model. */
    public String secretPut(final String name, final String kind, final String description, String value, String existingId) {
        final String ct;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, vaultKey());
            ct = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException("Secret encryption failed: " + e.getClass().getSimpleName());
        }
        final String sid = existingId != null ? existingId : J.id("sec");
        db.tx(new Runnable() {
            @Override public void run() {
                if (db.one("SELECT id FROM secret_references WHERE id = ?", sid) != null) {
                    db.exec("UPDATE secret_references SET ciphertext = ?, name = ?, description = ? WHERE id = ?", ct, name, description, sid);
                } else {
                    db.insert("secret_references", J.obj("id", sid, "name", name, "kind", kind, "description", description,
                            "ciphertext", ct, "created_at", J.nowIso()));
                }
            }
        });
        return sid;
    }

    public String secretGet(String id) {
        if (id == null) return null;
        String ct = db.scalar("SELECT ciphertext FROM secret_references WHERE id = ?", id);
        if (ct == null) return null;
        try {
            String[] p = ct.split(":", 2);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, vaultKey(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public String secretIdByName(String name) {
        return db.scalar("SELECT id FROM secret_references WHERE name = ? ORDER BY created_at DESC", name);
    }

    public void secretDelete(final String id) {
        if (id == null) return;
        db.tx(new Runnable() {
            @Override public void run() { db.exec("DELETE FROM secret_references WHERE id = ?", id); }
        });
    }
}
