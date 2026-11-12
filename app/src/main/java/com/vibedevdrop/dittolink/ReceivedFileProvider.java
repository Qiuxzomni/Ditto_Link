package com.vibedevdrop.dittolink;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only access to the latest received file for Android's share sheet. */
public final class ReceivedFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Invalid file request");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    static Uri uriForFileName(String name) {
        return Uri.parse("content://com.vibedevdrop.dittolink.files/" + Uri.encode(name));
    }

    static Uri uriForDownloadFileName(String name) {
        return Uri.parse("content://com.vibedevdrop.dittolink.files/download/" + Uri.encode(name));
    }

    static Uri uriForPrivateFileName(String name) {
        return Uri.parse("content://com.vibedevdrop.dittolink.files/private/" + Uri.encode(name));
    }

    static Uri uriForSharedFileName(String name) {
        return Uri.parse("content://com.vibedevdrop.dittolink.files/shared/" + Uri.encode(name));
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        String extension = name == null ? "" : MimeTypeMap.getFileExtensionFromUrl(name);
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) {
        try { return resolve(uri).delete() ? 1 : 0; }
        catch (FileNotFoundException ignored) { return 0; }
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }

    private File resolve(Uri uri) throws FileNotFoundException {
        java.util.List<String> segments = uri.getPathSegments();
        String source = segments.size() > 1 ? segments.get(0) : "";
        String name = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        if (name == null || name.isEmpty() || !new File(name).getName().equals(name))
            throw new FileNotFoundException("Invalid file request");
        File downloads = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Ditto"), name);
        File privateDownloads = new File(new File(getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Ditto"), name);
        File sharedCache = new File(getContext().getCacheDir(), "shared_" + name);
        File[] candidates = "download".equals(source) ? new File[] { downloads }
                : "private".equals(source) ? new File[] { privateDownloads }
                : "shared".equals(source) ? new File[] { sharedCache }
                : new File[] { downloads, privateDownloads, sharedCache };
        for (File file : candidates) {
            try {
                if (file.isFile() && file.getCanonicalFile().getParentFile().equals(file.getParentFile().getCanonicalFile()))
                    return file;
            } catch (java.io.IOException ignored) { }
        }
        throw new FileNotFoundException("File not found");
    }
}
