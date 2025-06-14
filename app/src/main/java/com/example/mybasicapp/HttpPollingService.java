package com.example.mybasicapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.core.app.NotificationCompat; // ADDED IMPORT


import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects; // Added for Objects.equals
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpPollingService extends Service {

    private static final String TAG = "HttpPollingService_DBG";
    public static final String ACTION_START_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_START_HTTP_FG_SERVICE";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_STOP_HTTP_FG_SERVICE";
    public static final String ACTION_START_POLLING = "com.example.mybasicapp.ACTION_START_POLLING"; // Could be merged with START_FG_SERVICE
    public static final String ACTION_STOP_POLLING = "com.example.mybasicapp.ACTION_STOP_POLLING";
    public static final String EXTRA_BASE_URL = "EXTRA_BASE_URL"; // Expected to be like "http://192.168.1.100" or "http://esp.local"

    public static final String ACTION_STATUS_UPDATE = "com.example.mybasicapp.ACTION_HTTP_STATUS_UPDATE";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";
    public static final String ACTION_DATA_RECEIVED = "com.example.mybasicapp.ACTION_HTTP_DATA_RECEIVED";
    public static final String EXTRA_DATA_TYPE = "EXTRA_DATA_TYPE"; // e.g., "mic_data"
    public static final String EXTRA_DATA_JSON_STRING = "EXTRA_DATA_JSON_STRING";

    private static final String NOTIFICATION_CHANNEL_ID_SERVICE = "http_polling_service_status_channel";
    private static final String NOTIFICATION_CHANNEL_ID_MESSAGES = "esp32_http_notifications"; // For actual alerts from ESP data
    private static final int SERVICE_NOTIFICATION_ID = 2; // For the foreground service itself
    private static final int MESSAGE_NOTIFICATION_ID_OFFSET = 1000; // Base for data alert notifications to allow multiple

    private OkHttpClient httpClient;
    private final Handler pollingHandler = new Handler(Looper.getMainLooper());
    private boolean isServiceRunningAsForeground = false;
    private boolean isCurrentlyPolling = false;
    private String currentTargetBaseUrl; // The ESP address this service instance is currently targeting

    // Polling interval - could be configurable
    // For the ESP code provided, it seems to respond to /get_config or a similar data endpoint
    // The previous code used /get_distance. We should make this more generic or configurable if needed.
    // Assuming the ESP provides mic data at a generic endpoint like "/get_mic_data" or just "/" returns relevant JSON.
    // For now, let's assume the root "/" of the ESP returns the mic data JSON.
    private static final String DATA_ENDPOINT = "/"; // Or "/get_mic_data" or "/get_config" if that's where mic data is
    private static final String DATA_TYPE_MIC = "mic_data"; // Identifier for this data type

    private static final long POLLING_INTERVAL_MS = 2500; // Default polling interval

    // SharedPreferences keys (must match what HomeFragment and MainActivity use/set for service)
    private static final String PREFS_NAME = "MrCooperESP_Prefs"; // Name of the shared prefs file
    private static final String PREF_TRIGGER_DISTANCE_DEPRECATED = "trigger_distance_cm"; // Assuming this is ESP-side threshold now
    private static final String PREF_NOTIFICATIONS_ENABLED = "notifications_enabled"; // App-side notifications (based on app's threshold)
    private static final String PREF_CUSTOM_ALERT_SOUND_URI = "custom_alert_sound_uri";
    private static final String PREF_CUSTOM_ALERT_SOUND_ENABLED = "custom_alert_sound_enabled";

    // App-side alert threshold (read from different prefs, set by HomeFragment)
    private static final String PREFS_HOME_FRAGMENT = "HomeFragmentPrefs_v2";
    private static final String PREF_APP_ALERT_LEVEL_DB = "app_alert_level_db";
    private static final String PREF_APP_ALERTS_ENABLED = "app_alerts_enabled"; // ADDED CONSTANT
    // Default values if not found in prefs
    private static final int DEFAULT_APP_ALERT_THRESHOLD_DB = 70; // Default if not set in HomeFragment prefs
    private static final boolean DEFAULT_NOTIFICATIONS_ENABLED = false;
    private static final boolean DEFAULT_CUSTOM_SOUND_ENABLED = true;

    private SharedPreferences serviceControlPrefs; // For custom sound URI/enable (MrCooperESP_Prefs)
    private SharedPreferences appAlertSettingsPrefs; // For app-side alert dB threshold (HomeFragmentPrefs_v2)

    // For logging sensor triggers
    private static final String SENSOR_TRIGGER_LOG_FILE_NAME_KEY = "log_sensor_trigger_file_name";


    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: Service Creating");
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS) // Shorter timeout for local network
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_SERVICE, getString(R.string.http_polling_service_channel_name), NotificationManager.IMPORTANCE_LOW);
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_MESSAGES, getString(R.string.channel_name_http_alerts), NotificationManager.IMPORTANCE_HIGH);

        serviceControlPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        appAlertSettingsPrefs = getSharedPreferences(PREFS_HOME_FRAGMENT, Context.MODE_PRIVATE);

        Log.d(TAG, "onCreate: Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Null intent or action. Flags=" + flags + ", StartId=" + startId);
            // If service is killed and restarted, intent might be null.
            // Decide restart behavior: if currentTargetBaseUrl is persisted, could try to restart polling.
            // For now, it will just remain idle until a new valid start intent.
            if (isServiceRunningAsForeground && currentTargetBaseUrl != null) {
                // If it was foreground and had a target, try to resume polling for that target
                Log.i(TAG, "Service restarted, attempting to resume polling for: " + currentTargetBaseUrl);
                startPollingData(); // This will use the existing currentTargetBaseUrl
            } else if (!isServiceRunningAsForeground && currentTargetBaseUrl != null) {
                // Was not foreground but had a target, make it foreground and poll
                startForegroundServiceWithNotification("Service Resuming Polling " + getHostFromUrl(currentTargetBaseUrl));
                startPollingData();
            }
            return START_STICKY; // Or START_NOT_STICKY if you don't want auto-restart without explicit command
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Action='" + action + "', TargetURL='" + intent.getStringExtra(EXTRA_BASE_URL) + "', CurrentTarget='" + currentTargetBaseUrl + "'");

        switch (action) {
            case ACTION_START_FOREGROUND_SERVICE:
            case ACTION_START_POLLING: // Treat these similarly now
                String newTargetUrl = intent.getStringExtra(EXTRA_BASE_URL);
                if (newTargetUrl == null || newTargetUrl.isEmpty()){
                    Log.e(TAG, action + ": Base URL is missing! Stopping service or ignoring.");
                    sendBroadcastStatus("Error: Base URL missing for service start/poll.");
                    // If no valid target, and service is running, decide whether to stop it or let it idle.
                    // If it was polling a previous URL, it should stop that.
                    if (isCurrentlyPolling) {
                        stopPollingData();
                    }
                    if (isServiceRunningAsForeground) { // If it was foreground but now has no target
                        updateServiceNotification("Service Active (Idle - No Target)");
                    }
                    // Don't stopSelf() immediately, allow another valid start command.
                    return START_STICKY;
                }

                // Normalize URL (ensure http schema, useful if fragment sends just IP/hostname)
                if (!newTargetUrl.toLowerCase().startsWith("http://") && !newTargetUrl.toLowerCase().startsWith("https://")) {
                    newTargetUrl = "http://" + newTargetUrl;
                }

                if (!Objects.equals(currentTargetBaseUrl, newTargetUrl) && isCurrentlyPolling) {
                    Log.i(TAG, "Target URL changed from " + currentTargetBaseUrl + " to " + newTargetUrl + ". Stopping old poll.");
                    stopPollingData(); // Stop polling the old URL
                }
                currentTargetBaseUrl = newTargetUrl; // Set the new target

                if (!isServiceRunningAsForeground) {
                    startForegroundServiceWithNotification("Polling " + getHostFromUrl(currentTargetBaseUrl));
                } else {
                    // Already foreground, just update notification if target changed or polling restarts
                     updateServiceNotification("Polling " + getHostFromUrl(currentTargetBaseUrl));
                }
                startPollingData(); // Start (or restart) polling for the currentTargetBaseUrl
                break;

            case ACTION_STOP_POLLING:
                Log.d(TAG, "onStartCommand: Handling ACTION_STOP_POLLING.");
                stopPollingData();
                if (isServiceRunningAsForeground) { // If foreground, update notification to show polling is paused
                    updateServiceNotification("Polling Paused for " + getHostFromUrl(currentTargetBaseUrl));
                }
                break;

            case ACTION_STOP_FOREGROUND_SERVICE:
                Log.d(TAG, "onStartCommand: Handling ACTION_STOP_FOREGROUND_SERVICE.");
                stopServiceAndForeground(); // This also stops polling
                return START_NOT_STICKY; // Explicitly stopped, don't auto-restart

            default:
                Log.w(TAG, "onStartCommand: Unhandled action: " + action);
                break;
        }
        return START_STICKY; // Default to sticky, service attempts to restart if killed
    }

    private String getHostFromUrl(String urlString) {
        if (urlString == null) return "Unknown Host";
        try {
            java.net.URL url = new java.net.URL(urlString);
            String host = url.getHost();
            int port = url.getPort();
            // Only include port if it's not default for the scheme (80 for http, 443 for https)
            if (port != -1 && port != url.getDefaultPort()) {
                return host + ":" + port;
            }
            return host;
        } catch (java.net.MalformedURLException e) {
            // If it's not a full URL, it might be just the hostname/IP, return it as is
            return urlString.replaceFirst("^(http://|https://)", "");
        }
    }


    private void startPollingData() {
        if (currentTargetBaseUrl == null || currentTargetBaseUrl.isEmpty()) {
            Log.e(TAG, "startPollingData: Cannot start, target base URL is not set.");
            sendBroadcastStatus("Error: Target URL not set for polling.");
            return;
        }
        if (!isCurrentlyPolling) {
            isCurrentlyPolling = true;
            pollingHandler.post(pollingRunnable); // Start immediately
            Log.i(TAG, "startPollingData: Polling started for " + currentTargetBaseUrl);
            sendBroadcastStatus("Polling started for " + getHostFromUrl(currentTargetBaseUrl));
            if(isServiceRunningAsForeground) { // Update notification if already foreground
                updateServiceNotification("Polling active: " + getHostFromUrl(currentTargetBaseUrl));
            }
        } else {
            Log.d(TAG, "startPollingData: Polling already active for " + currentTargetBaseUrl);
            // Ensure notification is correct if we re-enter this path
            if(isServiceRunningAsForeground) {
                updateServiceNotification("Polling active: " + getHostFromUrl(currentTargetBaseUrl));
            }
        }
    }

    private void stopPollingData() {
        if (isCurrentlyPolling) {
            isCurrentlyPolling = false;
            pollingHandler.removeCallbacks(pollingRunnable);
            Log.i(TAG, "stopPollingData: Polling stopped for " + currentTargetBaseUrl);
            sendBroadcastStatus("Polling stopped for " + getHostFromUrl(currentTargetBaseUrl));
        }
    }

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (isCurrentlyPolling && currentTargetBaseUrl != null && !currentTargetBaseUrl.isEmpty()) {
                fetchDataFromServer(currentTargetBaseUrl, DATA_ENDPOINT, DATA_TYPE_MIC);
                pollingHandler.postDelayed(this, POLLING_INTERVAL_MS);
            } else {
                // If polling is stopped or URL becomes null, ensure this runnable doesn't reschedule
                isCurrentlyPolling = false;
                Log.d(TAG, "PollingRunnable: Stopping as isCurrentlyPolling is false or currentTargetBaseUrl is null.");
            }
        }
    };

    private void fetchDataFromServer(String baseUrl, String endpoint, final String dataType) {
        String url = baseUrl + (endpoint.equals("/") && baseUrl.endsWith("/") ? "" : endpoint);
        Log.d(TAG, "HTTP Polling: GET " + url + " for dataType: " + dataType);

        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "HTTP poll " + url + " onFailure: " + e.getMessage());
                sendBroadcastStatus("Error polling " + getHostFromUrl(baseUrl) + ": " + e.getMessage().substring(0, Math.min(e.getMessage().length(), 50)));
                // Consider if polling should stop on repeated failures
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : null;
                final int responseCode = response.code();
                // Ensure response body is closed to prevent resource leaks
                // OkHttp's try-with-resources on Response object handles this in modern versions,
                // but explicit close after reading body is safest. Here, string() consumes and closes.
                // response.close(); // Not needed if response.body().string() is called

                if (response.isSuccessful() && responseBodyString != null) {
                    Log.d(TAG, "HTTP poll " + url + " onResponse (" + responseCode + "): " + responseBodyString.substring(0, Math.min(responseBodyString.length(), 100)));
                    sendBroadcastData(dataType, responseBodyString); // Broadcast raw JSON data

                    if (DATA_TYPE_MIC.equals(dataType)) {
                        try {
                            JSONObject json = new JSONObject(responseBodyString);
                            // ESP sends "db_calibrated", "rms", "status", "error"
                            double dbCalibrated = json.optDouble("db_calibrated", -999.0);
                            String espError = json.optString("error", null);

                            // App-side notification/alert logic (from HomeFragment settings)
                            boolean appNotificationsEnabled = appAlertSettingsPrefs.getBoolean(PREF_APP_ALERTS_ENABLED, DEFAULT_NOTIFICATIONS_ENABLED);
                            int appAlertThresholdDb = appAlertSettingsPrefs.getInt(PREF_APP_ALERT_LEVEL_DB, DEFAULT_APP_ALERT_THRESHOLD_DB);

                            Log.v(TAG, "Data check: dbCal=" + dbCalibrated + ", AppNotifEnabled=" + appNotificationsEnabled +
                                       ", AppAlertThreshold=" + appAlertThresholdDb + "dB");

                            if (appNotificationsEnabled && (espError == null || espError.isEmpty() || "null".equalsIgnoreCase(espError)) && dbCalibrated >= appAlertThresholdDb && dbCalibrated != -999.0) {
                                String notificationMsg = String.format(Locale.getDefault(),
                                        "Loud Noise: %.1f dB detected on %s (App Alert >= %d dB)",
                                        dbCalibrated, getHostFromUrl(baseUrl), appAlertThresholdDb);
                                // Use a unique ID for each ESP to avoid notifications overwriting each other if multiple are loud
                                int notificationId = MESSAGE_NOTIFICATION_ID_OFFSET + Math.abs(getHostFromUrl(baseUrl).hashCode() % 1000);
                                showDataNotification("Loud Noise Alert!", notificationMsg, notificationId);

                                // Custom sound alert logic (using serviceControlPrefs)
                                boolean customSoundEnabled = serviceControlPrefs.getBoolean(PREF_CUSTOM_ALERT_SOUND_ENABLED, DEFAULT_CUSTOM_SOUND_ENABLED);
                                String customSoundUriString = serviceControlPrefs.getString(PREF_CUSTOM_ALERT_SOUND_URI, null);
                                String customSoundFileName = "None";
                                if (customSoundUriString != null) {
                                     customSoundFileName = getFileNameFromContentUri(Uri.parse(customSoundUriString));
                                }

                                logSensorTriggerToFile(String.format(Locale.getDefault(),
                                        "App-Side Alert: ESP(%s) Level: %.1f dB (Threshold: >= %d dB). Visual notification shown. Custom sound: %s (Enabled: %b, URI Set: %b)",
                                        getHostFromUrl(baseUrl), dbCalibrated, appAlertThresholdDb, customSoundFileName, customSoundEnabled, (customSoundUriString != null)));

                                if (customSoundEnabled && customSoundUriString != null) {
                                    Uri soundUri = Uri.parse(customSoundUriString);
                                    Intent alertSoundIntent = new Intent(HttpPollingService.this, AlertSoundService.class);
                                    alertSoundIntent.setAction(AlertSoundService.ACTION_PLAY_CUSTOM_SOUND);
                                    alertSoundIntent.putExtra(AlertSoundService.EXTRA_SOUND_URI, soundUri.toString());
                                    alertSoundIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    ContextCompat.startForegroundService(HttpPollingService.this, alertSoundIntent);
                                    Log.i(TAG, "Custom alert sound service started for: " + customSoundFileName);
                                }
                            }
                        } catch (JSONException e_json) {
                             Log.e(TAG, "Error parsing " + dataType + " JSON for app-side notification: " + e_json.getMessage());
                        }
                    }

                } else {
                    Log.e(TAG, "HTTP poll " + url + " onResponse Error: " + responseCode + " - " + response.message());
                    sendBroadcastStatus("Error polling " + getHostFromUrl(baseUrl) + ": " + responseCode);
                }
            }
        });
    }

    private String getFileNameFromContentUri(Uri uri) {
        if (uri == null) return "Unknown URI";
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        result = cursor.getString(displayNameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file name from content URI: " + uri, e);
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
                result = uri.toString();
            }
        }
        return result != null ? result : "File";
    }

    private void logSensorTriggerToFile(String message) {
        File logFile = new File(getFilesDir(), getString(R.string.log_sensor_trigger_file_name));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logEntry = "[" + timestamp + "] " + message + "\n";

        try (FileOutputStream fos = new FileOutputStream(logFile, true); // true for append mode
             OutputStreamWriter osw = new OutputStreamWriter(fos)) {
            osw.write(logEntry);
            Log.i(TAG, "Logged to sensor trigger file: " + message.substring(0, Math.min(message.length(),100)));
        } catch (IOException e) {
            Log.e(TAG, "Error writing to sensor trigger log file: " + logFile.getAbsolutePath(), e);
        }
    }


    private void startForegroundServiceWithNotification(String statusText) {
        Log.d(TAG, "startForegroundServiceWithNotification: statusText='" + statusText + "'");
        Intent notificationIntent = new Intent(this, MainActivity.class); // Tapping notification opens MainActivity
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " " + getString(R.string.http_sync_notification_title_suffix))
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_stat_service) // Ensure this drawable exists
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Makes it non-dismissable by user swipe
                .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for service status
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification);
            }
            isServiceRunningAsForeground = true;
            Log.i(TAG, "startForegroundServiceWithNotification: Service started in foreground. Notification: '" + statusText + "'");
        } catch (Exception e) { // Catch more general exceptions during startForeground
            Log.e(TAG, "startForegroundServiceWithNotification: Error starting foreground: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            isServiceRunningAsForeground = false;
            // Potentially stopSelf() if foreground is critical and failed
        }
    }

    private void updateServiceNotification(String text) {
        Log.d(TAG, "updateServiceNotification: text='" + text + "'. isServiceRunningAsForeground=" + isServiceRunningAsForeground);
        if (!isServiceRunningAsForeground) { // Only update if actually in foreground mode
            Log.w(TAG, "updateServiceNotification: Not in foreground, cannot update notification.");
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
             Log.e(TAG, "updateServiceNotification: NotificationManager is null.");
            return;
        }
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " " + getString(R.string.http_sync_notification_title_suffix))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_service)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            manager.notify(SERVICE_NOTIFICATION_ID, notification);
            Log.d(TAG, "Service notification updated: " + text);
        } catch (Exception e) {
            Log.e(TAG, "updateServiceNotification: Error notifying: " + e.getMessage(), e);
        }
    }

    private void stopServiceAndForeground() {
        Log.i(TAG, "stopServiceAndForeground: Initiated.");
        stopPollingData(); // Stop any active polling loops
        if (isServiceRunningAsForeground) {
            Log.d(TAG, "stopServiceAndForeground: Stopping foreground state now.");
            stopForeground(true); // True = remove notification
            isServiceRunningAsForeground = false;
        }
        currentTargetBaseUrl = null; // Clear the target
        stopSelf(); // Stop the service instance
        sendBroadcastStatus("Service stopped.");
        Log.i(TAG, "stopServiceAndForeground: Service instance stopped and foreground state removed.");
    }

    private void sendBroadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.v(TAG, "sendBroadcastStatus >> UI: " + status);
    }

    private void sendBroadcastData(String dataType, String jsonString) {
        Intent intent = new Intent(ACTION_DATA_RECEIVED);
        intent.putExtra(EXTRA_DATA_TYPE, dataType);
        intent.putExtra(EXTRA_DATA_JSON_STRING, jsonString);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.v(TAG, "sendBroadcastData (" + dataType + ") >> UI: " + jsonString.substring(0, Math.min(jsonString.length(),100)));
    }

    private void createNotificationChannel(String channelId, String channelName, int importance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
            if (NOTIFICATION_CHANNEL_ID_MESSAGES.equals(channelId)) {
                channel.setDescription(getString(R.string.channel_description_http_alerts)); // Use specific string
                channel.enableLights(true);
                channel.enableVibration(true); // Ensure vibration is enabled for alert channel
            } else if (NOTIFICATION_CHANNEL_ID_SERVICE.equals(channelId)) {
                 channel.setDescription(getString(R.string.http_polling_service_channel_desc));
            }
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "createNotificationChannel: Channel '" + channelId + "' ("+channelName+") created/updated.");
            } else {
                Log.e(TAG, "createNotificationChannel: NotificationManager is null for channel '" + channelId + "'");
            }
        }
    }

    private void showDataNotification(String title, String message, int notificationId) {
        // Check for POST_NOTIFICATIONS permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "showDataNotification: POST_NOTIFICATIONS permission NOT granted. Cannot show alert notification.");
                // Optionally send a broadcast so MainActivity can inform user or request permission
                return;
            }
        }

        Intent intent = new Intent(this, MainActivity.class); // Open MainActivity on tap
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); // Good practice for notification intents
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent, // Use unique request code for PendingIntent
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message) // Ensure this drawable exists
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // High priority for alerts
            .setAutoCancel(true) // Notification dismissed when tapped
            .setContentIntent(pendingIntent)
            .setDefaults(Notification.DEFAULT_ALL); // Use default sound, lights, vibrate (custom sound handled separately by AlertSoundService)
        
        NotificationManagerCompat.from(this).notify(notificationId, builder.build()); // Use unique ID
        Log.i(TAG, "showDataNotification: Sent. Title='" + title + "', ID=" + notificationId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: Called, returning null as this is not a bound service.");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: Service Destroying. Current target: " + currentTargetBaseUrl);
        stopPollingData(); // Ensure polling callbacks are removed
        if (isServiceRunningAsForeground) { // Should have been handled by stopServiceAndForeground
            stopForeground(true);
            isServiceRunningAsForeground = false;
        }
        if (httpClient != null) {
            Log.d(TAG, "onDestroy: Shutting down OkHttpClient dispatcher and connection pool.");
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            try {
                if (httpClient.cache() != null) {
                    httpClient.cache().close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing OkHttp cache during onDestroy", e);
            }
        }
        sendBroadcastStatus("Service destroyed.");
        Log.i(TAG, "onDestroy: Service fully destroyed.");
        super.onDestroy();
    }
}