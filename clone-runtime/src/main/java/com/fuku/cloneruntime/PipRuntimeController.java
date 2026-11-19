package com.fuku.cloneruntime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Adds a small, draggable system-overlay controller for a running clone.
 *
 * This is an overlay control only: it does not resize, re-parent, or render the
 * cloned Activity inside the overlay. The cloned app remains a normal Android task.
 */
public final class PipRuntimeController {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static final Map<Activity, View> windowBars = new WeakHashMap<>();
    private static final Map<Activity, WindowManager.LayoutParams> barParams = new WeakHashMap<>();
    private static final Map<Activity, Boolean> permissionPromptShown = new WeakHashMap<>();

    private PipRuntimeController() { }

    public static void onActivityCreated(Activity activity) {
        if (!isEligibleActivity(activity)) return;
        currentActivity = new WeakReference<>(activity);
        installOrRequestOverlay(activity);
    }

    public static void onActivityResumed(Activity activity) {
        if (!isEligibleActivity(activity)) return;
        currentActivity = new WeakReference<>(activity);
        installOrRequestOverlay(activity);
    }

    private static boolean isEligibleActivity(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && !activity.getClass().getName().equals(
                        "com.fuku.cloneruntime.RuntimeEntryActivity");
    }

    public static void onActivityDestroyed(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) currentActivity = new WeakReference<>(null);
        removeFloatingControls(activity);
        permissionPromptShown.remove(activity);
    }

    private static void installOrRequestOverlay(Activity activity) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(activity)) {
            requestOverlayPermissionOnce(activity);
            return;
        }
        installFloatingControls(activity);
    }

    private static void requestOverlayPermissionOnce(Activity activity) {
        if (Boolean.TRUE.equals(permissionPromptShown.get(activity))) return;
        permissionPromptShown.put(activity, true);
        if (activity.isFinishing() || activity.isDestroyed()) return;

        new AlertDialog.Builder(activity)
                .setTitle("Enable Fuku floating controls?")
                .setMessage("Allow Fuku to display a small draggable control bar over other apps. The game itself will continue running normally; this does not turn it into a resizable floating game window.")
                .setNegativeButton("Not now", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Enable", (dialog, which) -> {
                    try {
                        Intent settings = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(settings);
                    } catch (RuntimeException ignored) { }
                })
                .setOnCancelListener(dialog -> { })
                .show();
    }

    private static void installFloatingControls(Activity activity) {
        if (windowBars.containsKey(activity)) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(activity)) return;

        WindowManager manager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
        if (manager == null) return;

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 8), 0, dp(activity, 6), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(25, 28, 34));
        background.setCornerRadius(dp(activity, 10));
        background.setStroke(dp(activity, 1), Color.rgb(67, 74, 86));
        bar.setBackground(background);
        bar.setElevation(dp(activity, 12));

        String titleText = "Fuku";
        try {
            CharSequence appLabel = activity.getPackageManager()
                    .getApplicationLabel(activity.getApplicationInfo());
            if (appLabel != null && appLabel.length() > 0) titleText = appLabel.toString();
        } catch (RuntimeException ignored) { }
        final String displayTitle = titleText;

        TextView title = control(activity, displayTitle, true);
        TextView minimize = control(activity, "−", false);
        TextView close = control(activity, "×", false);
        minimize.setContentDescription("Hide cloned app and keep floating controls visible");
        close.setContentDescription("Close cloned app");
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        bar.addView(minimize, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 44)));
        bar.addView(close, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 44)));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(activity, 260), dp(activity, 46), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(activity, 12);
        params.y = dp(activity, 48);

        try {
            manager.addView(bar, params);
            windowBars.put(activity, bar);
            barParams.put(activity, params);
        } catch (RuntimeException error) {
            return;
        }

        title.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean dragged;

            @Override public boolean onTouch(View view, MotionEvent event) {
                WindowManager.LayoutParams current = barParams.get(activity);
                if (current == null) return false;
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = current.x;
                    startY = current.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragged = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    if (Math.abs(event.getRawX() - downX) > dp(activity, 6)
                            || Math.abs(event.getRawY() - downY) > dp(activity, 6)) dragged = true;
                    current.x = Math.max(0, startX + Math.round(event.getRawX() - downX));
                    current.y = Math.max(0, startY + Math.round(event.getRawY() - downY));
                    try { manager.updateViewLayout(bar, current); }
                    catch (RuntimeException ignored) { }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!dragged) bringCloneToFront(activity);
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });

        minimize.setOnClickListener(v -> activity.moveTaskToBack(true));
        close.setOnClickListener(v -> {
            removeFloatingControls(activity);
            activity.finish();
        });
    }

    private static void bringCloneToFront(Activity activity) {
        try {
            Intent bringBack = new Intent(activity, activity.getClass());
            bringBack.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(bringBack);
        } catch (RuntimeException ignored) { }
    }

    private static TextView control(Activity activity, String text, boolean isTitle) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(isTitle ? 13 : 21);
        view.setTypeface(Typeface.DEFAULT, isTitle ? Typeface.BOLD : Typeface.NORMAL);
        view.setGravity(isTitle ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (isTitle) {
            view.setPadding(dp(activity, 8), 0, dp(activity, 4), 0);
        } else {
            view.setClickable(true);
            view.setFocusable(true);
            GradientDrawable buttonBackground = new GradientDrawable();
            buttonBackground.setColor(Color.TRANSPARENT);
            buttonBackground.setCornerRadius(dp(activity, 7));
            view.setBackground(buttonBackground);
        }
        return view;
    }

    private static void removeFloatingControls(Activity activity) {
        View bar = windowBars.remove(activity);
        barParams.remove(activity);
        if (bar == null) return;
        try {
            WindowManager manager = (WindowManager) activity.getSystemService(Activity.WINDOW_SERVICE);
            if (manager != null) manager.removeView(bar);
        } catch (RuntimeException ignored) { }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
