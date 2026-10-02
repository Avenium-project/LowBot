package io.lowbot.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * On-device SQLite database (WAL). Same data model as the LowBot server:
 * bot_id, conversation_id, message_id, task_id and run_id stay separate.
 * All writes go through {@link #tx(Runnable)} so state changes and their
 * events commit atomically.
 */
public final class Db extends SQLiteOpenHelper {
    public static final int VERSION = 5;
    private final List<Runnable> afterCommit = new ArrayList<Runnable>();
    private int depth = 0;

    public Db(Context ctx, String name) {
        super(ctx, name, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        for (String s : Schema.V1) db.execSQL(s);
        for (String s : Schema.V2) db.execSQL(s);
        for (String s : Schema.V3) db.execSQL(s);
        for (String s : Schema.V4) db.execSQL(s);
        for (String s : Schema.V5) db.execSQL(s);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Append-only migrations.
        if (oldV < 2) for (String s : Schema.V2) db.execSQL(s);
        if (oldV < 3) for (String s : Schema.V3) db.execSQL(s);
        if (oldV < 4) for (String s : Schema.V4) db.execSQL(s);
        if (oldV < 5) for (String s : Schema.V5) db.execSQL(s);
    }

    private SQLiteDatabase w() {
        return getWritableDatabase();
    }

    /** Write transaction; re-entrant. Callbacks registered with {@link #afterCommit} run after COMMIT. */
    public synchronized void tx(Runnable body) {
        SQLiteDatabase db = w();
        if (depth > 0) {
            depth++;
            try {
                body.run();
            } finally {
                depth--;
            }
            return;
        }
        db.beginTransaction();
        depth = 1;
        List<Runnable> cbs;
        try {
            body.run();
            db.setTransactionSuccessful();
        } catch (RuntimeException e) {
            synchronized (afterCommit) {
                afterCommit.clear();
            }
            throw e;
        } finally {
            depth = 0;
            db.endTransaction();
        }
        synchronized (afterCommit) {
            cbs = new ArrayList<Runnable>(afterCommit);
            afterCommit.clear();
        }
        for (Runnable r : cbs) r.run();
    }

    public void afterCommit(Runnable r) {
        if (depth > 0) {
            synchronized (afterCommit) {
                afterCommit.add(r);
            }
        } else {
            r.run();
        }
    }

    public synchronized void exec(String sql, Object... args) {
        change(sql, args);
    }

    /** UPDATE/DELETE returning the number of affected rows. */
    public synchronized int change(String sql, Object... args) {
        android.database.sqlite.SQLiteStatement st = w().compileStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null || a == JSONObject.NULL) st.bindNull(i + 1);
                else if (a instanceof Integer || a instanceof Long) st.bindLong(i + 1, ((Number) a).longValue());
                else if (a instanceof Double || a instanceof Float) st.bindDouble(i + 1, ((Number) a).doubleValue());
                else if (a instanceof Boolean) st.bindLong(i + 1, ((Boolean) a) ? 1 : 0);
                else st.bindString(i + 1, a.toString());
            }
            return st.executeUpdateDelete();
        } finally {
            st.close();
        }
    }

    public synchronized void insert(String table, JSONObject values) {
        ContentValues cv = new ContentValues();
        Iterator<String> it = values.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = values.opt(k);
            if (v == null || v == JSONObject.NULL) cv.putNull(k);
            else if (v instanceof Integer) cv.put(k, (Integer) v);
            else if (v instanceof Long) cv.put(k, (Long) v);
            else if (v instanceof Double) cv.put(k, (Double) v);
            else if (v instanceof Boolean) cv.put(k, ((Boolean) v) ? 1 : 0);
            else cv.put(k, v.toString());
        }
        w().insertOrThrow(table, null, cv);
    }

    public List<JSONObject> all(String sql, Object... args) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        Cursor c;
        synchronized (this) {
            c = getReadableDatabase().rawQuery(sql, strArgs(args));
        }
        try {
            while (c.moveToNext()) out.add(row(c));
        } finally {
            c.close();
        }
        return out;
    }

    public JSONObject one(String sql, Object... args) {
        List<JSONObject> r = all(sql + (sql.toUpperCase().contains(" LIMIT ") ? "" : " LIMIT 1"), args);
        return r.isEmpty() ? null : r.get(0);
    }

    public String scalar(String sql, Object... args) {
        Cursor c;
        synchronized (this) {
            c = getReadableDatabase().rawQuery(sql, strArgs(args));
        }
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    public long count(String sql, Object... args) {
        String s = scalar(sql, args);
        return s == null ? 0 : Long.parseLong(s);
    }

    public static JSONArray toArray(List<JSONObject> rows) {
        JSONArray a = new JSONArray();
        for (JSONObject r : rows) a.put(r);
        return a;
    }

    private static String[] strArgs(Object[] args) {
        String[] s = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            s[i] = a == null ? null : (a instanceof Boolean ? (((Boolean) a) ? "1" : "0") : a.toString());
        }
        return s;
    }

    private static JSONObject row(Cursor c) {
        JSONObject o = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++) {
            String k = c.getColumnName(i);
            switch (c.getType(i)) {
                case Cursor.FIELD_TYPE_NULL: J.put(o, k, null); break;
                case Cursor.FIELD_TYPE_INTEGER: J.put(o, k, c.getLong(i)); break;
                case Cursor.FIELD_TYPE_FLOAT: J.put(o, k, c.getDouble(i)); break;
                default: J.put(o, k, c.getString(i));
            }
        }
        return o;
    }
}
