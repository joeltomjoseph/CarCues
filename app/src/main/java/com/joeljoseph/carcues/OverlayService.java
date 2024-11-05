package com.joeljoseph.carcues;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.view.LayoutInflater;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private View overlayView;

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
//        overlayView.setVisibility(View.INVISIBLE);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // Allow touch events
                PixelFormat.TRANSLUCENT
        );

        windowManager.addView(overlayView, params);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_OVERLAY".equals(intent.getAction())) {
            stopSelf(); // Stop the service, which will destroy the overlay
            NotificationManagerCompat.from(this).cancel(1); // Cancel the notification (id 1)
        }
        if (intent != null) {
            String textToAppend = intent.getStringExtra("text_to_append");
            if (textToAppend != null) {
                TextView textView = overlayView.findViewById(R.id.overlay_text_view);
                textView.setText(textToAppend);
            }
        }
        return START_NOT_STICKY;
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        windowManager.removeView(overlayView);
    }
}
