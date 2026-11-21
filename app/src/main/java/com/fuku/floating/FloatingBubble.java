package com.fuku.floating;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * Sends the clone task to the background and leaves a draggable overlay bubble.
 * The user must grant Display over other apps for each cloned package.
 */
final class FloatingBubble {
    private static final Map<Integer, View> BUBBLES = new HashMap<Integer, View>();

    private FloatingBubble() {}

    static boolean minimize(Activity activity) {
        Context app = activity.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            Toast.makeText(activity, "Allow Display over other apps, then tap minimize again.",
                    Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + app.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (Throwable error) {
                android.util.Log.w("FukuFloating", "Could not open overlay permission screen", error);
            }
            return false;
        }
        try {
            show(app, activity.getTaskId(), String.valueOf(activity.getApplicationInfo().loadLabel(
                    activity.getPackageManager())));
        } catch (Throwable error) {
            android.util.Log.e("FukuFloating", "Could not show minimize bubble", error);
            Toast.makeText(activity, "Could not create minimize bubble", Toast.LENGTH_SHORT).show();
            return false;
        }
        activity.moveTaskToBack(true);
        return true;
    }

    static void hide(int taskId) {
        View bubble = BUBBLES.remove(taskId);
        if (bubble == null) return;
        try {
            WindowManager wm = (WindowManager) bubble.getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(bubble);
        } catch (Throwable ignored) {}
    }

    private static void show(final Context app, final int taskId, String label) {
        if (BUBBLES.containsKey(taskId)) return;
        final WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        final float density = app.getResources().getDisplayMetrics().density;
        final int size = Math.round(52 * density);

        final ImageView bubble = new ImageView(app);
        bubble.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xE6202124);
        bg.setStroke(Math.round(2 * density), 0xFF8AB4F8);
        bubble.setBackground(bg);
        bubble.setClipToOutline(true);
        try {
            bubble.setImageDrawable(app.getPackageManager().getApplicationIcon(app.getPackageName()));
        } catch (Throwable iconError) {
            android.util.Log.w("FukuFloating", "Could not load cloned app icon", iconError);
            bubble.setImageDrawable(null);
        }
        bubble.setContentDescription(label != null && label.length() > 0
                ? label + " minimized app" : "Minimized app");

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(8 * density);
        lp.y = Math.round(160 * density);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragged;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > 10 * density || Math.abs(dy) > 10 * density) dragged = true;
                        if (dragged) {
                            lp.x = startX + Math.round(dx);
                            lp.y = startY + Math.round(dy);
                            try { wm.updateViewLayout(v, lp); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) restore(app, taskId);
                        return true;
                }
                return false;
            }
        });

        wm.addView(bubble, lp);
        BUBBLES.put(taskId, bubble);
    }

    private static void restore(Context app, int taskId) {
        hide(taskId);
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(taskId, 0);
            return;
        } catch (Throwable error) {
            android.util.Log.w("FukuFloating", "moveTaskToFront failed, using launch intent", error);
        }
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                app.startActivity(launch);
            }
        } catch (Throwable error) {
            android.util.Log.e("FukuFloating", "Could not restore minimized clone", error);
        }
    }
}
