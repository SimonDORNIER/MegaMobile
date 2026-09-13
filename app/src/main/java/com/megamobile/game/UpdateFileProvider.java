package com.megamobile.game;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Lightweight read-only provider used only to hand a downloaded update APK to Android. */
public class UpdateFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("context");
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("invalid update name");
        }
        File root = getContext().getExternalFilesDir(null);
        if (root == null) throw new FileNotFoundException("storage");
        File file = new File(new File(root, "updates"), name);
        if (!file.isFile()) throw new FileNotFoundException(name);
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.startsWith("r")) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{file.getName(), file.length()});
            return cursor;
        } catch (Exception ignored) {
            return new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        }
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
}
