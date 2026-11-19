package com.fuku.cloneruntime;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.graphics.Color;
import android.widget.Toast;

public final class RuntimeEntryActivity extends Activity {
    private static final String META_LAUNCHER = "com.fuku.cloneruntime.ORIGINAL_LAUNCHER";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        launchOriginalApp();
    }

    private void launchOriginalApp() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    getPackageName(), PackageManager.GET_META_DATA);
            String launcherClass = info.metaData == null ? null : info.metaData.getString(META_LAUNCHER);
            if (launcherClass == null || launcherClass.isEmpty()) {
                throw new IllegalStateException("The original app launcher was not recorded in this clone.");
            }

            boolean freeformAvailable = Build.VERSION.SDK_INT >= 24
                    && getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT);

            Intent game = new Intent().setComponent(new ComponentName(getPackageName(), launcherClass));
            game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 24) {
                DisplayMetrics metrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                int width = Math.min(metrics.widthPixels, Math.max(dp(280), Math.round(metrics.widthPixels * 0.78f)));
                int height = Math.min(metrics.heightPixels, Math.max(dp(240), Math.round(metrics.heightPixels * 0.78f)));
                int left = Math.max(0, (metrics.widthPixels - width) / 2);
                int top = Math.max(0, (metrics.heightPixels - height) / 3);
                // Android only honors these bounds when the display supports freeform
                // (or PiP) window management. This is a native window request, not an overlay.
                options.setLaunchBounds(new Rect(left, top, left + width, top + height));
            }

            startActivity(game, options.toBundle());
            if (!freeformAvailable) {
                Toast.makeText(this,
                        "This device does not report Android freeform-window support. The clone may open fullscreen. Enable freeform/multi-window support in system settings if your ROM provides it.",
                        Toast.LENGTH_LONG).show();
            }
            finish();
        } catch (Exception error) {
            showLaunchError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void showLaunchError(String message) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(16, 19, 26));

        TextView title = new TextView(this);
        title.setText("Could not open the cloned app");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setText(message);
        detail.setTextColor(Color.LTGRAY);
        detail.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        root.addView(detail, params);
        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
