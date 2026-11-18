package com.fuku.cloneruntime;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Clone-side system floating-window runtime. */
public final class PipRuntimeController {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static final Map<Activity, View> windowBars = new WeakHashMap<>();
    private static final Map<Activity, WindowManager.LayoutParams> barParams = new WeakHashMap<>();
    private static final Map<Activity, int[]> savedBounds = new WeakHashMap<>();
    private static final Map<Activity, Boolean> maximized = new WeakHashMap<>();

    private PipRuntimeController() { }

    public static void onActivityCreated(Activity activity) {
        if (!isEligibleActivity(activity)) return;
        currentActivity = new WeakReference<>(activity);
        installFloatingControls(activity);
    }

    public static void onActivityResumed(Activity activity) {
        if (!isEligibleActivity(activity)) return;
        currentActivity = new WeakReference<>(activity);
        applyPictureInPictureParams(activity);
        installFloatingControls(activity);
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
        savedBounds.remove(activity);
        maximized.remove(activity);
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
        background.setColor(Color.rgb(32, 36, 43));
        background.setCornerRadius(dp(activity, 10));
        bar.setBackground(background);
        bar.setElevation(dp(activity, 10));

        TextView title = control(activity, "Fuku", 1);
        TextView minimize = control(activity, "—", 0);
        TextView maximize = control(activity, "□", 0);
        TextView close = control(activity, "×", 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        bar.addView(minimize, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 44)));
        bar.addView(maximize, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 44)));
        bar.addView(close, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 44)));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(activity, 300), dp(activity, 44), type,
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
                    current.x = startX + Math.round(event.getRawX() - downX);
                    current.y = startY + Math.round(event.getRawY() - downY);
                    try { manager.updateViewLayout(bar, current); }
                    catch (RuntimeException ignored) { }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!dragged) {
                        title.setText("Fuku");
                        Intent bringBack = new Intent(activity, activity.getClass());
                        bringBack.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        try { activity.startActivity(bringBack); }
                        catch (RuntimeException ignored) { }
                    }
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });

        minimize.setOnClickListener(v -> {
            title.setText("Fuku (tap to restore)");
            activity.moveTaskToBack(true);
        });
        maximize.setOnClickListener(v -> toggleMaximize(activity));
        close.setOnClickListener(v -> {
            removeFloatingControls(activity);
            activity.finish();
        });
    }

    private static TextView control(Activity activity, String text, int weight) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(weight == 1 ? 13 : 20);
        view.setTypeface(Typeface.DEFAULT, weight == 1 ? Typeface.BOLD : Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        return view;
    }

    private static void toggleMaximize(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams attrs = window.getAttributes();
        if (!Boolean.TRUE.equals(maximized.get(activity))) {
            savedBounds.put(activity, new int[] { attrs.width, attrs.height, attrs.x, attrs.y });
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            attrs.width = metrics.widthPixels;
            attrs.height = metrics.heightPixels;
            attrs.x = 0;
            attrs.y = 0;
            maximized.put(activity, true);
        } else {
            int[] old = savedBounds.get(activity);
            if (old != null) {
                attrs.width = old[0];
                attrs.height = old[1];
                attrs.x = old[2];
                attrs.y = old[3];
            }
            maximized.put(activity, false);
        }
        try { window.setAttributes(attrs); } catch (RuntimeException ignored) { }
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
