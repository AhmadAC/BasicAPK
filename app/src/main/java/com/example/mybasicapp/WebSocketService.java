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
    // Ensure action strings are unique if copied from elsewhere or define them in a central place
    public static final String ACTION_START_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_START_FOREGROUND_SERVICE";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_STOP_FOREGROUND_SERVICE";
    public static final String ACTION_CONNECT = "com.example.mybasicapp.ACTION_CONNECT";
    public static final String ACTION_DISCONNECT = "com.example.mybasicapp.ACTION_DISCONNECT";
    public static final String EXTRA_IP_ADDRESS = "EXTRA_IP_ADDRESS"; // This is the full ws:// URL

    public static final String ACTION_STATUS_UPDATE = "com.example.mybasicapp.ACTION_STATUS_UPDATE";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";
    public static final String ACTION_MESSAGE_RECEIVED = "com.example.mybasicapp.ACTION_MESSAGE_RECEIVED";
    public static final String EXTRA_MESSAGE_TITLE = "EXTRA_MESSAGE_TITLE";
    public static final String EXTRA_MESSAGE_BODY = "EXTRA_MESSAGE_BODY";

    private static final String NOTIFICATION_CHANNEL_ID_SERVICE = "web_socket_service_status_channel";
    private static final String NOTIFICATION_CHANNEL_ID_MESSAGES = "esp32_message_notifications";
    private static final int SERVICE_NOTIFICATION_ID = 1; // Must be > 0
    private static final int MESSAGE_NOTIFICATION_ID = 101;

    private OkHttpClient httpClient;
    private WebSocket webSocketClient;
    private String currentWebSocketUrl; // Stores the URL we are currently connected to or trying to connect to
    private Handler retryHandler = new Handler(Looper.getMainLooper());
    private boolean isServiceRunningAsForeground = false;
    private int connectionRetryCount = 0;
    private static final int MAX_CONNECTION_RETRIES = 5; // Max attempts before giving up (for a single connect sequence)
    private static final long INITIAL_RETRY_DELAY_MS = 3000; // 3 seconds
    private static final long MAX_RETRY_DELAY_MS = 30000; // 30 seconds max between retries

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        httpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false) // We handle retries manually
                .build();
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_SERVICE, "ESP32 Sync Service", NotificationManager.IMPORTANCE_LOW);
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_MESSAGES, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Null intent or action. Service might be restarting.");
            if (!isServiceRunningAsForeground) {
                 startForegroundServiceWithNotification("Service Initializing..."); // Ensure foreground state on restart
            }
            return START_STICKY; // Try to restart if killed
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand received Action: " + action);

        switch (action) {
            case ACTION_START_FOREGROUND_SERVICE:
                if (!isServiceRunningAsForeground) {
                    startForegroundServiceWithNotification("Service Active. Waiting for connection command.");
                }
                break;
            case ACTION_STOP_FOREGROUND_SERVICE:
                stopServiceAndForeground();
                return START_NOT_STICKY; // Do not restart if explicitly stopped
            case ACTION_CONNECT:
                if (!isServiceRunningAsForeground) {
                    startForegroundServiceWithNotification("Preparing to connect...");
                }
                String wsUrlFromIntent = intent.getStringExtra(EXTRA_IP_ADDRESS);
                if (wsUrlFromIntent != null && !wsUrlFromIntent.isEmpty()) {
                    // If already connected to a different URL, disconnect first
                    if (webSocketClient != null && currentWebSocketUrl != null && !currentWebSocketUrl.equals(wsUrlFromIntent)) {
                        Log.d(TAG, "Connecting to new URL. Closing existing connection to " + currentWebSocketUrl);
                        disconnectWebSocket("Connecting to new target: " + wsUrlFromIntent);
                    }
                    currentWebSocketUrl = wsUrlFromIntent;
                    connectionRetryCount = 0; // Reset retries for a new explicit connect command
                    cancelPendingRetries();
                    connectWebSocket(currentWebSocketUrl);
                } else {
                    Log.e(TAG, "ACTION_CONNECT: WebSocket URL is null or empty.");
                    sendBroadcastStatus("Error: WebSocket URL missing for connect command");
                    updateServiceNotification("Error: URL Missing");
                }
                break;
            case ACTION_DISCONNECT:
                disconnectWebSocket("User requested disconnect");
                updateServiceNotification("Disconnected by user."); // Update notification after explicit disconnect
                break;
            default:
                Log.w(TAG, "onStartCommand: Unhandled action: " + action);
                break;
        }
        return START_STICKY;
    }

    private void startForegroundServiceWithNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " Sync")
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
            Log.i(TAG, "Service started in foreground. Status: " + statusText);
        } catch (Exception e) { // Catch SecurityException, IllegalStateException, etc.
            Log.e(TAG, "Error starting foreground service: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
            isServiceRunningAsForeground = false;
            // Consider calling stopSelf() if foregrounding is mandatory and fails.
        }
    }

    private void updateServiceNotification(String text) {
        if (!isServiceRunningAsForeground) {
            Log.d(TAG, "Service not in foreground, persistent notification not updated. Current text would be: " + text);
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            Log.e(TAG, "NotificationManager is null, cannot update notification.");
            return;
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle(getString(R.string.app_name) + " Sync")
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
            Log.e(TAG, "Error updating service notification: " + e.getMessage(), e);
        }
    }

    private void connectWebSocket(final String wsUrlToConnect) {
        if (wsUrlToConnect == null || wsUrlToConnect.isEmpty()) {
            Log.e(TAG, "connectWebSocket: Aborted, URL is null or empty.");
            sendBroadcastStatus("Error: Invalid ESP32 URL for connection");
            updateServiceNotification("Error: Invalid URL");
            return;
        }

        if (webSocketClient != null) { // Should have been handled by disconnectWebSocket if URL changed
            Log.d(TAG, "connectWebSocket: Closing pre-existing client before connecting to " + wsUrlToConnect);
            webSocketClient.close(1001, "Client re-initiating connection");
            webSocketClient = null;
        }

        Request request = new Request.Builder().url(wsUrlToConnect).build();
        final String displayUrl = wsUrlToConnect.replaceFirst("ws://", "").replaceFirst("/ws", ""); // For display only
        String connectingMsg = "Connecting to: " + displayUrl +
                (connectionRetryCount > 0 ? " (Retrying " + connectionRetryCount + "/" + MAX_CONNECTION_RETRIES + ")" : "");
        Log.i(TAG, "Attempting WebSocket connection to: " + wsUrlToConnect + (connectionRetryCount > 0 ? " (Retry " + connectionRetryCount + ")" : ""));
        sendBroadcastStatus(connectingMsg);
        updateServiceNotification(connectingMsg);

        // Create a new WebSocket client for this attempt
        webSocketClient = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                super.onOpen(ws, response);
                 // Check if this callback is for the current connection attempt
                if (!wsUrlToConnect.equals(currentWebSocketUrl)) {
                    Log.w(TAG, "onOpen received for a stale URL: " + wsUrlToConnect + ". Current target: " + currentWebSocketUrl + ". Closing this connection.");
                    ws.close(1000, "Stale connection attempt.");
                    return;
                }
                Log.i(TAG, "WebSocket Opened with " + displayUrl);
                sendBroadcastStatus("Connected to: " + displayUrl);
                updateServiceNotification("Connected to: " + displayUrl);
                connectionRetryCount = 0; // Reset on successful connection
                cancelPendingRetries();
                // WebSocketService.this.webSocketClient = ws; // Already set before calling newWebSocket
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                super.onMessage(ws, text);
                if (!wsUrlToConnect.equals(currentWebSocketUrl) || WebSocketService.this.webSocketClient != ws) {
                     Log.w(TAG, "onMessage from a stale/unexpected WebSocket instance. Ignoring."); return;
                }
                Log.i(TAG, "WS Message from " + displayUrl + ": " + text);
                try {
                    JSONObject json = new JSONObject(text);
                    String event = json.optString("event", "unknown_event");

                    if ("motion_detected".equals(event)) {
                        double distanceCm = json.optDouble("distance_cm", -1.0);
                        String title = "Motion Alert!";
                        String messageBody = String.format(Locale.getDefault(), "Motion at %.1f cm detected.", distanceCm);
                        sendBroadcastMessage(title, messageBody);
                        showDataNotification(title, messageBody);
                    } else {
                        String title = json.optString("title", "ESP32 Info");
                        String messageBody = json.optString("message", text);
                        sendBroadcastMessage(title, messageBody);
                        Log.d(TAG, "Received event '" + event + "' or generic message: " + text);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing JSON from WebSocket: " + e.getMessage() + ". Raw: " + text);
                    sendBroadcastMessage("ESP32 Raw Message", text);
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                super.onMessage(ws, bytes);
                 if (!wsUrlToConnect.equals(currentWebSocketUrl) || WebSocketService.this.webSocketClient != ws) {
                     Log.w(TAG, "onMessage (bytes) from a stale/unexpected WebSocket instance. Ignoring."); return;
                }
                Log.i(TAG, "WS Receiving bytes from " + displayUrl + ": " + bytes.hex() + " (not processed)");
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                super.onClosing(ws, code, reason);
                Log.i(TAG, "WebSocket Closing: " + code + " / " + reason + " for URL: " + wsUrlToConnect);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                super.onClosed(ws, code, reason);
                Log.i(TAG, "WebSocket Closed: " + code + " / " + reason + " for URL: " + wsUrlToConnect);
                // Check if this closed event is for the WebSocket client we care about
                if (WebSocketService.this.webSocketClient == ws) {
                    WebSocketService.this.webSocketClient = null; // Clear the active client
                    String finalStatusMsg = "Disconnected from: " + displayUrl + " (Reason: " + reason + ", Code: " + code + ")";
                    sendBroadcastStatus(finalStatusMsg);
                    updateServiceNotification("Disconnected from ESP32");

                    // Retry logic: only if not a clean/intentional close and service is still running for this URL
                    boolean intentionalClose = (code == 1000 || code == 1001); // 1000=Normal, 1001=Going Away
                    if (!intentionalClose && currentWebSocketUrl != null && currentWebSocketUrl.equals(wsUrlToConnect) && isServiceRunningAsForeground) {
                        Log.d(TAG, "Connection to " + wsUrlToConnect + " closed unexpectedly (code " + code + "). Will attempt retry.");
                        handleConnectionFailure(wsUrlToConnect, "Connection closed (code " + code + ")");
                    } else if (currentWebSocketUrl == null) {
                        Log.d(TAG, "WebSocket closed but currentWebSocketUrl is null, likely explicit disconnect. No retry.");
                    } else if (!currentWebSocketUrl.equals(wsUrlToConnect)) {
                        Log.d(TAG, "WebSocket for " + wsUrlToConnect + " closed, but current target is " + currentWebSocketUrl + ". No retry for old URL.");
                    } else {
                        Log.d(TAG, "WebSocket closed cleanly or retry not appropriate. Reason: " + reason + " Code: " + code);
                    }
                } else {
                     Log.w(TAG, "onClosed event for an old/unknown WebSocket instance (URL: " + wsUrlToConnect + "). Ignoring for retry logic.");
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                super.onFailure(ws, t, response);
                String errorMsg = (t != null && t.getMessage() != null) ? t.getMessage() : "Unknown connection error";
                Log.e(TAG, "WebSocket Failure for " + wsUrlToConnect + ": " + errorMsg, t);
                // Check if this failure is for the WebSocket client we care about
                if (WebSocketService.this.webSocketClient == ws) {
                    WebSocketService.this.webSocketClient = null; // Clear the active client
                    handleConnectionFailure(wsUrlToConnect, errorMsg);
                } else {
                    Log.w(TAG, "onFailure event for an old/unknown WebSocket instance (URL: " + wsUrlToConnect + "). Ignoring for retry logic.");
                }
            }
        });
    }

    private void handleConnectionFailure(String failedWsUrl, String errorMessage) {
        // Ensure we only retry if the service is still meant to be connected to THIS URL
        if (!isServiceRunningAsForeground || currentWebSocketUrl == null || !currentWebSocketUrl.equals(failedWsUrl)) {
            Log.w(TAG, "handleConnectionFailure: Conditions not met for retry on " + failedWsUrl +
                    " (service stopped, URL changed to " + currentWebSocketUrl + ", or explicit disconnect). Error: " + errorMessage);
            if (currentWebSocketUrl != null && currentWebSocketUrl.equals(failedWsUrl)) { // If it was the current URL that failed but conditions changed
                 sendBroadcastStatus("Connection Failed: " + errorMessage + " to " + failedWsUrl.replaceFirst("ws://", "").replaceFirst("/ws",""));
                 updateServiceNotification("Connection Failed");
            }
            return;
        }

        if (connectionRetryCount < MAX_CONNECTION_RETRIES) {
            long delay = (long) (INITIAL_RETRY_DELAY_MS * Math.pow(1.8, connectionRetryCount)); // Slightly less aggressive backoff
            delay = Math.min(delay, MAX_RETRY_DELAY_MS);

            final int nextRetryAttempt = connectionRetryCount + 1;
            Log.d(TAG, "Retrying connection to: " + currentWebSocketUrl + " (Attempt " + nextRetryAttempt +
                    " of " + MAX_CONNECTION_RETRIES + ") in " + (delay / 1000.0) + "s. Last Error: " + errorMessage);
            String retryStatus = String.format(Locale.US,"Connection Failed to %s. Retrying (%d/%d)...",
                    currentWebSocketUrl.replaceFirst("ws://", "").replaceFirst("/ws",""),
                    nextRetryAttempt, MAX_CONNECTION_RETRIES);

            sendBroadcastStatus(retryStatus);
            updateServiceNotification("Connection Failed. Retrying...");

            retryHandler.postDelayed(() -> {
                if (isServiceRunningAsForeground && currentWebSocketUrl != null && currentWebSocketUrl.equals(failedWsUrl)) {
                     // connectionRetryCount is incremented just before the connectWebSocket call in this block if it proceeds
                    connectWebSocket(currentWebSocketUrl); // This will use the current connectionRetryCount
                } else {
                     Log.w(TAG, "Retry for " + failedWsUrl + " was scheduled but is now cancelled due to state change (URL/service state).");
                }
            }, delay);
            connectionRetryCount++; // Increment for the next potential failure of this attempt
        } else {
            Log.e(TAG, "Max retry attempts ("+ MAX_CONNECTION_RETRIES +") reached for " + currentWebSocketUrl + ". Giving up. Last error: " + errorMessage);
            sendBroadcastStatus("Connection Failed: Max retries for " + currentWebSocketUrl.replaceFirst("ws://", "").replaceFirst("/ws",""));
            updateServiceNotification("Connection Failed. Max retries.");
            // currentWebSocketUrl = null; // Optional: Force user to explicitly reconnect.
        }
    }

    private void disconnectWebSocket(String reason) {
        Log.i(TAG, "disconnectWebSocket called. Reason: " + reason);
        cancelPendingRetries(); // Stop any retry attempts
        if (webSocketClient != null) {
            Log.d(TAG, "Closing active WebSocket client for URL: " + currentWebSocketUrl);
            webSocketClient.close(1000, reason); // 1000 Normal closure
            webSocketClient = null;
        }
        // Important: Clear currentWebSocketUrl to signify no active connection target
        // This prevents onClosed/onFailure from incorrect retry attempts after explicit disconnect.
        currentWebSocketUrl = null;
        connectionRetryCount = 0; // Reset retries
        sendBroadcastStatus("Disconnected: " + reason);
        // Foreground notification will be updated by onClosed or if explicitly stopped
    }

    private void cancelPendingRetries() {
        retryHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Cancelled any pending connection retries.");
    }

    private void sendBroadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.v(TAG, "Broadcast Sent - Status: " + status);
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
            if (NOTIFICATION_CHANNEL_ID_MESSAGES.equals(channelId)) {
                channel.setDescription(getString(R.string.channel_description));
            } else {
                 channel.setDescription("Channel for ESP32 Sync Service status updates.");
            }
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created/updated: " + channelId);
            } else {
                Log.e(TAG, "NotificationManager is null, cannot create channel " + channelId);
            }
        }
    }

    private void showDataNotification(String title, String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show data notification.");
                return;
            }
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                (int) System.currentTimeMillis(), // Unique request code for pending intent
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(Notification.DEFAULT_ALL); // Uses default sound, vibrate, light

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        try {
            notificationManager.notify(MESSAGE_NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) { // Should be rare with TIRAMISU check
            Log.e(TAG, "SecurityException while trying to post data notification: " + e.getMessage());
        }
    }

    private void stopServiceAndForeground() {
        Log.i(TAG, "stopServiceAndForeground called.");
        disconnectWebSocket("Service is stopping"); // Ensure WS is closed
        cancelPendingRetries();
        if (isServiceRunningAsForeground) {
            Log.d(TAG, "Stopping foreground state.");
            stopForeground(true); // True to remove the notification
            isServiceRunningAsForeground = false;
        }
        stopSelf(); // Stop the service instance
        Log.i(TAG, "WebSocketService fully stopped and resources should be released.");
        // Note: sendBroadcastStatus("Service Stopped") is often done by the component requesting the stop
        // or can be done here if a final status is needed.
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // This service is not designed for binding
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy. Cleaning up...");
        disconnectWebSocket("Service being destroyed"); // Final attempt to close
        cancelPendingRetries();
        if (isServiceRunningAsForeground) { // Should already be false if stopServiceAndForeground was called
            stopForeground(true);
            isServiceRunningAsForeground = false;
        }
        if (httpClient != null) {
            // OkHttp recommends shutting down its dispatcher and connection pool for cleanup
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            try {
                if (httpClient.cache() != null) {
                    httpClient.cache().close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing OkHttp cache during onDestroy", e);
            }
        }
        Log.i(TAG, "Service resources cleaned up in onDestroy.");
        super.onDestroy();
    }
}