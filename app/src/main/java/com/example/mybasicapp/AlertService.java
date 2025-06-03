package com.example.mybasicapp;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlertService extends Service {

    private static final String TAG = "AlertService";
    public static final String CHANNEL_ID = "AlertServiceChannel"; // Must match MainActivity
    private static final int NOTIFICATION_ID = 1; // Unique ID for the foreground notification
    private static final String LOG_FILE_NAME = "sensor_alerts.log";


    public static final String ACTION_PLAY_SOUND = "com.example.mybasicapp.ACTION_PLAY_SOUND";
    public static final String EXTRA_SOUND_URI = "com.example.mybasicapp.EXTRA_SOUND_URI";

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyBasicApp::AlertWakeLockTag");
            wakeLock.setReferenceCounted(false); // Manage wakelock manually
        }
        Log.d(TAG, "AlertService created.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand received with action: " + (intent != null ? intent.getAction() : "null intent"));
        if (intent != null && ACTION_PLAY_SOUND.equals(intent.getAction())) {
            String soundUriString = intent.getStringExtra(EXTRA_SOUND_URI);
            if (soundUriString != null) {
                Uri soundUri = Uri.parse(soundUriString);
                
                // Try to get persistable URI permission if not already held
                // This is important if the activity that granted it is no longer active
                try {
                    // Check if we need to take (or re-take) permission.
                    // The flag Intent.FLAG_GRANT_READ_URI_PERMISSION on the intent to the service also helps.
                    // ContentResolver.takePersistableUriPermission is more for long-term access from Activity.
                    // For a service, the grant from the Intent should suffice as long as the service is running.
                    // But checking access is good.
                    getContentResolver().openInputStream(soundUri).close(); // Test accessibility
                     Log.d(TAG, "Service has access to URI: " + soundUri.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Service lost access to URI: " + soundUri.toString(), e);
                    logSensorTrigger(this, "Sensor triggered, but service lost access to URI: " + getFileNameFromUri(this, soundUri));
                    cleanupAndStopService();
                    return START_NOT_STICKY;
                }

                startForegroundWithNotification();
                String logMessage = "Sensor triggered. Attempting to play sound: " + getFileNameFromUri(this, soundUri);
                logSensorTrigger(this, logMessage);
                playSoundInBackground(soundUri);
            } else {
                Log.e(TAG, "Sound URI is null in intent. Stopping service.");
                logSensorTrigger(this, "Sensor triggered, but sound URI was null in intent.");
                stopSelf();
            }
        } else {
            Log.w(TAG, "Null intent or invalid action. Stopping service.");
            if (intent == null) {
                logSensorTrigger(this, "Service started with null intent.");
            } else {
                logSensorTrigger(this, "Service started with invalid action: " + intent.getAction());
            }
            stopSelf(); // Stop if no valid action or if intent is null (e.g. service killed and restarted without sticky)
        }
        return START_NOT_STICKY; // Don't restart if killed by system unless explicitly told to
    }

    private void startForegroundWithNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class); // Clicking notification opens MainActivity
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_alert_playing))
                .setContentText(getString(R.string.notification_text_alert_playing))
                .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's actual icon
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW) // Or higher if critical, but LOW is good for background tasks
                .setOngoing(true) // Makes the notification non-dismissable
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            Log.d(TAG, "Foreground service started with notification.");
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service", e);
            logSensorTrigger(this, "Error starting foreground service: " + e.getMessage());
            // Attempt to stop gracefully if foreground start fails
            cleanupAndStopService();
        }
    }

    private void playSoundInBackground(Uri soundUri) {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_ALARM) // Crucial for alarms and alerts
                        .build());

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10*60*1000L /* 10 minutes timeout */); // Acquire WakeLock before starting playback
            Log.d(TAG, "WakeLock acquired.");
        }

        try {
            mediaPlayer.setDataSource(getApplicationContext(), soundUri);
            mediaPlayer.prepareAsync(); // Prepare asynchronously
            mediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "MediaPlayer prepared, starting playback in background.");
                mp.start();
                logSensorTrigger(this, "Playback started for: " + getFileNameFromUri(this, soundUri));
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "MediaPlayer playback completed.");
                logSensorTrigger(this, "Playback completed for: " + getFileNameFromUri(this, soundUri));
                cleanupAndStopService();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error during background playback: what=" + what + ", extra=" + extra);
                logSensorTrigger(this, "MediaPlayer error for: " + getFileNameFromUri(this, soundUri) + " What: " + what + " Extra: " + extra);
                cleanupAndStopService();
                return true; // Indicates the error was handled
            });
        } catch (IOException e) {
            Log.e(TAG, "IOException setting data source or preparing MediaPlayer for background playback", e);
            logSensorTrigger(this, "IOException during sound prep for: " + getFileNameFromUri(this, soundUri) + " Error: " + e.getMessage());
            cleanupAndStopService();
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException setting data source, URI permission likely lost: " + soundUri, se);
            logSensorTrigger(this, "SecurityException, URI permission lost for: " + getFileNameFromUri(this, soundUri) + " Error: " + se.getMessage());
            cleanupAndStopService();
        }
    }
    
    private String getFileNameFromUri(Context context, Uri uri) {
        String result = null;
        if (uri == null) return "Unknown URI";
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                         result = cursor.getString(displayNameIndex);
                    } else {
                        Log.w(TAG, "_display_name column not found for URI: " + uri);
                    }
                } else {
                     Log.w(TAG, "Cursor is null or empty for URI: " + uri);
                }
            } catch (Exception e) { // Catch SecurityException or others
                Log.e(TAG, "Error getting file name from content URI in service: " + uri, e);
                result = "Access Denied or Error";
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            } else {
                result = uri.toString(); // Fallback to full URI string if path is null
            }
        }
        return result;
    }

    private void cleanupAndStopService() {
        Log.d(TAG, "Cleaning up resources and stopping service.");
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released.");
        }
        stopForeground(true); // True to remove the notification
        stopSelf(); // Stop the service instance
        Log.d(TAG, "Service stopped.");
    }

    private void logSensorTrigger(Context context, String message) {
        File logFile = new File(context.getFilesDir(), LOG_FILE_NAME);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logEntry = "[" + timestamp + "] " + message + "\n";

        FileOutputStream fos = null;
        OutputStreamWriter osw = null;
        try {
            fos = new FileOutputStream(logFile, true); // true for append mode
            osw = new OutputStreamWriter(fos);
            osw.write(logEntry);
            Log.i(TAG, "Logged to file: " + logEntry.trim()); // Log to Logcat as well
        } catch (IOException e) {
            Log.e(TAG, "Error writing to log file: " + logFile.getAbsolutePath(), e);
        } finally {
            try {
                if (osw != null) osw.close(); // This also flushes and closes fos
                else if (fos != null) fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing log file streams", e);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AlertService destroyed.");
        // Ensure cleanup if service is destroyed unexpectedly, though cleanupAndStopService should handle most cases
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.w(TAG, "WakeLock released in onDestroy (was unexpectedly held).");
        }
    }
}