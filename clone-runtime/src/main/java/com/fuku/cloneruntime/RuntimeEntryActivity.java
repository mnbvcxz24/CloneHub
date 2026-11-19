package com.fuku.cloneruntime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class RuntimeEntryActivity extends Activity {
    private static final String META_LAUNCHER = "com.fuku.cloneruntime.ORIGINAL_LAUNCHER";
    private boolean launched = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        continueLaunch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!launched && canDrawOverlays()) continueLaunch();
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void continueLaunch() {
        if (launched) return;
        if (!canDrawOverlays()) {
            showPermissionScreen();
            return;
        }
        launched = true;
        try {
            Intent service = new Intent(this, FloatingService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);

            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    getPackageName(), PackageManager.GET_META_DATA);
            String launcherClass = info.metaData == null ? null : info.metaData.getString(META_LAUNCHER);
            if (launcherClass == null || launcherClass.isEmpty()) {
                throw new IllegalStateException("The original app launcher was not recorded in this clone.");
            }
            Intent app = new Intent().setComponent(new ComponentName(getPackageName(), launcherClass));
            app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(app);
            finish();
        } catch (Exception error) {
            launched = false;
            showError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void showPermissionScreen() {
        LinearLayout root = createRoot();
        TextView title = new TextView(this);
        title.setText("Allow floating window");
        title.setTextColor(0xffffffff);
        title.setTextSize(22);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText("Fuku's QuickNote-style floating controller needs permission to appear over the cloned app. Enable 'Display over other apps' for this clone, then return here.");
        detail.setTextColor(0xffd5d9e2);
        detail.setTextSize(14);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-1, -2);
        textParams.topMargin = dp(12);
        root.addView(detail, textParams);

        Button grant = new Button(this);
        grant.setText("Grant overlay permission");
        grant.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(18);
        root.addView(grant, buttonParams);
        setContentView(root);
    }

    private void showError(String message) {
        LinearLayout root = createRoot();
        TextView title = new TextView(this);
        title.setText("Could not start floating controller");
        title.setTextColor(0xffffffff);
        title.setTextSize(20);
        root.addView(title);
        TextView detail = new TextView(this);
        detail.setText(message);
        detail.setTextColor(0xffd5d9e2);
        detail.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        root.addView(detail, params);
        setContentView(root);
    }

    private LinearLayout createRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(0xff10131a);
        return root;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
