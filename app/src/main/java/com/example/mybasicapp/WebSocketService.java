package com.example.mybasicapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WebSocketService extends Service {

    private static final String TAG = "WebSocketService";
    public static final String ACTION_START_FOREGROUND_SERVICE = "ACTION_START_FOREGROUND_SERVICE";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "ACTION_STOP_FOREGROUND_SERVICE";
    public static final String ACTION_CONNECT = "ACTION_CONNECT";
    public static final String ACTION_DISCONNECT = "ACTION_DISCONNECT";
    public static final String EXTRA_IP_ADDRESS = "EXTRA_IP_ADDRESS"; // This will be the full ws:// URL

    public static final String ACTION_STATUS_UPDATE = "com.example.mybasicapp.STATUS_UPDATE";
    public static final String ACTION_MESSAGE_RECEIVED = "com.example.mybasicapp.MESSAGE_RECEIVED";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";
    public static final String EXTRA_MESSAGE_TITLE = "EXTRA_MESSAGE_TITLE";
    public static final String EXTRA_MESSAGE_BODY = "EXTRA_MESSAGE_BODY";


    private static final String NOTIFICATION_CHANNEL_ID_SERVICE = "web_socket_service_channel";
    private static final String NOTIFICATION_CHANNEL_ID_MESSAGES = "esp32_notifications"; // For actual alert notifications
    private static final int SERVICE_NOTIFICATION_ID = 1;
    private static final int MESSAGE_NOTIFICATION_ID = 101; // For messages from ESP32

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private String currentWebSocketUrl; // Changed from currentIpAddress to reflect it's a full URL
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isServiceStarted = false;
    private int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 5; // Max number of retries
    private static final long RETRY_DELAY_MS = 5000; // 5 seconds delay

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        httpClient = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS) // Keep the connection alive
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_SERVICE, "WebSocket Service Status", NotificationManager.IMPORTANCE_LOW);
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_MESSAGES, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            Log.d(TAG, "onStartCommand, Action: " + action);

            if (ACTION_START_FOREGROUND_SERVICE.equals(action)) {
                if (!isServiceStarted) {
                    startForegroundServiceWithNotification("Service Active. Not connected.");
                    isServiceStarted = true;
                }
            } else if (ACTION_STOP_FOREGROUND_SERVICE.equals(action)) {
                stopService();
                return START_NOT_STICKY;
            } else if (ACTION_CONNECT.equals(action) && isServiceStarted) {
                String wsUrlFromIntent = intent.getStringExtra(EXTRA_IP_ADDRESS); // This is the full URL
                if (wsUrlFromIntent != null && !wsUrlFromIntent.isEmpty()) {
                    currentWebSocketUrl = wsUrlFromIntent; // Store the full URL
                    connectWebSocket(currentWebSocketUrl);
                    retryCount = 0; // Reset retry count on new manual connect attempt
                } else {
                    Log.e(TAG, "WebSocket URL is null or empty in ACTION_CONNECT");
                    sendBroadcastStatus("Error: WebSocket URL missing");
                }
            } else if (ACTION_DISCONNECT.equals(action) && isServiceStarted) {
                disconnectWebSocket();
            }
        }
        return START_STICKY;
    }


    private void startForegroundServiceWithNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " Service")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_stat_service)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification);
        }
        Log.d(TAG, "Service started in foreground.");
    }

    private void updateServiceNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !isServiceStarted) return;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " Service")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_service)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        manager.notify(SERVICE_NOTIFICATION_ID, notification);
    }


    private void connectWebSocket(String wsUrl) {
        if (wsUrl == null || wsUrl.isEmpty()) {
            Log.e(TAG, "connectWebSocket called with null or empty URL.");
            sendBroadcastStatus("Error: Invalid ESP32 Address");
            return;
        }
        if (webSocket != null) {
            Log.d(TAG, "Closing existing WebSocket before reconnecting.");
            webSocket.close(1001, "Client reconnecting"); // 1001 indicates going away
            webSocket = null;
        }

        Request request = new Request.Builder().url(wsUrl).build();
        final String displayUrl = wsUrl.replaceFirst("ws://", "").replaceFirst("/ws", ""); // For shorter display
        sendBroadcastStatus("Connecting to " + displayUrl + "...");
        updateServiceNotification("Connecting to ESP32...");
        Log.i(TAG, "Attempting to connect to: " + wsUrl);

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                super.onOpen(ws, response);
                Log.i(TAG, "WebSocket Opened with " + displayUrl);
                sendBroadcastStatus("Connected to ESP32");
                updateServiceNotification("Connected to ESP32");
                retryCount = 0; // Reset retry count on successful connection
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                super.onMessage(ws, text);
                Log.i(TAG, "Receiving from ESP32: " + text);
                try {
                    JSONObject json = new JSONObject(text);
                    String event = json.optString("event");

                    if ("motion_detected".equals(event)) {
                        double distanceCm = json.optDouble("distance_cm", -1.0);
                        //double thresholdCm = json.optDouble("threshold_cm", -1.0); // Also available
                        //long timestamp = json.optLong("timestamp", 0); // Also available

                        String title = "Motion Alert!";
                        String messageBody = String.format(Locale.getDefault(), "Motion detected at %.1f cm.", distanceCm);

                        sendBroadcastMessage(title, messageBody);
                        showDataNotification(title, messageBody);
                    } else {
                        // Handle other event types or generic messages if your ESP32 sends them
                        String title = json.optString("title", "ESP32 Info");
                        String messageBody = json.optString("message", text); // Fallback to raw text
                        sendBroadcastMessage(title, messageBody);
                        // Optionally show notification for other events too
                        // showDataNotification(title, messageBody);
                        Log.d(TAG, "Received non-motion event or generic message: " + text);
                    }

                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing JSON from WebSocket: " + e.getMessage() + ". Raw: " + text);
                    // If not JSON, treat as a simple message
                    sendBroadcastMessage("ESP32 Message", text);
                    showDataNotification("ESP32 Message", text);
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                super.onMessage(ws, bytes);
                Log.i(TAG, "Receiving bytes: " + bytes.hex() + " (not handled)");
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                super.onClosing(ws, code, reason);
                Log.i(TAG, "WebSocket Closing: " + code + " / " + reason);
                // ws.close(1000, null); // Not needed, OkHttp handles this
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                super.onClosed(ws, code, reason);
                Log.i(TAG, "WebSocket Closed: " + code + " / " + reason + " for URL: " + wsUrl);
                sendBroadcastStatus("Disconnected");
                updateServiceNotification("Disconnected from ESP32");
                if (WebSocketService.this.webSocket == ws) { // Ensure it's the current socket
                    WebSocketService.this.webSocket = null;
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                super.onFailure(ws, t, response);
                String errorMsg = (t != null && t.getMessage() != null) ? t.getMessage() : "Unknown connection error";
                Log.e(TAG, "WebSocket Failure for " + wsUrl + ": " + errorMsg, t);
                sendBroadcastStatus("Connection Failed: " + errorMsg);
                updateServiceNotification("Connection Failed");
                if (WebSocketService.this.webSocket == ws) { // Ensure it's the current socket
                    WebSocketService.this.webSocket = null;
                }

                // Implement retry logic with backoff if connection fails and service is still running
                if (isServiceStarted && currentWebSocketUrl != null && !currentWebSocketUrl.isEmpty()) {
                    if (retryCount < MAX_RETRY_COUNT) {
                        retryCount++;
                        long delay = RETRY_DELAY_MS * retryCount; // Exponential backoff could be used too
                        Log.d(TAG, "Retrying connection to: " + currentWebSocketUrl + " (Attempt " + retryCount + ") in " + delay + "ms");
                        sendBroadcastStatus("Connection Failed. Retrying (" + retryCount + "/" + MAX_RETRY_COUNT + ")...");
                        updateServiceNotification("Connection Failed. Retrying...");
                        handler.postDelayed(() -> {
                            if (isServiceStarted) { // Check again if service is still started before retrying
                                connectWebSocket(currentWebSocketUrl);
                            }
                        }, delay);
                    } else {
                        Log.e(TAG, "Max retry attempts reached for " + currentWebSocketUrl + ". Giving up.");
                        sendBroadcastStatus("Connection Failed: Max retries reached.");
                        updateServiceNotification("Connection Failed. Max retries.");
                    }
                }
            }
        });
    }

    private void disconnectWebSocket() {
        handler.removeCallbacksAndMessages(null); // Cancel any pending retries
        retryCount = 0;
        if (webSocket != null) {
            webSocket.close(1000, "User requested disconnect");
            webSocket = null; // Important to nullify immediately
        }
        sendBroadcastStatus("Disconnected by user");
        updateServiceNotification("Disconnected by user");
        Log.i(TAG, "WebSocket Disconnected by user action.");
        currentWebSocketUrl = null; // Clear the URL so it doesn't try to auto-reconnect to old one
    }


    private void sendBroadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendBroadcastMessage(String title, String body) {
        Intent intent = new Intent(ACTION_MESSAGE_RECEIVED);
        intent.putExtra(EXTRA_MESSAGE_TITLE, title);
        intent.putExtra(EXTRA_MESSAGE_BODY, body);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void createNotificationChannel(String channelId, String channelName, int importance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, channelName, importance);
            if (channelId.equals(NOTIFICATION_CHANNEL_ID_MESSAGES)) {
                channel.setDescription(getString(R.string.channel_description));
                // Optional: Configure sound, vibration, etc. for alert notifications
                // channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null);
                // channel.enableVibration(true);
                // channel.setVibrationPattern(new long[]{0, 500, 200, 500});
            } else {
                 channel.setDescription("Channel for ESP32 Sync Service status");
            }
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void showDataNotification(String title, String message) {
        // Check for POST_NOTIFICATIONS permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show data notification.");
                // Service cannot request permission. Activity must handle it.
                // Optionally send a broadcast to Activity to inform user about missing permission.
                return;
            }
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); // Bring to front or launch
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                (int) System.currentTimeMillis(), // Unique request code to ensure PendingIntent updates if extras change
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);


        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Ensure it pops up
                .setAutoCancel(true) // Dismiss notification when tapped
                .setContentIntent(pendingIntent)
                .setDefaults(Notification.DEFAULT_ALL); // Use default sound, vibration, light

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        try {
            // MESSAGE_NOTIFICATION_ID should be unique for each type of notification if you want them to stack or update correctly
            // Or use a dynamic ID if you want multiple distinct motion alerts. For now, one ID updates previous.
            notificationManager.notify(MESSAGE_NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            // This can happen if permissions are revoked while app is running.
            Log.e(TAG, "SecurityException while trying to post data notification: " + e.getMessage());
        }
    }


    private void stopService() {
        Log.d(TAG, "stopService called in WebSocketService");
        handler.removeCallbacksAndMessages(null); // Cancel any pending retries
        retryCount = 0;
        disconnectWebSocket(); // Ensure WebSocket is closed first
        if (isServiceStarted) {
            stopForeground(true); // true to remove the notification
            stopSelf(); // Stop the service instance
            isServiceStarted = false; // Update flag
            // sendBroadcastStatus("Service Stopped"); // MainActivity will update based on its own logic
        }
        Log.d(TAG, "WebSocketService fully stopped.");
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We are not using binding, so return null
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        stopService(); // Ensure everything is cleaned up
        if (httpClient != null) {
            // Gracefully shut down OkHttpClient resources
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            try {
                if (httpClient.cache() != null) {
                    httpClient.cache().close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing OkHttp cache", e);
            }
        }
        super.onDestroy();
    }
}