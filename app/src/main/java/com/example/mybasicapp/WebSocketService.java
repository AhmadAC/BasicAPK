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

    private static final String TAG = "WebSocketService_DEBUG"; // Enhanced Tag
    public static final String ACTION_START_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_START_FOREGROUND_SERVICE";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "com.example.mybasicapp.ACTION_STOP_FOREGROUND_SERVICE";
    public static final String ACTION_CONNECT = "com.example.mybasicapp.ACTION_CONNECT";
    public static final String ACTION_DISCONNECT = "com.example.mybasicapp.ACTION_DISCONNECT";
    public static final String EXTRA_IP_ADDRESS = "EXTRA_IP_ADDRESS";

    public static final String ACTION_STATUS_UPDATE = "com.example.mybasicapp.ACTION_STATUS_UPDATE";
    public static final String EXTRA_STATUS = "EXTRA_STATUS";
    public static final String ACTION_MESSAGE_RECEIVED = "com.example.mybasicapp.ACTION_MESSAGE_RECEIVED";
    public static final String EXTRA_MESSAGE_TITLE = "EXTRA_MESSAGE_TITLE";
    public static final String EXTRA_MESSAGE_BODY = "EXTRA_MESSAGE_BODY";

    private static final String NOTIFICATION_CHANNEL_ID_SERVICE = "web_socket_service_status_channel";
    private static final String NOTIFICATION_CHANNEL_ID_MESSAGES = "esp32_message_notifications";
    private static final int SERVICE_NOTIFICATION_ID = 1;
    private static final int MESSAGE_NOTIFICATION_ID = 101;

    private OkHttpClient httpClient;
    private WebSocket webSocketClient;
    private String currentWebSocketUrl;
    private Handler retryHandler = new Handler(Looper.getMainLooper());
    private boolean isServiceRunningAsForeground = false;
    private int connectionRetryCount = 0;
    private static final int MAX_CONNECTION_RETRIES = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 3000;
    private static final long MAX_RETRY_DELAY_MS = 30000;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate: Service Creating");
        httpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_SERVICE, "ESP32 Sync Service", NotificationManager.IMPORTANCE_LOW);
        createNotificationChannel(NOTIFICATION_CHANNEL_ID_MESSAGES, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
        Log.d(TAG, "onCreate: Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "onStartCommand: Null intent or action. Flags=" + flags + ", StartId=" + startId);
            if (!isServiceRunningAsForeground) {
                 Log.d(TAG, "onStartCommand: Ensuring foreground state due to null intent.");
                 startForegroundServiceWithNotification("Service Initializing (Restart)...");
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: Action='" + action + "', Flags=" + flags + ", StartId=" + startId);

        switch (action) {
            case ACTION_START_FOREGROUND_SERVICE:
                Log.d(TAG, "onStartCommand: Handling ACTION_START_FOREGROUND_SERVICE. isServiceRunningAsForeground=" + isServiceRunningAsForeground);
                if (!isServiceRunningAsForeground) {
                    startForegroundServiceWithNotification("Service Active. Ready for connection.");
                }
                break;
            case ACTION_STOP_FOREGROUND_SERVICE:
                Log.d(TAG, "onStartCommand: Handling ACTION_STOP_FOREGROUND_SERVICE.");
                stopServiceAndForeground();
                return START_NOT_STICKY;
            case ACTION_CONNECT:
                String wsUrlFromIntent = intent.getStringExtra(EXTRA_IP_ADDRESS);
                Log.d(TAG, "onStartCommand: Handling ACTION_CONNECT. URL='" + wsUrlFromIntent + "'. isServiceRunningAsForeground=" + isServiceRunningAsForeground);
                if (!isServiceRunningAsForeground) {
                    startForegroundServiceWithNotification("Preparing to connect to " + (wsUrlFromIntent != null ? wsUrlFromIntent.replaceFirst("ws://","").split("/")[0] : "ESP") );
                }
                if (wsUrlFromIntent != null && !wsUrlFromIntent.isEmpty()) {
                    if (webSocketClient != null && currentWebSocketUrl != null && !currentWebSocketUrl.equals(wsUrlFromIntent)) {
                        Log.i(TAG, "ACTION_CONNECT: New URL received. Closing existing connection to '" + currentWebSocketUrl + "'.");
                        disconnectWebSocket("Switching to new target: " + wsUrlFromIntent.replaceFirst("ws://","").split("/")[0]);
                    }
                    Log.i(TAG, "ACTION_CONNECT: Setting currentWebSocketUrl to '" + wsUrlFromIntent + "'");
                    currentWebSocketUrl = wsUrlFromIntent;
                    connectionRetryCount = 0;
                    cancelPendingRetries();
                    connectWebSocket(currentWebSocketUrl);
                } else {
                    Log.e(TAG, "ACTION_CONNECT: WebSocket URL is null or empty in intent.");
                    sendBroadcastStatus("Error: WebSocket URL missing for connect");
                    updateServiceNotification("Error: URL Missing");
                }
                break;
            case ACTION_DISCONNECT:
                Log.d(TAG, "onStartCommand: Handling ACTION_DISCONNECT.");
                disconnectWebSocket("User or App requested disconnect"); // More specific reason
                updateServiceNotification("Disconnected by request.");
                break;
            default:
                Log.w(TAG, "onStartCommand: Unhandled action: " + action);
                break;
        }
        return START_STICKY;
    }

    private void startForegroundServiceWithNotification(String statusText) {
        Log.d(TAG, "startForegroundServiceWithNotification: statusText='" + statusText + "'");
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
                // ... (rest of the notification builder from previous correct version)
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
            Log.e(TAG, "updateServiceNotification: Error: " + e.getMessage(), e);
        }
    }

    private void connectWebSocket(final String wsUrlToConnect) {
        Log.i(TAG, "connectWebSocket: Attempting connection to '" + wsUrlToConnect + "'. RetryCount=" + connectionRetryCount);
        if (wsUrlToConnect == null || wsUrlToConnect.isEmpty()) {
            Log.e(TAG, "connectWebSocket: Aborted, URL is null or empty.");
            sendBroadcastStatus("Error: Invalid ESP32 URL");
            updateServiceNotification("Error: Invalid URL");
            return;
        }

        if (webSocketClient != null) {
            Log.w(TAG, "connectWebSocket: webSocketClient is not null. This indicates a potential race condition or improper cleanup. Closing it first.");
            webSocketClient.close(1001, "Cleaning up before new attempt");
            webSocketClient = null;
        }

        Request request = new Request.Builder().url(wsUrlToConnect).build();
        final String displayUrl = wsUrlToConnect.replaceFirst("ws://", "").replaceFirst("/ws", "");
        String connectingMsg = "Connecting to: " + displayUrl + (connectionRetryCount > 0 ? " (Retry " + connectionRetryCount + ")" : "");
        Log.d(TAG, "connectWebSocket: Sending broadcast and updating notification: '" + connectingMsg + "'");
        sendBroadcastStatus(connectingMsg);
        updateServiceNotification(connectingMsg);

        webSocketClient = httpClient.newWebSocket(request, new WebSocketListener() { // Assign to instance variable
            @Override
            public void onOpen(WebSocket ws, Response response) {
                super.onOpen(ws, response);
                if (!wsUrlToConnect.equals(currentWebSocketUrl)) {
                    Log.w(TAG, "onOpen: Received for a STALE URL '" + wsUrlToConnect + "'. Current target: '" + currentWebSocketUrl + "'. Closing this WS.");
                    ws.close(1000, "Stale connection open.");
                    return;
                }
                Log.i(TAG, "onOpen: WebSocket OPENED successfully with '" + displayUrl + "'. Response: " + response.message());
                sendBroadcastStatus("Connected to: " + displayUrl);
                updateServiceNotification("Connected to: " + displayUrl);
                connectionRetryCount = 0;
                cancelPendingRetries();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                super.onMessage(ws, text);
                 if (!wsUrlToConnect.equals(currentWebSocketUrl) || WebSocketService.this.webSocketClient != ws) {
                     Log.w(TAG, "onMessage (text): From a STALE/UNEXPECTED WebSocket instance. Ignoring."); return;
                }
                Log.i(TAG, "onMessage (text) from '" + displayUrl + "': " + text);
                // ... (rest of JSON parsing from previous correct version)
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
                     Log.w(TAG, "onMessage (bytes): From a STALE/UNEXPECTED WebSocket instance. Ignoring."); return;
                }
                Log.i(TAG, "onMessage (bytes) from '" + displayUrl + "': " + bytes.hex());
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                super.onClosing(ws, code, reason);
                Log.i(TAG, "onClosing: Code=" + code + ", Reason='" + reason + "' for URL: '" + wsUrlToConnect + "'");
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                super.onClosed(ws, code, reason);
                Log.i(TAG, "onClosed: Code=" + code + ", Reason='" + reason + "' for URL: '" + wsUrlToConnect + "'");
                if (WebSocketService.this.webSocketClient == ws) { // Check if this is the one we care about
                    Log.d(TAG, "onClosed: This was the active webSocketClient.");
                    WebSocketService.this.webSocketClient = null;
                    String finalStatusMsg = "Disconnected from: " + displayUrl + " (R: " + reason + ", C: " + code + ")";
                    sendBroadcastStatus(finalStatusMsg);
                    updateServiceNotification("Disconnected from ESP32");

                    boolean intentionalClose = (code == 1000 || code == 1001);
                    if (!intentionalClose && currentWebSocketUrl != null && currentWebSocketUrl.equals(wsUrlToConnect) && isServiceRunningAsForeground) {
                        Log.w(TAG, "onClosed: Connection to '" + wsUrlToConnect + "' closed unexpectedly (Code=" + code + "). Scheduling retry.");
                        handleConnectionFailure(wsUrlToConnect, "Connection closed (code " + code + ")");
                    } else {
                        Log.d(TAG, "onClosed: Clean close or retry not appropriate. currentWebSocketUrl=" + currentWebSocketUrl + ", intentionalClose=" + intentionalClose);
                    }
                } else {
                     Log.w(TAG, "onClosed: Event for an old/unknown WebSocket instance. Ignored for retry/status update. (Closed URL: " + wsUrlToConnect + ")");
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                super.onFailure(ws, t, response);
                String errorMsg = (t != null && t.getMessage() != null) ? t.getMessage() : "Unknown connection error";
                String responseMsg = (response != null) ? response.message() + " (Code: " + response.code() + ")" : "No response";
                Log.e(TAG, "onFailure: For URL '" + wsUrlToConnect + "'. Error: '" + errorMsg + "'. Response: '" + responseMsg + "'", t);
                if (WebSocketService.this.webSocketClient == ws) { // Check if this is the one we care about
                    Log.d(TAG, "onFailure: This was the active webSocketClient.");
                    WebSocketService.this.webSocketClient = null;
                    handleConnectionFailure(wsUrlToConnect, errorMsg);
                } else {
                    Log.w(TAG, "onFailure: Event for an old/unknown WebSocket instance. Ignored for retry/status update. (Failed URL: " + wsUrlToConnect + ")");
                }
            }
        });
    }

    private void handleConnectionFailure(String failedWsUrl, String errorMessage) {
        Log.d(TAG, "handleConnectionFailure: URL='" + failedWsUrl + "', Error='" + errorMessage + "', currentTargetURL='" + currentWebSocketUrl + "', retries=" + connectionRetryCount);
        if (!isServiceRunningAsForeground || currentWebSocketUrl == null || !currentWebSocketUrl.equals(failedWsUrl)) {
            Log.w(TAG, "handleConnectionFailure: Conditions not met for retry. ServiceRunning=" + isServiceRunningAsForeground +
                    ", currentURLMatch=" + (currentWebSocketUrl != null && currentWebSocketUrl.equals(failedWsUrl)));
            if (currentWebSocketUrl != null && currentWebSocketUrl.equals(failedWsUrl)) {
                 sendBroadcastStatus("Connection Failed: " + errorMessage + " to " + failedWsUrl.replaceFirst("ws://", "").replaceFirst("/ws",""));
                 updateServiceNotification("Connection Failed");
            }
            return;
        }

        if (connectionRetryCount < MAX_CONNECTION_RETRIES) {
            long delay = (long) (INITIAL_RETRY_DELAY_MS * Math.pow(1.8, connectionRetryCount));
            delay = Math.min(delay, MAX_RETRY_DELAY_MS);
            final int nextRetryNum = connectionRetryCount + 1; // Store for log, count increments after scheduling

            Log.i(TAG, "handleConnectionFailure: Scheduling retry " + nextRetryNum + "/" + MAX_CONNECTION_RETRIES +
                    " for '" + currentWebSocketUrl + "' in " + (delay / 1000.0) + "s. Last Error: " + errorMessage);
            String retryStatus = String.format(Locale.US,"Connection Failed to %s. Retrying (%d/%d)...",
                    currentWebSocketUrl.replaceFirst("ws://", "").replaceFirst("/ws",""),
                    nextRetryNum, MAX_CONNECTION_RETRIES);
            sendBroadcastStatus(retryStatus);
            updateServiceNotification("Connection Failed. Retrying...");

            retryHandler.postDelayed(() -> {
                Log.d(TAG, "handleConnectionFailure: Retry runnable executing for '" + failedWsUrl + "'. Current target: '" + currentWebSocketUrl + "'");
                if (isServiceRunningAsForeground && currentWebSocketUrl != null && currentWebSocketUrl.equals(failedWsUrl)) {
                    Log.i(TAG, "handleConnectionFailure: Proceeding with retry attempt " + nextRetryNum + " for '" + currentWebSocketUrl + "'");
                    // connectionRetryCount was already incremented before connectWebSocket is called again by this retry
                    connectWebSocket(currentWebSocketUrl);
                } else {
                     Log.w(TAG, "handleConnectionFailure: Retry for '" + failedWsUrl + "' cancelled due to state change during delay.");
                }
            }, delay);
            connectionRetryCount++; // Increment for the *next* failure, if this retry also fails
        } else {
            Log.e(TAG, "handleConnectionFailure: Max retry attempts ("+ MAX_CONNECTION_RETRIES +") reached for '" + currentWebSocketUrl + "'. Giving up. Last error: " + errorMessage);
            sendBroadcastStatus("Connection Failed: Max retries for " + currentWebSocketUrl.replaceFirst("ws://", "").replaceFirst("/ws",""));
            updateServiceNotification("Connection Failed. Max retries.");
        }
    }

    private void disconnectWebSocket(String reason) {
        Log.i(TAG, "disconnectWebSocket: Called. Reason='" + reason + "'. Current URL='" + currentWebSocketUrl + "'");
        cancelPendingRetries();
        if (webSocketClient != null) {
            Log.d(TAG, "disconnectWebSocket: Closing active WebSocket client (was connected to '" + currentWebSocketUrl + "').");
            webSocketClient.close(1000, reason);
            webSocketClient = null;
        } else {
            Log.d(TAG, "disconnectWebSocket: No active webSocketClient to close.");
        }
        currentWebSocketUrl = null; // Essential to prevent retries after explicit disconnect
        connectionRetryCount = 0;
        sendBroadcastStatus("Disconnected: " + reason);
        Log.i(TAG, "disconnectWebSocket: Process completed. Broadcast sent.");
    }

    private void cancelPendingRetries() {
        Log.d(TAG, "cancelPendingRetries: Removing any scheduled retry callbacks.");
        retryHandler.removeCallbacksAndMessages(null);
    }

    private void sendBroadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.v(TAG, "sendBroadcastStatus >> UI: " + status);
    }

    private void sendBroadcastMessage(String title, String body) {
        Intent intent = new Intent(ACTION_MESSAGE_RECEIVED);
        intent.putExtra(EXTRA_MESSAGE_TITLE, title);
        intent.putExtra(EXTRA_MESSAGE_BODY, body);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.v(TAG, "sendBroadcastMessage >> UI: Title='" + title + "', Body='" + body.substring(0, Math.min(body.length(), 50)) + "...'");
    }

    private void createNotificationChannel(String channelId, String channelName, int importance) {
        // ... (same as previous correct version)
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
                Log.d(TAG, "createNotificationChannel: Channel '" + channelId + "' created/updated.");
            } else {
                Log.e(TAG, "createNotificationChannel: NotificationManager is null for channel '" + channelId + "'");
            }
        }
    }

    private void showDataNotification(String title, String message) {
        // ... (same as previous correct version)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "showDataNotification: POST_NOTIFICATIONS permission NOT granted. Cannot show.");
                return;
            }
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title).setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(pendingIntent).setDefaults(Notification.DEFAULT_ALL);
        NotificationManagerCompat.from(this).notify(MESSAGE_NOTIFICATION_ID, builder.build());
        Log.d(TAG, "showDataNotification: Sent. Title='" + title + "'");
    }

    private void stopServiceAndForeground() {
        Log.i(TAG, "stopServiceAndForeground: Initiated.");
        disconnectWebSocket("Service is stopping (foreground cleanup)");
        cancelPendingRetries();
        if (isServiceRunningAsForeground) {
            Log.d(TAG, "stopServiceAndForeground: Stopping foreground state now.");
            stopForeground(true);
            isServiceRunningAsForeground = false;
        }
        stopSelf();
        Log.i(TAG, "stopServiceAndForeground: Service instance stopped. Final broadcast may have been missed by MainActivity if it's also destroying.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind: Called, returning null (not a bound service).");
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: Service Destroying. Cleaning up all resources.");
        disconnectWebSocket("Service being destroyed (onDestroy)");
        cancelPendingRetries();
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
            } catch (Exception e) {
                Log.e(TAG, "onDestroy: Error closing OkHttp cache: " + e.getMessage(), e);
            }
        }
        Log.i(TAG, "onDestroy: Service fully destroyed.");
        super.onDestroy();
    }
}