package com.example.mybasicapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

public class AlertSoundService extends Service {

    private static final String TAG = "AlertSoundService_DBG";
    public static final String CUSTOM_ALERT_SOUND_CHANNEL_ID = "custom_alert_sound_service_channel";
    private static final int CUSTOM_ALERT_NOTIFICATION_ID = 3; // Unique ID

    public static final String ACTION_PLAY_CUSTOM_SOUND = "com.example.mybasicapp.ACTION_PLAY_CUSTOM_SOUND";
    public static final String EXTRA_SOUND_URI = "EXTRA_SOUND_URI";

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: AlertSoundService creating.");
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MrCoopersESP::AlertSoundWakeLock");
            wakeLock.setReferenceCounted(false);
        }
        createNotificationChannel(); // Create channel early
        Log.d(TAG, "onCreate: AlertSoundService created.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Null intent or action. Stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Action='" + action + "'");

        if (ACTION_PLAY_CUSTOM_SOUND.equals(action)) {
            String soundUriString = intent.getStringExtra(EXTRA_SOUND_URI);
            if (soundUriString != null) {
                Uri soundUri = Uri.parse(soundUriString);
                startForegroundWithNotification();
                playSoundInBackground(soundUri);
            } else {
                Log.e(TAG, "ACTION_PLAY_CUSTOM_SOUND: Sound URI is missing! Stopping service.");
                stopSelf();
            }
        } else {
            Log.w(TAG, "onStartCommand: Unhandled action: " + action + ". Stopping service.");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CUSTOM_ALERT_SOUND_CHANNEL_ID,
                    getString(R.string.alert_sound_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW // Low importance for background service notification
            );
            channel.setDescription(getString(R.string.alert_sound_service_channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created: " + CUSTOM_ALERT_SOUND_CHANNEL_ID);
            }
        }
    }

    private void startForegroundWithNotification() {
        Log.d(TAG, "startForegroundWithNotification called");
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CUSTOM_ALERT_SOUND_CHANNEL_ID)
                .setContentTitle(getString(R.string.alert_sound_notification_title))
                .setContentText(getString(R.string.alert_sound_notification_text))
                .setSmallIcon(R.drawable.ic_stat_message) // Use an appropriate icon
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                 startForeground(CUSTOM_ALERT_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(CUSTOM_ALERT_NOTIFICATION_ID, notification);
            }
            Log.i(TAG, "Service started in foreground for custom sound.");
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service for custom sound: " + e.getMessage(), e);
        }
    }

    private void playSoundInBackground(Uri soundUri) {
        Log.d(TAG, "playSoundInBackground: URI=" + soundUri);
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_ALARM) // Important for alerts
                        .build());

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L /* 10 minutes timeout */);
            Log.d(TAG, "WakeLock acquired.");
        }

        try {
            // Grant temporary permission to the media player if needed, by setting the URI on the intent to the service.
            // MainActivity should have added Intent.FLAG_GRANT_READ_URI_PERMISSION
            // Or, the URI must be persistable and permission already taken by MainActivity.
            mediaPlayer.setDataSource(getApplicationContext(), soundUri);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                Log.i(TAG, "MediaPlayer prepared, starting custom sound playback.");
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.i(TAG, "Custom sound playback completed.");
                cleanupAndStopService();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error during custom sound: what=" + what + ", extra=" + extra);
                cleanupAndStopService();
                return true; // Error handled
            });
        } catch (IOException e) {
            Log.e(TAG, "IOException for custom sound: " + e.getMessage(), e);
            cleanupAndStopService();
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException for custom sound (URI permission issue?): " + se.getMessage(), se);
            cleanupAndStopService();
        }
    }

    private void cleanupAndStopService() {
        Log.d(TAG, "cleanupAndStopService called");
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released.");
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released.");
        }
        stopForeground(true); // Remove notification
        stopSelf(); // Stop the service
        Log.i(TAG, "AlertSoundService stopped.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: AlertSoundService destroyed.");
        cleanupAndStopService(); // Ensure all resources are released
    }
}