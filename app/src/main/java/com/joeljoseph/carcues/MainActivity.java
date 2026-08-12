package com.joeljoseph.carcues;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private static final String STATE_WAITING_FOR_OVERLAY_ACCESS =
            "waiting_for_overlay_access";
    private static final int NOTIFICATION_REQUEST_CODE = 101;

    private Button cueButton;
    private boolean waitingForOverlayAccess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        cueButton = findViewById(R.id.cueButton);
        waitingForOverlayAccess = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_WAITING_FOR_OVERLAY_ACCESS);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_WAITING_FOR_OVERLAY_ACCESS, waitingForOverlayAccess);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForOverlayAccess) {
            waitingForOverlayAccess = false;
            if (Settings.canDrawOverlays(this)) {
                continueStartingCueSession();
            } else {
                Toast.makeText(this, R.string.overlay_access_required, Toast.LENGTH_SHORT).show();
            }
        }
        refreshCueButton();
    }

    public void toggleCues(View view) {
        if (OverlayService.isActive()) {
            Intent intent = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP);
            startService(intent);
            cueButton.postDelayed(this::refreshCueButton, 100);
            return;
        }

        if (!OverlayService.hasRequiredSensors(this)) {
            Toast.makeText(this, R.string.motion_sensor_unsupported, Toast.LENGTH_LONG).show();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayAccess = true;
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
            return;
        }

        continueStartingCueSession();
    }

    private void continueStartingCueSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST_CODE
            );
            return;
        }

        if (!notificationsAvailable()) {
            Toast.makeText(this, R.string.notifications_required, Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(settingsIntent);
            return;
        }

        Intent intent = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        cueButton.postDelayed(this::refreshCueButton, 300);
    }

    private boolean notificationsAvailable() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = getSystemService(NotificationManager.class)
                    .getNotificationChannel(OverlayService.CHANNEL_ID);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != NOTIFICATION_REQUEST_CODE) {
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            continueStartingCueSession();
        } else {
            Toast.makeText(this, R.string.notifications_required, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshCueButton() {
        cueButton.setText(OverlayService.isActive() ? R.string.disable_cues : R.string.enable_cues);
    }
}
