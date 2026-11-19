package com.fuku.cloneruntime;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class RuntimeEntryActivity extends Activity {
    private static final String META_LAUNCHER = "com.fuku.cloneruntime.ORIGINAL_LAUNCHER";
    private String launchError;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getPreferences(MODE_PRIVATE).getBoolean("complete", false)) {
            launchOriginalApp();
            return;
        }
        showSetup();
    }

    private void showSetup() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF10131A);

        TextView title = new TextView(this);
        title.setText(getPackageManager().getApplicationLabel(getApplicationInfo()));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(this);
        description.setText("This cloned app requests its own Android freeform window when you open it. The floating launch happens here inside the clone, not from the Fuku cloner.");
        description.setTextColor(0xFFCDD3DF);
        description.setTextSize(15);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(14);
        root.addView(description, descriptionParams);

        Button launchButton = new Button(this);
        launchButton.setText("Open cloned app in floating window");
        LinearLayout.LayoutParams launchParams = new LinearLayout.LayoutParams(-1, -2);
        launchParams.topMargin = dp(18);
        root.addView(launchButton, launchParams);
        launchButton.setOnClickListener(v -> launchOriginalApp());

        TextView note = new TextView(this);
        note.setText("If your Android device supports freeform windows, the cloned app opens in a movable, resizable window. If the device blocks freeform mode, Android will open it fullscreen instead.");
        note.setTextColor(0xFFFFD18A);
        note.setTextSize(12);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(20);
        root.addView(note, noteParams);

        if (launchError != null && !launchError.isEmpty()) {
            TextView error = new TextView(this);
            error.setText("The cloned app could not start: " + launchError);
            error.setTextColor(0xFFFF8A80);
            error.setTextSize(13);
            LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(-1, -2);
            errorParams.topMargin = dp(16);
            root.addView(error, errorParams);
        }
        setContentView(root);
    }

    private void launchOriginalApp() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    getPackageName(), PackageManager.GET_META_DATA);
            String launcherClass = info.metaData == null ? null : info.metaData.getString(META_LAUNCHER);
            if (launcherClass == null || launcherClass.isEmpty()) {
                throw new IllegalStateException("Original game launcher was not recorded in the clone.");
            }
            Intent game = new Intent().setComponent(new ComponentName(getPackageName(), launcherClass));
            game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 24) {
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                int width = Math.min(metrics.widthPixels, Math.max(dp(280), Math.round(metrics.widthPixels * 0.72f)));
                int height = Math.min(metrics.heightPixels, Math.max(dp(240), Math.round(metrics.heightPixels * 0.72f)));
                int left = Math.max(0, (metrics.widthPixels - width) / 2);
                int top = Math.max(0, (metrics.heightPixels - height) / 3);
                options.setLaunchBounds(new Rect(left, top, left + width, top + height));
            }
            startActivity(game, options.toBundle());
            getPreferences(MODE_PRIVATE).edit().putBoolean("complete", true).apply();
            finish();
        } catch (Exception error) {
            launchError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            showSetup();
            Toast.makeText(this, "Could not start the cloned app: " + launchError,
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
