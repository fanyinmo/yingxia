package com.local.douyinsaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** Test APK only; independent provider process needs no main APK or Kotlin runtime. */
public final class DynamicPreviewFixtureProvider extends ContentProvider {
    public static final Uri GIF_URI = Uri.parse("content://com.local.douyinsaver.test.dynamicpreview/gif");
    public static final Uri WEBP_URI = Uri.parse("content://com.local.douyinsaver.test.dynamicpreview/webp");

    // Generated 16 x 16 red/blue GIF, 300 ms per frame, infinite original loop.
    private static final String GIF_BYTES = "R0lGODlhEAAQAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAHgAAACwAAAAAEAAQAAAIHQABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFgQEBACH5BAEeAAEALAAAAAAQABAAgQAA/wAAAAAAAAAAAAgdAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWBAQEAOw==";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        if (GIF_URI.equals(uri)) return "image/gif";
        if (WEBP_URI.equals(uri)) return "image/webp";
        throw new IllegalArgumentException("No such preview fixture");
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Preview fixtures are read-only");
        if (!GIF_URI.equals(uri) && !WEBP_URI.equals(uri)) throw new FileNotFoundException("No such preview fixture");
        final String mime = getType(uri);
        final byte[] bytes;
        if (GIF_URI.equals(uri)) bytes = Base64.decode(GIF_BYTES, Base64.DEFAULT);
        else {
            if (getContext() == null) throw new FileNotFoundException("Preview fixture context unavailable");
            try (InputStream input = getContext().getAssets().open("dynamic_album_test/two_frames.webp");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (output.size() + count > 256 * 1024) throw new IOException("Fixture exceeds fixed size limit");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            } catch (IOException failure) {
                FileNotFoundException missing = new FileNotFoundException("Preview fixture asset unavailable");
                missing.initCause(failure);
                throw missing;
            }
        }
        return openPipeHelper(uri, mime, null, bytes, new PipeDataWriter<byte[]>() {
            @Override public void writeDataToPipe(ParcelFileDescriptor pipe, Uri requestedUri, String mimeType,
                                                 Bundle options, byte[] data) {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe)) {
                    output.write(data);
                } catch (IOException failure) {
                    Log.w("DynamicPreviewFixture", "Unable to return synthetic image", failure);
                }
            }
        });
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        getType(uri);
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only fixtures"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only fixtures");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only fixtures");
    }
}
