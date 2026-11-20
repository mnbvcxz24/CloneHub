package com.fuku.floating;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Registers the floating-window lifecycle hook before the clone launches activities. */
public final class FloatingInitProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        try {
            Application app = (Application) getContext().getApplicationContext();
            FloatingWindow.install(app);
            return true;
        } catch (Throwable error) {
            android.util.Log.e("FukuFloating", "Could not install floating-window runtime", error);
            return false;
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
