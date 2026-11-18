package com.fuku.cloneruntime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class RuntimeEntryActivity extends Activity {
    private static final String META_LAUNCHER = "com.fuku.cloneruntime.ORIGINAL_LAUNCHER";
    private String launchError;
    private Button permissionButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getPreferences(MODE_PRIVATE).getBoolean("complete", false) && hasOverlayPermission()) {
            launchOriginalApp();
            return;
        }
        showSetup();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionButton != null) updatePermissionButton();
    }

    private boolean hasOverlayPermission() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23 || hasOverlayPermission()) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Could not open overlay permission settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void updatePermissionButton() {
        if (permissionButton == null) return;
        boolean granted = hasOverlayPermission();
        permissionButton.setText(granted ? "Overlay permission granted" : "Allow display over other apps");
        permissionButton.setEnabled(!granted);
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
        description.setText("Fuku needs overlay permission for the floating-window experiment. Allow display over other apps in Android Settings, then continue to launch the clone.");
        description.setTextColor(0xFFCDD3DF);
        description.setTextSize(15);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(14);
        root.addView(description, descriptionParams);

        permissionButton = new Button(this);
        permissionButton.setText("Allow display over other apps");
        LinearLayout.LayoutParams permissionParams = new LinearLayout.LayoutParams(-1, -2);
        permissionParams.topMargin = dp(20);
        root.addView(permissionButton, permissionParams);
        permissionButton.setOnClickListener(v -> openOverlaySettings());
        updatePermissionButton();

        Button launchButton = new Button(this);
        launchButton.setText("Continue");
        LinearLayout.LayoutParams launchParams = new LinearLayout.LayoutParams(-1, -2);
        launchParams.topMargin = dp(26);
        root.addView(launchButton, launchParams);
        launchButton.setOnClickListener(v -> {
            if (!hasOverlayPermission()) {
                openOverlaySettings();
                Toast.makeText(this, "Grant overlay permission first.", Toast.LENGTH_LONG).show();
            } else launchOriginalApp();
        });

        TextView note = new TextView(this);
        note.setText("Overlay permission enables drawing above other apps; it does not yet move the cloned app content into a system overlay.");
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
            startActivity(game);
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
