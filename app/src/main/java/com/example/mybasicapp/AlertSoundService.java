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
    // Channel ID should be unique and descriptive
    public static final String CUSTOM_ALERT_SOUND_CHANNEL_ID = "custom_alert_sound_playback_channel";
    // Notification ID for the foreground service notification (distinct from data alert notifications)
    private static final int FOREGROUND_NOTIFICATION_ID = 3; // Unique ID within your app

    public static final String ACTION_PLAY_CUSTOM_SOUND = "com.example.mybasicapp.ACTION_PLAY_CUSTOM_SOUND";
    public static final String EXTRA_SOUND_URI = "EXTRA_SOUND_URI"; // URI of the sound file to play

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: AlertSoundService creating.");
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            // WakeLock to ensure CPU stays active during sound playback, especially if screen is off
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MrCoopersESP::AlertSoundWakeLockTag");
            wakeLock.setReferenceCounted(false); // Manage acquire/release manually
        } else {
            Log.w(TAG, "onCreate: PowerManager not available, WakeLock not created.");
        }
        createNotificationChannelForService(); // Create notification channel for this service's foreground notification
        Log.d(TAG, "onCreate: AlertSoundService created.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Null intent or action. Stopping service.");
            cleanupAndStopService(); // Ensure cleanup if started improperly
            return START_NOT_STICKY; // Don't restart if killed without a valid intent
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Received action='" + action + "'");

        if (ACTION_PLAY_CUSTOM_SOUND.equals(action)) {
            String soundUriString = intent.getStringExtra(EXTRA_SOUND_URI);
            if (soundUriString != null && !soundUriString.isEmpty()) {
                Uri soundUri = Uri.parse(soundUriString);
                // Start as a foreground service to ensure playback continues even if app is backgrounded
                startForegroundWithNotificationForPlayback();
                playSoundInBackground(soundUri);
            } else {
                Log.e(TAG, "ACTION_PLAY_CUSTOM_SOUND: Sound URI is missing or empty! Stopping service.");
                cleanupAndStopService();
            }
        } else {
            Log.w(TAG, "onStartCommand: Unhandled action: " + action + ". Stopping service.");
            cleanupAndStopService();
        }
        // If the service is killed by the system after returning from here,
        // it will not be restarted unless it's explicitly started again.
        return START_NOT_STICKY;
    }

    private void createNotificationChannelForService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use string resources for channel name and description
            CharSequence name = getString(R.string.alert_sound_service_channel_name);
            String description = getString(R.string.alert_sound_service_channel_description);
            // Importance LOW is suitable for a service just indicating it's active for playback
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CUSTOM_ALERT_SOUND_CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created: " + CUSTOM_ALERT_SOUND_CHANNEL_ID);
            } else {
                Log.e(TAG, "Failed to get NotificationManager to create channel.");
            }
        }
    }

    private void startForegroundWithNotificationForPlayback() {
        Log.d(TAG, "startForegroundWithNotificationForPlayback called");
        // Intent to open MainActivity when notification is tapped
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, // Request code 0
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CUSTOM_ALERT_SOUND_CHANNEL_ID)
                .setContentTitle(getString(R.string.alert_sound_notification_title))
                .setContentText(getString(R.string.alert_sound_notification_text))
                .setSmallIcon(R.drawable.ic_stat_playing_sound) // Use a specific icon for "playing sound"
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Makes the notification persistent
                .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for this type of status
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                 startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
            Log.i(TAG, "Service started in foreground for custom sound playback.");
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service for custom sound: " + e.getMessage(), e);
            // If foreground start fails, playback might be unreliable. Consider stopping.
            cleanupAndStopService();
        }
    }

    private void playSoundInBackground(Uri soundUri) {
        Log.d(TAG, "playSoundInBackground: Preparing to play URI=" + soundUri);
        if (mediaPlayer != null) {
            mediaPlayer.release(); // Release any previous instance
            mediaPlayer = null;
        }
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC) // Or CONTENT_TYPE_SONIFICATION for alerts
                        .setUsage(AudioAttributes.USAGE_ALARM) // USAGE_ALARM is important for alerts to bypass DND sometimes
                        .build());

        // Acquire WakeLock before starting playback
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L /* 10 minutes timeout, ample for most alert sounds */);
            Log.d(TAG, "WakeLock acquired.");
        } else if (wakeLock == null) {
            Log.w(TAG, "WakeLock is null, playback might be interrupted if device sleeps.");
        }

        try {
            // The calling component (HomeFragment/HttpPollingService) should have ensured
            // FLAG_GRANT_READ_URI_PERMISSION was added to the intent if it's a content URI.
            mediaPlayer.setDataSource(getApplicationContext(), soundUri);
            mediaPlayer.prepareAsync(); // Asynchronous preparation
            mediaPlayer.setOnPreparedListener(mp -> {
                Log.i(TAG, "MediaPlayer prepared, starting custom sound playback.");
                mp.start(); // Start playback once prepared
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.i(TAG, "Custom sound playback completed.");
                cleanupAndStopService(); // Clean up and stop service after completion
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error during custom sound: what=" + what + ", extra=" + extra + " for URI: " + soundUri);
                cleanupAndStopService(); // Clean up and stop on error
                return true; // True if the error has been handled
            });
        } catch (IOException e) {
            Log.e(TAG, "IOException setting MediaPlayer data source for custom sound: " + soundUri, e);
            cleanupAndStopService();
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException (URI permission issue?) for custom sound: " + soundUri, se);
            cleanupAndStopService();
        } catch (IllegalArgumentException iae) {
            Log.e(TAG, "IllegalArgumentException (invalid URI or MediaPlayer state) for: " + soundUri, iae);
            cleanupAndStopService();
        }
    }

    private void cleanupAndStopService() {
        Log.d(TAG, "cleanupAndStopService called");
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                try {
                    mediaPlayer.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaPlayer.stop() called in an invalid state.", e);
                }
            }
            mediaPlayer.release(); // Release MediaPlayer resources
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released.");
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released.");
        }
        // Stop foreground state and remove notification, then stop the service itself
        stopForeground(true); // true = remove notification
        stopSelf(); // Stop the service
        Log.i(TAG, "AlertSoundService cleanup complete and service stopped.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // This is not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: AlertSoundService being destroyed.");
        // Ensure all resources are released if service is destroyed unexpectedly
        cleanupAndStopService();
    }
}