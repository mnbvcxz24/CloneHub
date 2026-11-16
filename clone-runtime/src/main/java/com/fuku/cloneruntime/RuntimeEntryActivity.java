package com.fuku.cloneruntime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class RuntimeEntryActivity extends Activity {
    private static final String META_LAUNCHER = "com.fuku.cloneruntime.ORIGINAL_LAUNCHER";
    private Button permissionButton;
    private Button launchButton;
    private String launchError;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getPreferences(MODE_PRIVATE).getBoolean("complete", false)
                && (Build.VERSION.SDK_INT >= 31 || Settings.canDrawOverlays(this))) {
            launchOriginalApp();
            return;
        }
        showSetup();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionState();
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
        description.setText(Build.VERSION.SDK_INT >= 31
                ? "This cloned app uses Android's native Picture-in-Picture mode. Continue, then swipe Home while playing to enter the floating game window. Overlay access is optional for the extra Fuku PiP control."
                : "This cloned app uses Android's native Picture-in-Picture mode. Allow display-over-other-apps access for the floating Fuku control, then tap it while playing to enter the floating game window.");
        description.setTextColor(0xFFCDD3DF);
        description.setTextSize(15);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(14);
        root.addView(description, descriptionParams);

        permissionButton = new Button(this);
        permissionButton.setText(Build.VERSION.SDK_INT >= 31
                ? "Optional: enable floating Fuku control"
                : "Grant overlay permission");
        LinearLayout.LayoutParams permissionParams = new LinearLayout.LayoutParams(-1, -2);
        permissionParams.topMargin = dp(26);
        root.addView(permissionButton, permissionParams);
        permissionButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        launchButton = new Button(this);
        launchButton.setText("Continue");
        LinearLayout.LayoutParams launchParams = new LinearLayout.LayoutParams(-1, -2);
        launchParams.topMargin = dp(10);
        root.addView(launchButton, launchParams);
        launchButton.setOnClickListener(v -> launchOriginalApp());

        TextView note = new TextView(this);
        note.setText("Floating gameplay rendering is not enabled yet. This stage tests the independent setup and overlay runtime.");
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
        updatePermissionState();
    }

    private void updatePermissionState() {
        if (permissionButton == null || launchButton == null) return;
        boolean allowed = Settings.canDrawOverlays(this);
        permissionButton.setText(allowed ? "Overlay permission granted"
                : (Build.VERSION.SDK_INT >= 31
                    ? "Optional: enable floating Fuku control"
                    : "Grant overlay permission"));
        launchButton.setEnabled(Build.VERSION.SDK_INT >= 31 || allowed);
    }

    private void launchOriginalApp() {
        boolean overlayAllowed = Settings.canDrawOverlays(this);
        if (Build.VERSION.SDK_INT < 31 && !overlayAllowed) {
            Toast.makeText(this, "Allow overlay permission first.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    getPackageName(), PackageManager.GET_META_DATA);
            String launcherClass = info.metaData == null ? null : info.metaData.getString(META_LAUNCHER);
            if (launcherClass == null || launcherClass.isEmpty()) {
                throw new IllegalStateException("Original game launcher was not recorded in the clone.");
            }
            if (overlayAllowed) {
                Intent service = new Intent(this, RuntimeOverlayService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            }

            getPreferences(MODE_PRIVATE).edit().putBoolean("complete", true).apply();
            Intent game = new Intent().setComponent(new ComponentName(getPackageName(), launcherClass));
            game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(game);
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
