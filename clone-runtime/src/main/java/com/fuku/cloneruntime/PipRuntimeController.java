package com.fuku.cloneruntime;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Clone-side controls for entering the Android system Picture-in-Picture window.
 * The game remains the real cloned Activity; no screen capture or mirroring is used.
 */
public final class PipRuntimeController {
    private static final Map<Activity, View> decoratedActivities = new WeakHashMap<>();
    private static final Map<Activity, View> controlBars = new WeakHashMap<>();

    private PipRuntimeController() { }

    public static void onActivityCreated(Activity activity) {
        decorateActivity(activity);
    }

    public static void onActivityResumed(Activity activity) {
        decorateActivity(activity);
        syncPipUi(activity);
    }

    public static void onActivityDestroyed(Activity activity) {
        decoratedActivities.remove(activity);
        controlBars.remove(activity);
    }

    private static void syncPipUi(Activity activity) {
        View bar = controlBars.get(activity);
        if (bar != null) {
            bar.setVisibility(activity.isInPictureInPictureMode() ? View.GONE : View.VISIBLE);
        }
    }

    private static void decorateActivity(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getClass().getName().equals("com.fuku.cloneruntime.RuntimeEntryActivity")
                || decoratedActivities.containsKey(activity)) return;

        FrameLayout content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.BLACK);

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
        TextView pip = control(activity, "▣", false);
        TextView close = control(activity, "×", false);
        pip.setContentDescription("Enter picture-in-picture");
        close.setContentDescription("Close game");

        bar.addView(title, new LinearLayout.LayoutParams(0, dp(activity, 42), 1));
        bar.addView(pip, new LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 42)));
        bar.addView(close, new LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)));

        FrameLayout gameSurface = new FrameLayout(activity);
        LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        shell.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44)));
        shell.addView(gameSurface, gameParams);

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
        controlBars.put(activity, bar);

        pip.setOnClickListener(v -> enterPip(activity, bar));
        close.setOnClickListener(v -> activity.finish());
    }

    private static void enterPip(Activity activity, View bar) {
        if (android.os.Build.VERSION.SDK_INT < 26
                || !activity.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(activity, "Picture-in-Picture is not supported on this device.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            View content = activity.findViewById(android.R.id.content);
            int width = content == null ? 16 : Math.max(1, content.getWidth());
            int height = content == null ? 9 : Math.max(1, content.getHeight());
            float ratio = (float) width / (float) height;
            ratio = Math.max(0.5f, Math.min(2.35f, ratio));
            int numerator = Math.max(1, Math.round(ratio * 1000f));
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(numerator, 1000))
                    .build();
            bar.setVisibility(View.GONE);
            boolean entered = activity.enterPictureInPictureMode(params);
            if (!entered) {
                bar.setVisibility(View.VISIBLE);
                Toast.makeText(activity, "Android could not enter Picture-in-Picture.",
                        Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException error) {
            bar.setVisibility(View.VISIBLE);
            String message = error.getMessage();
            Toast.makeText(activity,
                    "Could not enter Picture-in-Picture"
                            + (message == null ? "." : ": " + message),
                    Toast.LENGTH_LONG).show();
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
