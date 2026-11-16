package com.fuku.cloneruntime;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Rational;

import java.lang.ref.WeakReference;

/**
 * Tracks the currently resumed app Activity and prepares native Android PiP.
 * Android 12+ can auto-enter PiP when the user leaves the app. On older
 * releases the in-clone floating control requests PiP explicitly.
 */
final class PipRuntimeController {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);

    private PipRuntimeController() { }

    static void onActivityResumed(Activity activity) {
        if (activity == null || activity instanceof RuntimeEntryActivity
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        currentActivity = new WeakReference<>(activity);
        applyPictureInPictureParams(activity);
    }

    static void onActivityDestroyed(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) currentActivity = new WeakReference<>(null);
    }

    static boolean enterCurrentActivity() {
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || Build.VERSION.SDK_INT < 26) {
            return false;
        }
        try {
            PictureInPictureParams params = buildParams(activity);
            return activity.enterPictureInPictureMode(params);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static void applyPictureInPictureParams(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            activity.setPictureInPictureParams(buildParams(activity));
        } catch (RuntimeException ignored) {
            // Some activities or device builds reject PiP parameters. Keep app launch safe.
        }
    }

    private static PictureInPictureParams buildParams(Activity activity) {
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        if (activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
            int swap = Math.max(width, height);
            int shortSide = Math.min(width, height);
            width = swap;
            height = shortSide;
        } else {
            int longSide = Math.max(width, height);
            int shortSide = Math.min(width, height);
            width = shortSide;
            height = longSide;
        }
        float ratio = height == 0 ? (16f / 9f) : ((float) width / (float) height);
        ratio = Math.max(1f / 2.39f, Math.min(2.39f, ratio));

        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(Math.round(ratio * 1000f), 1000));
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(true);
            builder.setSeamlessResizeEnabled(true);
        }
        return builder.build();
    }
}
