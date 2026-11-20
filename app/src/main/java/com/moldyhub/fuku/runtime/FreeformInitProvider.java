package com.moldyhub.fuku.runtime;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Clone-owned bootstrap that requests freeform before the first screen is shown. */
public final class FreeformInitProvider extends ContentProvider {
    private static final String EXTRA_RELAUNCHED =
            "com.moldyhub.fuku.runtime.FREEFORM_RELAUNCHED";
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);
    private static final AtomicInteger ACTIVE_ACTIVITIES = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Application.ActivityLifecycleCallbacks callbacks =
            new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            ACTIVE_ACTIVITIES.incrementAndGet();
            Intent incoming = activity.getIntent();
            if (incoming != null && incoming.getBooleanExtra(EXTRA_RELAUNCHED, false)) {
                ATTEMPTED.set(true);
                return;
            }
            if (!ATTEMPTED.compareAndSet(false, true)) return;

            try {
                Intent relaunch = incoming == null ? new Intent() : new Intent(incoming);
                relaunch.setComponent(activity.getComponentName());
                relaunch.setPackage(null);
                relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                relaunch.putExtra(EXTRA_RELAUNCHED, true);

                ActivityOptions options = makeFreeformOptions(activity);
                activity.startActivity(relaunch, options.toBundle());
                mainHandler.postDelayed(activity::finish, 180L);
            } catch (Throwable blocked) {
                android.util.Log.w("FukuFreeform",
                        "Freeform launch was rejected; continuing normally", blocked);
            }
        }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) {
            if (ACTIVE_ACTIVITIES.decrementAndGet() <= 0) {
                ACTIVE_ACTIVITIES.set(0);
                ATTEMPTED.set(false);
            }
        }
    };

    private ActivityOptions makeFreeformOptions(Activity activity) throws Exception {
        HiddenApiBypass.addHiddenApiExemptions("");
        ActivityOptions options = ActivityOptions.makeBasic();
        String methodName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? "setLaunchWindowingMode" : "setLaunchStackId";
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 5 : 2;
        Method method = ActivityOptions.class.getDeclaredMethod(methodName, int.class);
        method.setAccessible(true);
        method.invoke(options, mode);

        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.max(320, (int) (metrics.widthPixels * 0.72f));
        int height = Math.max(480, (int) (metrics.heightPixels * 0.72f));
        int left = Math.max(0, (metrics.widthPixels - width) / 2);
        int top = Math.max(0, (metrics.heightPixels - height) / 2);
        options.setLaunchBounds(new Rect(left, top, left + width, top + height));
        return options;
    }

    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        Application app = (Application) getContext().getApplicationContext();
        app.registerActivityLifecycleCallbacks(callbacks);
        return true;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}