package dev.jason.gboardpatches.extension.webclipboard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * A lightweight ContentProvider to expose Web Clipboard images to recipient apps.
 */
public final class WebClipboardFileProvider extends ContentProvider {
    public static final String AUTHORITY_SUFFIX = ".webclipboard.provider";
    private static final String SUBDIR = "web_clipboard";

    @Override
    public boolean onCreate() {
        return true;
    }

    public static File getFile(Context context, String filename) {
        File dir = new File(context.getCacheDir(), SUBDIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, filename);
    }

    public static Uri getUriForFile(Context context, String filename) {
        return Uri.parse("content://" + context.getPackageName() + AUTHORITY_SUFFIX + "/" + filename);
    }

    public static void saveImageFile(Context context, String filename, byte[] bytes) throws IOException {
        File file = getFile(context, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
            fos.flush();
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("Context is null");
        }
        String filename = uri.getLastPathSegment();
        if (filename == null || filename.trim().isEmpty()) {
            throw new FileNotFoundException("Invalid filename in URI: " + uri);
        }
        File file = getFile(context, filename);
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + filename);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String filename = uri.getLastPathSegment();
        if (filename != null && (filename.endsWith(".jpg") || filename.endsWith(".jpeg"))) {
            return "image/jpeg";
        }
        return "image/png";
    }

    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        String mime = getType(uri);
        if (mimeTypeFilter == null || mimeTypeFilter.equals("*/*") || mimeTypeFilter.startsWith("image/")) {
            return new String[]{mime};
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        String filename = uri.getLastPathSegment();
        if (filename == null || filename.trim().isEmpty()) {
            return null;
        }
        File file = getFile(context, filename);
        if (!file.exists()) {
            return null;
        }

        String[] cols = projection != null ? projection : new String[]{
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
        };
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(col, file.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(col, file.length());
            } else {
                row.add(col, null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
