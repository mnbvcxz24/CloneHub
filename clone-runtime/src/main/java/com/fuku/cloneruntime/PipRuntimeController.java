package com.fuku.cloneruntime;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Experimental in-Activity floating title bar.
 *
 * Unlike the previous TYPE_APPLICATION_OVERLAY controller, this bar is inserted
 * into the guest Activity's own content hierarchy. It therefore shares the game
 * surface/window. Moving the Activity task itself still depends on Android/OEM
 * freeform support; ordinary apps cannot force task bounds on stock Android.
 */
public final class PipRuntimeController {
    private static final Map<Activity, View> decoratedActivities = new WeakHashMap<>();
    private static final Map<Activity, WindowManager.LayoutParams> restoreBounds = new WeakHashMap<>();

    private PipRuntimeController() { }

    public static void onActivityCreated(Activity activity) {
        decorateActivity(activity);
    }

    public static void onActivityResumed(Activity activity) {
        decorateActivity(activity);
    }

    public static void onActivityDestroyed(Activity activity) {
        decoratedActivities.remove(activity);
        restoreBounds.remove(activity);
    }

    private static void decorateActivity(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getClass().getName().equals("com.fuku.cloneruntime.RuntimeEntryActivity")
                || decoratedActivities.containsKey(activity)) return;

        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(13, 16, 22));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 8), 0, dp(activity, 5), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(27, 31, 40));
        background.setCornerRadius(dp(activity, 7));
        background.setStroke(dp(activity, 1), Color.rgb(76, 86, 103));
        bar.setBackground(background);
        bar.setElevation(dp(activity, 5));

        String titleText = "Fuku";
        try {
            CharSequence label = activity.getPackageManager()
                    .getApplicationLabel(activity.getApplicationInfo());
            if (label != null && label.length() > 0) titleText = label.toString();
        } catch (RuntimeException ignored) { }

        TextView title = control(activity, titleText, true);
        TextView minimize = control(activity, "−", false);
        TextView maximize = control(activity, "□", false);
        TextView close = control(activity, "×", false);
        minimize.setContentDescription("Minimize game window");
        maximize.setContentDescription("Maximize or restore game window");
        close.setContentDescription("Close game");

        bar.addView(title, new LinearLayout.LayoutParams(0, dp(activity, 42), 1));
        bar.addView(minimize, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)));
        bar.addView(maximize, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)));
        bar.addView(close, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)));

        FrameLayout gameSurface = new FrameLayout(activity);
        LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        shell.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44)));
        shell.addView(gameSurface, gameParams);

        // Preserve the guest's existing content view and its layout parameters.
        View[] originalChildren = new View[content.getChildCount()];
        ViewGroup.LayoutParams[] originalParams = new ViewGroup.LayoutParams[content.getChildCount()];
        for (int i = 0; i < content.getChildCount(); i++) {
            originalChildren[i] = content.getChildAt(i);
            originalParams[i] = originalChildren[i].getLayoutParams();
        }
        content.removeAllViews();
        for (int i = 0; i < originalChildren.length; i++) {
            View child = originalChildren[i];
            ViewGroup.LayoutParams params = originalParams[i];
            if (params == null) params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            gameSurface.addView(child, params);
        }
        content.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decoratedActivities.put(activity, shell);

        title.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                    startX = p.x;
                    startY = p.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    moved = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (Math.abs(dx) > dp(activity, 4) || Math.abs(dy) > dp(activity, 4)) moved = true;
                    if (moved) {
                        WindowManager.LayoutParams p = activity.getWindow().getAttributes();
                        p.x = startX + Math.round(dx);
                        p.y = startY + Math.round(dy);
                        activity.getWindow().setAttributes(p);
                    }
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });

        minimize.setOnClickListener(v -> activity.moveTaskToBack(true));
        maximize.setOnClickListener(v -> toggleMaximize(activity));
        close.setOnClickListener(v -> activity.finish());
    }

    private static void toggleMaximize(Activity activity) {
        WindowManager.LayoutParams current = activity.getWindow().getAttributes();
        if (restoreBounds.containsKey(activity)) {
            WindowManager.LayoutParams restore = restoreBounds.remove(activity);
            activity.getWindow().setAttributes(restore);
        } else {
            WindowManager.LayoutParams saved = new WindowManager.LayoutParams();
            saved.copyFrom(current);
            restoreBounds.put(activity, saved);
            current.width = ViewGroup.LayoutParams.MATCH_PARENT;
            current.height = ViewGroup.LayoutParams.MATCH_PARENT;
            current.x = 0;
            current.y = 0;
            activity.getWindow().setAttributes(current);
        }
    }

    private static TextView control(Activity activity, String text, boolean isTitle) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(isTitle ? 13 : 20);
        view.setTypeface(Typeface.DEFAULT, isTitle ? Typeface.BOLD : Typeface.NORMAL);
        view.setGravity(isTitle ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (isTitle) view.setPadding(dp(activity, 8), 0, dp(activity, 4), 0);
        else {
            view.setClickable(true);
            view.setFocusable(true);
            GradientDrawable button = new GradientDrawable();
            button.setColor(Color.TRANSPARENT);
            button.setCornerRadius(dp(activity, 6));
            view.setBackground(button);
        }
        return view;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
