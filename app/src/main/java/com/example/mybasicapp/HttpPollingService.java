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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
// import java.util.Objects; // Not strictly needed here anymore
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
    public static final String ACTION_START_POLLING = "com.example.mybasicapp.ACTION_START_POLLING";
    public static final String ACTION_STOP_POLLING = "com.example.mybasicapp.ACTION_STOP_POLLING";
    public static final String EXTRA_BASE_URL = "EXTRA_BASE_URL";

    public static final String ACTION_STATUS_UPDATE = "com.example.mybasicapp.ACTION_HTTP_STATUS_UPDATE";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";
    public static final String ACTION_DATA_RECEIVED = "com.example.mybasicapp.ACTION_HTTP_DATA_RECEIVED";
    public static final String EXTRA_DATA_TYPE = "EXTRA_DATA_TYPE";
    public static final String EXTRA_DATA_JSON_STRING = "EXTRA_DATA_JSON_STRING";

    private static final String NOTIFICATION_CHANNEL_ID_SERVICE = "http_polling_service_status_channel";
    private static final String NOTIFICATION_CHANNEL_ID_MESSAGES = "esp32_http_notifications";
    private static final int SERVICE_NOTIFICATION_ID = 2;
    private static final int MESSAGE_NOTIFICATION_ID = 102; // Unique ID for distance alerts

    private OkHttpClient httpClient;
    private Handler pollingHandler = new Handler(Looper.getMainLooper());
    private boolean isServiceRunningAsForeground = false;
    private boolean isCurrentlyPolling = false;
    private String currentBaseUrl;

    private static final long POLLING_INTERVAL_MS = 3000;
    private static final String DISTANCE_ENDPOINT = "/get_distance";

    // SharedPreferences keys (must match MainActivity)
    private static final String PREFS_NAME = "MrCooperESP_Prefs";
    private static final String PREF_TRIGGER_DISTANCE = "trigger_distance_cm";
    private static final String PREF_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final int DEFAULT_TRIGGER_DISTANCE = 50; // cm
    private static final boolean DEFAULT_NOTIFICATIONS_ENABLED = false;
    private SharedPreferences sharedPreferences;


    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: Service Creating");
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_SERVICE, "ESP32 HTTP Polling Service", NotificationManager.IMPORTANCE_LOW);
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_MESSAGES, getString(R.string.channel_name_http), NotificationManager.IMPORTANCE_HIGH);

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Log.d(TAG, "onCreate: Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Null intent or action. Flags=" + flags + ", StartId=" + startId);
            if (!isServiceRunningAsForeground) {
                startForegroundServiceWithNotification("Service Initializing (Restart)...");
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Action='" + action + "', Flags=" + flags + ", StartId=" + startId);

        switch (action) {
            case ACTION_START_FOREGROUND_SERVICE:
                Log.d(TAG, "onStartCommand: Handling ACTION_START_FOREGROUND_SERVICE. isServiceRunningAsForeground=" + isServiceRunningAsForeground);
                currentBaseUrl = intent.getStringExtra(EXTRA_BASE_URL);
                if (currentBaseUrl == null || currentBaseUrl.isEmpty()){
                    Log.e(TAG, "ACTION_START_FOREGROUND_SERVICE: Base URL is missing!");
                    sendBroadcastStatus("Error: Base URL missing for service start");
                    stopSelf();
                    return START_NOT_STICKY;
                }
                if (!isServiceRunningAsForeground) {
                    startForegroundServiceWithNotification("Service Active. Polling ESP32 at " + getHostFromUrl(currentBaseUrl));
                }
                if (!isCurrentlyPolling) {
                     startPollingData();
                }
                break;
            case ACTION_STOP_FOREGROUND_SERVICE:
                Log.d(TAG, "onStartCommand: Handling ACTION_STOP_FOREGROUND_SERVICE.");
                stopPollingData();
                stopServiceAndForeground();
                return START_NOT_STICKY;
            case ACTION_START_POLLING:
                Log.d(TAG, "onStartCommand: Handling ACTION_START_POLLING.");
                currentBaseUrl = intent.getStringExtra(EXTRA_BASE_URL);
                if (currentBaseUrl == null || currentBaseUrl.isEmpty()){
                     Log.e(TAG, "ACTION_START_POLLING: Base URL is missing!");
                     sendBroadcastStatus("Error: Base URL missing for polling");
                     break;
                }
                if (!isServiceRunningAsForeground) {
                     startForegroundServiceWithNotification("Polling ESP32 at " + getHostFromUrl(currentBaseUrl));
                }
                startPollingData();
                break;
            case ACTION_STOP_POLLING:
                Log.d(TAG, "onStartCommand: Handling ACTION_STOP_POLLING.");
                stopPollingData();
                updateServiceNotification("Polling Paused. Service still active.");
                break;
            default:
                Log.w(TAG, "onStartCommand: Unhandled action: " + action);
                break;
        }
        return START_STICKY;
    }

    private String getHostFromUrl(String urlString) {
        if (urlString == null) return "Unknown Host";
        try {
            java.net.URL url = new java.net.URL(urlString);
            return url.getHost() + (url.getPort() != -1 && url.getPort() != 80 && url.getPort() != url.getDefaultPort() ? ":" + url.getPort() : "");
        } catch (java.net.MalformedURLException e) {
            return urlString;
        }
    }


    private void startPollingData() {
        if (currentBaseUrl == null || currentBaseUrl.isEmpty()) {
            Log.e(TAG, "startPollingData: Cannot start, base URL is not set.");
            sendBroadcastStatus("Error: Base URL not set for polling.");
            return;
        }
        if (!isCurrentlyPolling) {
            isCurrentlyPolling = true;
            pollingHandler.post(pollingRunnable);
            Log.i(TAG, "startPollingData: Polling started for " + currentBaseUrl);
            sendBroadcastStatus("Polling started for " + getHostFromUrl(currentBaseUrl));
            updateServiceNotification("Polling active: " + getHostFromUrl(currentBaseUrl));
        } else {
            Log.d(TAG, "startPollingData: Polling already active.");
        }
    }

    private void stopPollingData() {
        if (isCurrentlyPolling) {
            isCurrentlyPolling = false;
            pollingHandler.removeCallbacks(pollingRunnable);
            Log.i(TAG, "stopPollingData: Polling stopped.");
            sendBroadcastStatus("Polling stopped.");
        }
    }

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (isCurrentlyPolling && currentBaseUrl != null && !currentBaseUrl.isEmpty()) {
                fetchDataFromServer(DISTANCE_ENDPOINT, "distance");
                pollingHandler.postDelayed(this, POLLING_INTERVAL_MS);
            }
        }
    };

    private void fetchDataFromServer(String endpoint, final String dataType) {
        if (currentBaseUrl == null) {
            Log.e(TAG, "fetchDataFromServer: currentBaseUrl is null. Cannot fetch.");
            return;
        }
        String url = currentBaseUrl + endpoint;
        Log.d(TAG, "HTTP Polling: GET " + url);

        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "HTTP poll " + endpoint + " onFailure: " + e.getMessage());
                sendBroadcastStatus("Error polling " + dataType + ": " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                final String responseBodyString = response.body() != null ? response.body().string() : null;
                final int responseCode = response.code();
                response.close();

                if (response.isSuccessful() && responseBodyString != null) {
                    Log.d(TAG, "HTTP poll " + endpoint + " onResponse (" + responseCode + "): " + responseBodyString.substring(0, Math.min(responseBodyString.length(), 100)));
                    sendBroadcastData(dataType, responseBodyString);

                    if ("distance".equals(dataType)) {
                        try {
                            JSONObject json = new JSONObject(responseBodyString);
                            double distanceValCm = json.optDouble("distance_cm", -3.0);

                            // Check for notification trigger
                            boolean notificationsEnabled = sharedPreferences.getBoolean(PREF_NOTIFICATIONS_ENABLED, DEFAULT_NOTIFICATIONS_ENABLED);
                            int triggerDistanceCm = sharedPreferences.getInt(PREF_TRIGGER_DISTANCE, DEFAULT_TRIGGER_DISTANCE);

                            Log.d(TAG, "Notification check: Enabled=" + notificationsEnabled +
                                       ", TriggerDist=" + triggerDistanceCm + "cm" +
                                       ", CurrentDist=" + distanceValCm + "cm");

                            if (notificationsEnabled && distanceValCm >= 0 && distanceValCm <= triggerDistanceCm) {
                                String notificationMsg = String.format(Locale.getDefault(),
                                        "Object detected at %.1f cm (Trigger: <= %d cm)",
                                        distanceValCm, triggerDistanceCm);
                                showDataNotification("Motion Alert", notificationMsg);
                                Log.i(TAG, "Notification triggered: " + notificationMsg);
                            }

                        } catch (JSONException e_json) {
                             Log.e(TAG, "Error parsing " + dataType + " JSON for notification: " + e_json.getMessage());
                        }
                    }

                } else {
                    Log.e(TAG, "HTTP poll " + endpoint + " onResponse Error: " + responseCode + " - " + response.message());
                    sendBroadcastStatus("Error polling " + dataType + ": " + responseCode);
                }
            }
        });
    }


    private void startForegroundServiceWithNotification(String statusText) {
        Log.d(TAG, "startForegroundServiceWithNotification: statusText='" + statusText + "'");
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " HTTP Sync")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_stat_service)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
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
        } catch (Exception e) {
            Log.e(TAG, "startForegroundServiceWithNotification: Error: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            isServiceRunningAsForeground = false;
        }
    }

    private void updateServiceNotification(String text) {
        Log.d(TAG, "updateServiceNotification: text='" + text + "'. isServiceRunningAsForeground=" + isServiceRunningAsForeground);
        if (!isServiceRunningAsForeground) {
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
                .setContentTitle(getString(R.string.app_name) + " HTTP Sync")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_service)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        try {
            manager.notify(SERVICE_NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "updateServiceNotification: Error: " + e.getMessage(), e);
        }
    }

    private void stopServiceAndForeground() {
        Log.i(TAG, "stopServiceAndForeground: Initiated.");
        stopPollingData();
        if (isServiceRunningAsForeground) {
            Log.d(TAG, "stopServiceAndForeground: Stopping foreground state now.");
            stopForeground(true);
            isServiceRunningAsForeground = false;
        }
        stopSelf();
        Log.i(TAG, "stopServiceAndForeground: Service instance stopped.");
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
                channel.setDescription(getString(R.string.channel_description_http));
                // For alert notifications, enable lights, vibration, etc.
                channel.enableLights(true);
                channel.enableVibration(true);
                // channel.setLightColor(Color.RED); // Example
            } else {
                 channel.setDescription("Channel for ESP32 HTTP Polling Service status.");
            }
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "createNotificationChannel: Channel '" + channelId + "' created/updated.");
            } else {
                Log.e(TAG, "createNotificationChannel: NotificationManager is null for channel '" + channelId + "'");
            }
        }
    }

    private void showDataNotification(String title, String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "showDataNotification: POST_NOTIFICATIONS permission NOT granted. Cannot show.");
                // Optionally send a broadcast to MainActivity to request permission if it's not active
                return;
            }
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // Unique request code for PendingIntent to ensure it's new/updated if message changes
        PendingIntent pendingIntent = PendingIntent.getActivity(this, MESSAGE_NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title).setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(Notification.DEFAULT_ALL); // Uses default sound, vibrate, light
        
        NotificationManagerCompat.from(this).notify(MESSAGE_NOTIFICATION_ID, builder.build());
        Log.d(TAG, "showDataNotification: Sent. Title='" + title + "'");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: Called, returning null.");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: Service Destroying.");
        stopPollingData();
        if (isServiceRunningAsForeground) {
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
                Log.e(TAG, "Error closing OkHttp cache", e);
            }
        }
        Log.i(TAG, "onDestroy: Service fully destroyed.");
        super.onDestroy();
    }
}