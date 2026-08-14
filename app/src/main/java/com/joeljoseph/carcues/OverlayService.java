package com.joeljoseph.carcues;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class OverlayService extends Service implements SensorEventListener {
    static final String ACTION_START = "com.joeljoseph.carcues.START";
    static final String ACTION_STOP = "com.joeljoseph.carcues.STOP";
    static final String CHANNEL_ID = "cue_session";

    private static final int NOTIFICATION_ID = 1;
    private static final int SENSOR_PERIOD_MICROSECONDS = 20_000;
    private static volatile boolean active;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MotionEstimator motionEstimator = new MotionEstimator();
    private final float[] gravity = new float[3];

    private WindowManager windowManager;
    private SensorManager sensorManager;
    private AppOpsManager appOpsManager;
    private CueFieldView cueFieldView;
    private WindowManager.LayoutParams overlayParams;
    private boolean hasGravitySample;
    private boolean foregroundStarted;
    private boolean watchingOverlayAccess;
    private boolean watchingScreenState;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                sensorManager.unregisterListener(OverlayService.this);
                resetMotionState();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && active) {
                resetMotionState();
                if (!registerMotionSensors()) {
                    Toast.makeText(
                            OverlayService.this,
                            R.string.motion_sensor_unavailable,
                            Toast.LENGTH_LONG
                    ).show();
                    stopCueSession();
                }
            }
        }
    };

    private final AppOpsManager.OnOpChangedListener overlayAccessListener =
            (operation, packageName) -> mainHandler.post(() -> {
                if (active && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.overlay_access_removed, Toast.LENGTH_SHORT).show();
                    stopCueSession();
                }
            });

    static boolean isActive() {
        return active;
    }

    static boolean hasRequiredSensors(Context context) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) {
            return false;
        }
        if (manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null) {
            return true;
        }
        return manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                && manager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        appOpsManager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCueSession();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        return startCueSession() ? START_STICKY : START_NOT_STICKY;
    }

    private boolean startCueSession() {
        if (active) {
            return true;
        }

        try {
            startInForeground();
            if (!Settings.canDrawOverlays(this)) {
                throw new IllegalStateException(getString(R.string.overlay_access_required));
            }
            if (!hasRequiredSensors(this)) {
                throw new IllegalStateException(getString(R.string.motion_sensor_unsupported));
            }

            addCueField();
            if (!registerMotionSensors()) {
                throw new IllegalStateException(getString(R.string.motion_sensor_unavailable));
            }
            IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenStateReceiver, screenStateFilter);
            watchingScreenState = true;
            watchOverlayAccess();
            active = true;
            return true;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? getString(R.string.cue_session_failed)
                    : exception.getMessage();
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            stopCueSession();
            return false;
        }
    }

    private void startInForeground() {
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification_icon)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openPendingIntent)
                .addAction(R.drawable.ic_stop, getString(R.string.stop_cues), stopPendingIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void addCueField() {
        cueFieldView = new CueFieldView(this);
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.alpha = getTouchSafeOpacity();
        windowManager.addView(cueFieldView, overlayParams);
    }

    private float getTouchSafeOpacity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            InputManager inputManager = getSystemService(InputManager.class);
            if (inputManager != null) {
                return Math.min(0.8f, inputManager.getMaximumObscuringOpacityForTouch());
            }
        }
        return 0.8f;
    }

    private boolean registerMotionSensors() {
        Sensor linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (linearAcceleration != null) {
            return sensorManager.registerListener(
                    this,
                    linearAcceleration,
                    SENSOR_PERIOD_MICROSECONDS,
                    0
            );
        }

        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        boolean accelerometerRegistered = sensorManager.registerListener(
                this,
                accelerometer,
                SENSOR_PERIOD_MICROSECONDS,
                0
        );
        boolean gravityRegistered = sensorManager.registerListener(
                this,
                gravitySensor,
                SENSOR_PERIOD_MICROSECONDS,
                0
        );
        if (!accelerometerRegistered || !gravityRegistered) {
            sensorManager.unregisterListener(this);
            return false;
        }
        return true;
    }

    private void watchOverlayAccess() {
        if (appOpsManager == null) {
            return;
        }
        appOpsManager.startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                getPackageName(),
                overlayAccessListener
        );
        watchingOverlayAccess = true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int sensorType = event.sensor.getType();
        if (sensorType == Sensor.TYPE_GRAVITY) {
            System.arraycopy(event.values, 0, gravity, 0, gravity.length);
            hasGravitySample = true;
            return;
        }

        float x;
        float y;
        if (sensorType == Sensor.TYPE_LINEAR_ACCELERATION) {
            x = event.values[0];
            y = event.values[1];
        } else if (sensorType == Sensor.TYPE_ACCELEROMETER && hasGravitySample) {
            x = event.values[0] - gravity[0];
            y = event.values[1] - gravity[1];
        } else {
            return;
        }

        MotionEstimator.NormalizedVehicleAccelerationEstimate estimate = motionEstimator.sample(
                event.timestamp,
                x,
                y,
                currentDisplayRotation()
        );
        cueFieldView.setVehicleAccelerationEstimate(estimate);
    }

    private int currentDisplayRotation() {
        return cueFieldView.getDisplay() == null
                ? Surface.ROTATION_0
                : cueFieldView.getDisplay().getRotation();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resetMotionState();
        if (cueFieldView != null && overlayParams != null) {
            windowManager.updateViewLayout(cueFieldView, overlayParams);
            cueFieldView.requestLayout();
        }
    }

    private void resetMotionState() {
        hasGravitySample = false;
        motionEstimator.reset();
        if (cueFieldView != null) {
            cueFieldView.resetCueFlow();
        }
    }

    private void stopCueSession() {
        cleanUpSession();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void cleanUpSession() {
        active = false;
        resetMotionState();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (watchingScreenState) {
            unregisterReceiver(screenStateReceiver);
            watchingScreenState = false;
        }
        if (watchingOverlayAccess && appOpsManager != null) {
            appOpsManager.stopWatchingMode(overlayAccessListener);
            watchingOverlayAccess = false;
        }
        if (cueFieldView != null && windowManager != null) {
            try {
                windowManager.removeView(cueFieldView);
            } catch (IllegalArgumentException ignored) {
                // The system already detached the overlay.
            }
            cueFieldView = null;
            overlayParams = null;
        }
    }

    @Override
    public void onDestroy() {
        cleanUpSession();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
