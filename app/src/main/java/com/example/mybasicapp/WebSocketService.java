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
    public static final String EXTRA_IP_ADDRESS = "EXTRA_IP_ADDRESS";

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
    private String currentIpAddress;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isServiceStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        httpClient = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true) // Important for robustness
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
                    startForegroundServiceWithNotification("Service Started. Not connected.");
                    isServiceStarted = true;
                }
            } else if (ACTION_STOP_FOREGROUND_SERVICE.equals(action)) {
                stopService();
                return START_NOT_STICKY; // Ensure it doesn't restart automatically
            } else if (ACTION_CONNECT.equals(action) && isServiceStarted) {
                currentIpAddress = intent.getStringExtra(EXTRA_IP_ADDRESS);
                if (currentIpAddress != null && !currentIpAddress.isEmpty()) {
                    connectWebSocket("ws://" + currentIpAddress + "/ws");
                } else {
                    Log.e(TAG, "IP Address is null or empty");
                    sendBroadcastStatus("Error: IP Address missing");
                }
            } else if (ACTION_DISCONNECT.equals(action) && isServiceStarted) {
                disconnectWebSocket();
            }
        }
        // If the service is killed and restarted, resend the last intent or null
        return START_STICKY;
    }


    private void startForegroundServiceWithNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle("ESP32 Sync Service")
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_stat_service) // Create a small icon for service status
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Makes the notification non-dismissable
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
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_SERVICE)
                .setContentTitle("ESP32 Sync Service")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_service)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        manager.notify(SERVICE_NOTIFICATION_ID, notification);
    }


    private void connectWebSocket(String wsUrl) {
        if (webSocket != null) {
            webSocket.close(1000, "Reconnecting");
            webSocket = null;
        }

        Request request = new Request.Builder().url(wsUrl).build();
        sendBroadcastStatus("Connecting to " + wsUrl);
        updateServiceNotification("Connecting to ESP32...");
        Log.i(TAG, "Attempting to connect to: " + wsUrl);

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                super.onOpen(ws, response);
                Log.i(TAG, "WebSocket Opened");
                sendBroadcastStatus("Connected to ESP32");
                updateServiceNotification("Connected to ESP32");
                // ws.send("Hello ESP32 from Android Service!"); // Optional: send initial message
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                super.onMessage(ws, text);
                Log.i(TAG, "Receiving: " + text);
                try {
                    JSONObject json = new JSONObject(text);
                    String title = json.optString("title", "ESP32 Notification");
                    String message = json.optString("message", "Received a new message.");
                    sendBroadcastMessage(title, message);
                    showDataNotification(title, message);
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing JSON from WebSocket: " + e.getMessage());
                    sendBroadcastMessage("ESP32 Message", text); // Send raw if not JSON
                    showDataNotification("ESP32 Message", text);
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                super.onMessage(ws, bytes);
                Log.i(TAG, "Receiving bytes: " + bytes.hex());
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                super.onClosing(ws, code, reason);
                Log.i(TAG, "Closing: " + code + " / " + reason);
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                super.onClosed(ws, code, reason);
                Log.i(TAG, "Closed: " + code + " / " + reason);
                sendBroadcastStatus("Disconnected");
                updateServiceNotification("Disconnected from ESP32");
                WebSocketService.this.webSocket = null; // Clear the reference
                // Optional: Implement retry logic here if desired
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                super.onFailure(ws, t, response);
                String errorMsg = (t != null && t.getMessage() != null) ? t.getMessage() : "Unknown error";
                Log.e(TAG, "Failure: " + errorMsg, t);
                sendBroadcastStatus("Connection Failed: " + errorMsg);
                updateServiceNotification("Connection Failed");
                WebSocketService.this.webSocket = null; // Clear the reference
                 // Implement retry logic with backoff if connection fails
                if (isServiceStarted && currentIpAddress != null && !currentIpAddress.isEmpty()) {
                    handler.postDelayed(() -> {
                        Log.d(TAG, "Retrying connection to: " + currentIpAddress);
                        connectWebSocket("ws://" + currentIpAddress + "/ws");
                    }, 5000); // Retry after 5 seconds
                }
            }
        });
    }

    private void disconnectWebSocket() {
        if (webSocket != null) {
            webSocket.close(1000, "User disconnected");
            webSocket = null;
        }
        sendBroadcastStatus("Disconnected by user");
        updateServiceNotification("Disconnected by user");
        Log.i(TAG, "WebSocket Disconnected by user action.");
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
            } else {
                 channel.setDescription("Channel for WebSocket Service status");
            }
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void showDataNotification(String title, String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Cannot show data notification.");
                // The service cannot request permission directly. Activity should handle it.
                return;
            }
        }

        Intent intent = new Intent(this, MainActivity.class); // Tapping notification opens MainActivity
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0 /* Request code */, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);


        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_message) // Create a small icon for messages
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        try {
            notificationManager.notify(MESSAGE_NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while trying to post data notification: " + e.getMessage());
        }
    }


    private void stopService() {
        Log.d(TAG, "stopService called");
        disconnectWebSocket(); // Ensure WebSocket is closed
        if (isServiceStarted) {
            stopForeground(true); // true to remove the notification
            stopSelf(); // Stop the service instance
            isServiceStarted = false;
            sendBroadcastStatus("Service Stopped");
        }
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