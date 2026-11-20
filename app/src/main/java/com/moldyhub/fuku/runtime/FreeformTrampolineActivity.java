package com.moldyhub.fuku.runtime;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Per-clone launcher trampoline. This class and HiddenApiBypass are copied into
 * the generated clone's secondary DEX; it does not require Fuku to remain installed.
 */
public final class FreeformTrampolineActivity extends Activity {
    private static final String TARGET_ASSET = "fuku_launcher_target.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String target = readTargetActivity();
        if (target == null || target.trim().isEmpty()) {
            finish();
            return;
        }

        Intent launch = new Intent(getIntent());
        launch.setComponent(new ComponentName(getPackageName(), target.trim()));
        launch.setPackage(null);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        try {
            HiddenApiBypass.addHiddenApiExemptions("");
            ActivityOptions options = ActivityOptions.makeBasic();
            String methodName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? "setLaunchWindowingMode" : "setLaunchStackId";
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? 5 : 2;
            Method method = ActivityOptions.class.getMethod(methodName, int.class);
            method.invoke(options, mode);

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int width = Math.max(320, (int) (metrics.widthPixels * 0.72f));
            int height = Math.max(480, (int) (metrics.heightPixels * 0.72f));
            int left = Math.max(0, (metrics.widthPixels - width) / 2);
            int top = Math.max(0, (metrics.heightPixels - height) / 2);
            options.setLaunchBounds(new Rect(left, top, left + width, top + height));

            startActivity(launch, options.toBundle());
        } catch (Throwable freeformRejected) {
            // Freeform is a system/OEM capability. Keep the clone usable if the
            // device refuses the request, rather than leaving a blank trampoline.
            startActivity(launch);
        }
        finish();
    }

    private String readTargetActivity() {
        try (InputStream input = getAssets().open(TARGET_ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.readLine();
        } catch (Exception ignored) {
            return null;
        }
    }
}
