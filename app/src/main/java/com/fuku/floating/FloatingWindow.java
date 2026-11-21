package com.fuku.floating;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Adds an in-window title bar and resize handle to activities whose manifest theme
 * is based on a floating Dialog theme. No SYSTEM_ALERT_WINDOW or external window
 * manager is used for the app window; minimize uses a separate overlay bubble.
 */
public final class FloatingWindow implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "fuku_floating";
    private static final String BOUNDS_PREFIX = "bounds.";
    private static final Object LOCK = new Object();
    private static final Set<Activity> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, View> DECORATIONS = new WeakHashMap<>();
    private static boolean callbacksRegistered;

    private FloatingWindow() {}

    public static void install(Application app) {
        if (app == null) return;
        synchronized (LOCK) {
            if (callbacksRegistered) return;
            callbacksRegistered = true;
        }
        app.registerActivityLifecycleCallbacks(new FloatingWindow());
    }

    /**
     * Restore the app's real theme before the Activity's own onCreate runs.
     * AppCompat validates its theme during onCreate, so onActivityCreated/Started
     * are too late. This callback exists from API 29; older devices are not
     * given the dialog theme during clone building.
     */
    @Override public void onActivityPreCreated(Activity activity, Bundle state) {
        // Apply non-modal behavior before Android attaches the Activity window.
        // Setting this only in onActivityStarted can be too late on some builds:
        // the initial window may already have been registered as touch-modal.
        try {
            Window window = activity.getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0f;
            window.setAttributes(params);
        } catch (Throwable error) {
            android.util.Log.e("FukuFloating", "Could not configure non-modal window before creation", error);
        }

        try {
            android.content.pm.ActivityInfo info = activity.getPackageManager()
                    .getActivityInfo(activity.getComponentName(),
                            android.content.pm.PackageManager.GET_META_DATA);
            Bundle metadata = info.metaData;
            if (metadata == null) return;
            int originalTheme = metadata.getInt("com.fuku.floating.ORIGINAL_THEME", 0);
            if (originalTheme != 0) {
                activity.setTheme(originalTheme);
                android.util.Log.d("FukuFloating", "Restored original activity theme: "
                        + activity.getComponentName().flattenToShortString());
            }
        } catch (Throwable error) {
            android.util.Log.e("FukuFloating", "Could not restore original activity theme", error);
        }
    }

    @Override public void onActivityStarted(final Activity activity) {
        final View decor = activity.getWindow().getDecorView();
        decor.post(new Runnable() {
            @Override public void run() {
                if (!activity.isFinishing() && !activity.isDestroyed()) setup(activity);
            }
        });
    }

    @Override public void onActivityStopped(Activity activity) { saveBounds(activity); }
    @Override public void onActivityPaused(Activity activity) { saveBounds(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) { FloatingBubble.hide(activity.getTaskId()); }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    @Override public void onActivityDestroyed(Activity activity) {
        if (activity.isFinishing()) FloatingBubble.hide(activity.getTaskId());
        synchronized (LOCK) {
            INSTALLED.remove(activity);
            DECORATIONS.remove(activity);
        }
    }

    private void setup(final Activity activity) {
        synchronized (LOCK) {
            if (INSTALLED.contains(activity)) return;
        }

        Window window = activity.getWindow();
        View contentView = window.findViewById(android.R.id.content);
        final FrameLayout content = ensureFrameLayout(activity, contentView);
        if (content == null) return;
        if (content.getTag() != null && content.getTag() instanceof String
                && "fuku-floating-decorated".equals(content.getTag())) return;

        final DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        final float density = metrics.density;
        int[] bounds = loadBounds(activity);
        if (bounds == null) {
            int width = Math.round(metrics.widthPixels * 0.82f);
            int height = Math.round(metrics.heightPixels * 0.78f);
            bounds = new int[] {
                    Math.max(0, (metrics.widthPixels - width) / 2),
                    Math.max(0, (metrics.heightPixels - height) / 2),
                    width, height
            };
        }

        WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = bounds[0];
        params.y = bounds[1];
        params.width = clamp(bounds[2], Math.round(260 * density), metrics.widthPixels);
        params.height = clamp(bounds[3], Math.round(180 * density), metrics.heightPixels);
        params.dimAmount = 0f;
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.setAttributes(params);

        final int barHeight = Math.round(38 * density);
        final View appContent = content.getChildCount() > 0 ? content.getChildAt(0) : null;
        if (appContent != null) {
            FrameLayout.LayoutParams appParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.TOP);
            appParams.topMargin = barHeight;
            appContent.setLayoutParams(appParams);
        }

        final LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF202124);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setElevation(8 * density);
        final FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.TOP);
        bar.setLayoutParams(barParams);

        final TextView title = new TextView(activity);
        title.setText(activity.getApplicationInfo().loadLabel(activity.getPackageManager()));
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setPadding(Math.round(12 * density), 0, 0, 0);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        final boolean[] maximized = {false};
        final int[][] previous = {null};
        final View[] resizeHandle = {null};

        // Send the whole clone task to the background and leave a draggable overlay bubble.
        final TextView minimizeButton = button(activity, "–", density, new View.OnClickListener() {
            @Override public void onClick(View view) { FloatingBubble.minimize(activity); }
        });
        bar.addView(minimizeButton);


        final TextView maximizeButton = button(activity, "□", density, new View.OnClickListener() {
            @Override public void onClick(View view) {
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                if (!maximized[0]) {
                    previous[0] = new int[] {p.x, p.y, p.width, p.height};
                    p.x = 0;
                    p.y = 0;
                    p.width = metrics.widthPixels;
                    p.height = metrics.heightPixels;
                } else if (previous[0] != null) {
                    p.x = previous[0][0];
                    p.y = previous[0][1];
                    p.width = previous[0][2];
                    p.height = previous[0][3];
                }
                maximized[0] = !maximized[0];
                activity.getWindow().setAttributes(p);
            }
        });
        maximizeButtonRef[0] = maximizeButton;
        bar.addView(maximizeButton);

        final TextView closeButton = button(activity, "×", density, new View.OnClickListener() {
            @Override public void onClick(View view) { activity.finish(); }
        });
        closeButtonRef[0] = closeButton;
        bar.addView(closeButton);

        bar.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = p.x;
                        startY = p.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.x = startX + Math.round(event.getRawX() - downX);
                        p.y = startY + Math.round(event.getRawY() - downY);
                        p.x = clamp(p.x, -p.width + Math.round(72 * density),
                                Math.max(0, metrics.widthPixels - Math.round(72 * density)));
                        p.y = clamp(p.y, 0, Math.max(0, metrics.heightPixels - Math.round(36 * density)));
                        activity.getWindow().setAttributes(p);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        });

        final int handleSize = Math.round(30 * density);
        View handle = new View(activity);
        resizeHandle[0] = handle;
        handle.setBackgroundColor(0x66808080);
        handle.setContentDescription("Resize floating window");
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(
                handleSize, handleSize, Gravity.BOTTOM | Gravity.END);
        handle.setLayoutParams(handleParams);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startWidth, startHeight;
            @Override public boolean onTouch(View view, MotionEvent event) {
                WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startWidth = p.width;
                        startHeight = p.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.width = clamp(startWidth + Math.round(event.getRawX() - downX),
                                Math.round(260 * density), metrics.widthPixels);
                        p.height = clamp(startHeight + Math.round(event.getRawY() - downY),
                                Math.round(180 * density), metrics.heightPixels);
                        activity.getWindow().setAttributes(p);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.performClick();
                        return true;
                    default:
                        return true;
                }
            }
        });

        content.addView(bar);
        content.addView(handle);
        content.setTag("fuku-floating-decorated");
        synchronized (LOCK) {
            INSTALLED.add(activity);
            DECORATIONS.put(activity, bar);
        }
    }

    private static FrameLayout ensureFrameLayout(Activity activity, View contentView) {
        if (contentView instanceof FrameLayout) return (FrameLayout) contentView;
        if (contentView == null) {
            android.util.Log.w("FukuFloating", "Cannot install floating controls: android.R.id.content is missing");
            return null;
        }
        if (!(contentView.getParent() instanceof ViewGroup)) {
            android.util.Log.w("FukuFloating", "Cannot wrap android.R.id.content: it has no ViewGroup parent ("
                    + contentView.getClass().getName() + ")");
            return null;
        }

        ViewGroup parent = (ViewGroup) contentView.getParent();
        int index = parent.indexOfChild(contentView);
        ViewGroup.LayoutParams oldParams = contentView.getLayoutParams();
        parent.removeView(contentView);
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setId(android.R.id.content);
        if (contentView.getId() == android.R.id.content) contentView.setId(View.NO_ID);
        parent.addView(wrapper, index, oldParams);
        wrapper.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        android.util.Log.w("FukuFloating", "Wrapped non-FrameLayout android.R.id.content ("
                + contentView.getClass().getName() + ") in FrameLayout");
        return wrapper;
    }

    private static TextView button(Context context, String label, float density,
                                   View.OnClickListener listener) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18);
        text.setGravity(Gravity.CENTER);
        text.setLayoutParams(new LinearLayout.LayoutParams(Math.round(42 * density),
                ViewGroup.LayoutParams.MATCH_PARENT));
        text.setOnClickListener(listener);
        return text;
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static String boundsKey(Activity activity, String suffix) {
        return BOUNDS_PREFIX + activity.getClass().getName() + "." + suffix;
    }

    private static void saveBounds(Activity activity) {
        try {
            WindowManager.LayoutParams p = activity.getWindow().getAttributes();
            if (p.width <= 0 || p.height <= 0) return;
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(boundsKey(activity, "x"), p.x)
                    .putInt(boundsKey(activity, "y"), p.y)
                    .putInt(boundsKey(activity, "w"), p.width)
                    .putInt(boundsKey(activity, "h"), p.height)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private static int[] loadBounds(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int width = prefs.getInt(boundsKey(activity, "w"), 0);
        int height = prefs.getInt(boundsKey(activity, "h"), 0);
        if (width <= 0 || height <= 0) return null;
        return new int[] {
                prefs.getInt(boundsKey(activity, "x"), 0),
                prefs.getInt(boundsKey(activity, "y"), 0),
                width, height
        };
    }
}
