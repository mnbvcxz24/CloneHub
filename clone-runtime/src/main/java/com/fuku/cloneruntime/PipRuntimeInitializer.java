package com.fuku.cloneruntime;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/** Registers clone-side window and PiP behavior without replacing the source Application. */
public final class PipRuntimeInitializer extends ContentProvider {
    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        Application application = (Application) getContext().getApplicationContext();
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, android.os.Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) {
                PipRuntimeController.onActivityResumed(activity);
            }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) {
                PipRuntimeController.onActivityDestroyed(activity);
            }
        });
        return true;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
