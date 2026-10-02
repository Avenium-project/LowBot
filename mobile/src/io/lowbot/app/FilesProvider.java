package io.lowbot.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only hand-off of a single file to another app (open with / share), like
 * AndroidX FileProvider but without the library. Only files the app placed in
 * cache/shared/ are reachable, through URIs granted per intent; not exported.
 */
public final class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "io.lowbot.app.files";

    static File root(Context c) { return new File(c.getCacheDir(), "shared"); }

    static Uri uriFor(File f, Context c) {
        String rel = f.getAbsolutePath().substring(root(c).getAbsolutePath().length() + 1);
        return new Uri.Builder().scheme("content").authority(AUTHORITY).encodedPath("/" + Uri.encode(rel, "/")).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        try {
            File base = root(getContext()).getCanonicalFile();
            File f = new File(base, uri.getPath() == null ? "" : uri.getPath()).getCanonicalFile();
            if (!f.getPath().startsWith(base.getPath() + File.separator) || !f.isFile()) throw new FileNotFoundException();
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException();
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
        String t = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        if (t == null && ext != null && (ext.equalsIgnoreCase("md") || ext.equalsIgnoreCase("log"))) t = "text/plain";
        return t == null ? "application/octet-stream" : t;
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String sel, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String sel, String[] args) { throw new UnsupportedOperationException(); }
}
