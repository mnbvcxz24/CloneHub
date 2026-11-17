package com.fuku.cloneruntime;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Rational;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Clone-side runtime. It is loaded from the injected secondary DEX and
 * initialized by PipRuntimeInitializer in the cloned package.
 */
public final class PipRuntimeController {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static final Map<Activity, View> windowBars = new WeakHashMap<>();

    private PipRuntimeController() { }

    /**
     * Apply the floating layout as soon as the source activity has completed
     * onCreate(), before its first normal resumed frame whenever possible.
     */
    public static void onActivityCreated(Activity activity) {
        if (!isEligibleActivity(activity)) return;
        currentActivity = new WeakReference<>(activity);
        applyFloatingWindow(activity);
        installFloatingControls(activity);
    }

    public static void onActivityResumed(Activity activity) {
        if (!isEligibleActivity(activity)) return;
        currentActivity = new WeakReference<>(activity);
        applyPictureInPictureParams(activity);
        applyFloatingWindow(activity);
        installFloatingControls(activity);
    }

    private static boolean isEligibleActivity(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && !activity.getClass().getName().equals(
                        "com.fuku.cloneruntime.RuntimeEntryActivity");
    }

    private static void applyFloatingWindow(Activity activity) {
        if (!isEligibleActivity(activity)) return;
        Window window = activity.getWindow();
        if (window == null) return;

        float density = activity.getResources().getDisplayMetrics().density;
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int width = dp(activity, Math.min(480, Math.max(280,
                (int) (screenWidth / density) - 32)));
        int height = dp(activity, Math.max(240,
                (int) (screenHeight / density) - 72));

        // Apply to ordinary application activities too; most launcher
        // activities do not declare windowIsFloating in their theme.
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = Math.min(width, Math.max(1, screenWidth - dp(activity, 16)));
        params.height = Math.min(height, Math.max(1, screenHeight - dp(activity, 48)));
        params.gravity = Gravity.TOP | Gravity.START;
        if (params.x == 0 && params.y == 0) {
            params.x = dp(activity, 16);
            params.y = dp(activity, 48);
        }
        window.setAttributes(params);
    }

    public static void onActivityDestroyed(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) currentActivity = new WeakReference<>(null);
        View bar = windowBars.remove(activity);
        if (bar != null) {
            try {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                decor.removeView(bar);
            } catch (RuntimeException ignored) { }
        }
    }

    public static boolean enterCurrentActivity() {
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || Build.VERSION.SDK_INT < 26) return false;
        try {
            return activity.enterPictureInPictureMode(buildParams(activity));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static void installFloatingControls(Activity activity) {
        Window window = activity.getWindow();
        if (windowBars.containsKey(activity)) return;
        View decorView = window.getDecorView();
        if (!(decorView instanceof ViewGroup)) return;
        ViewGroup decor = (ViewGroup) decorView;

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 12), 0, dp(activity, 8), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(32, 36, 43));
        background.setCornerRadius(dp(activity, 8));
        bar.setBackground(background);

        TextView title = new TextView(activity);
        title.setText("Fuku Floating Window");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(activity, 40), 1));

        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextColor(Color.WHITE);
        close.setTextSize(22);
        close.setGravity(Gravity.CENTER);
        bar.addView(close, new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 40)));
        close.setOnClickListener(v -> activity.finish());

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 40), Gravity.TOP);
        overlay.addView(bar, barParams);
        overlay.setClickable(false);
        bar.setClickable(true);
        title.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                WindowManager.LayoutParams current = window.getAttributes();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = current.x;
                    startY = current.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    current.x = startX + Math.round(event.getRawX() - downX);
                    current.y = startY + Math.round(event.getRawY() - downY);
                    window.setAttributes(current);
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        decor.addView(overlay, overlayParams);
        overlay.bringChildToFront(bar);
        windowBars.put(activity, overlay);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void applyPictureInPictureParams(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) return;
        try { activity.setPictureInPictureParams(buildParams(activity)); }
        catch (RuntimeException ignored) { }
    }

    private static PictureInPictureParams buildParams(Activity activity) {
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            int longSide = Math.max(width, height);
            width = longSide;
            height = Math.min(width, height);
        } else {
            int longSide = Math.max(width, height);
            height = longSide;
            width = Math.min(width, height);
        }
        float ratio = height == 0 ? 16f / 9f : (float) width / height;
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
