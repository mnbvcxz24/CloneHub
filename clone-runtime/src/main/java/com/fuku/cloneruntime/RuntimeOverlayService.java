package com.fuku.cloneruntime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public final class RuntimeOverlayService extends Service {
    private static final String CHANNEL = "fuku_clone_controls";
    private static final int NOTIFICATION_ID = 8127;
    private WindowManager windowManager;
    private View control;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        if (android.provider.Settings.canDrawOverlays(this)) showControl();
        else stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (control != null && windowManager != null) {
            try { windowManager.removeView(control); } catch (Exception ignored) { }
            control = null;
        }
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Clone floating controls", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, RuntimeEntryActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Clone floating controls active")
                .setContentText("The cloned app is running independently of Fuku.")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void showControl() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        TextView pill = new TextView(this);
        pill.setText("Fuku • PiP");
        pill.setTextColor(0xFFFFFFFF);
        pill.setTextSize(13);
        pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pill.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEE202938);
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), 0xFF65738B);
        pill.setBackground(background);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(18);
        params.y = dp(90);

        pill.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downX);
                        int dy = (int) (event.getRawY() - downY);
                        if (Math.abs(dx) + Math.abs(dy) > dp(4)) moved = true;
                        params.x = startX + dx;
                        params.y = startY + dy;
                        try { windowManager.updateViewLayout(control, params); }
                        catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (PipRuntimeController.enterCurrentActivity()) {
                                stopSelf();
                            } else {
                                Toast.makeText(RuntimeOverlayService.this,
                                        "PiP is not available for the current screen yet.",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        control = pill;
        try {
            windowManager.addView(control, params);
        } catch (Exception error) {
            control = null;
            stopSelf();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
