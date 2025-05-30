package com.example.mybasicapp;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private EditText editTextEspIp;
    private Button buttonStartService, buttonStopService, buttonConnect, buttonDisconnect, buttonSaveLog;
    private TextView textViewStatus;
    private TextView textViewLastMessage;

    private boolean isServiceReceiverRegistered = false;
    private StringBuilder statusLog = new StringBuilder();
    private StringBuilder messageLog = new StringBuilder();

    private ActivityResultLauncher<String> createFileLauncher;


    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Notifications permission denied. App may not show alerts.", Toast.LENGTH_LONG).show();
                }
            });

    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String timestamp = getCurrentTimestamp();

            if (WebSocketService.ACTION_STATUS_UPDATE.equals(action)) {
                String status = intent.getStringExtra(WebSocketService.EXTRA_STATUS);
                String logEntry = timestamp + " Status: " + status + "\n";
                statusLog.append(logEntry);
                textViewStatus.setText("Status: " + status);
                Log.d(TAG, "Service Status Update: " + status);

                if ("Service Stopped".equals(status) || "Disconnected by user".equals(status) || status.startsWith("Connection Failed")) {
                    buttonConnect.setEnabled(true);
                    buttonDisconnect.setEnabled(false);
                } else if ("Connected to ESP32".equals(status)) {
                    buttonConnect.setEnabled(false);
                    buttonDisconnect.setEnabled(true);
                } else if (status.startsWith("Connecting to")) {
                    buttonConnect.setEnabled(false);
                    buttonDisconnect.setEnabled(false);
                }
            } else if (WebSocketService.ACTION_MESSAGE_RECEIVED.equals(action)) {
                String title = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_TITLE);
                String body = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_BODY);
                String logEntry = timestamp + " Message: [" + title + "] " + body + "\n";
                messageLog.append(logEntry);
                textViewLastMessage.setText("Last Message: " + title + " - " + body);
                Log.d(TAG, "Service Message Received: " + title + " - " + body);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextEspIp = findViewById(R.id.editTextEspIp);
        buttonStartService = findViewById(R.id.buttonStartService);
        buttonStopService = findViewById(R.id.buttonStopService);
        buttonConnect = findViewById(R.id.buttonConnect);
        buttonDisconnect = findViewById(R.id.buttonDisconnect);
        buttonSaveLog = findViewById(R.id.buttonSaveLog); // Initialize new button
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewLastMessage = findViewById(R.id.textViewLastMessage);

        askNotificationPermission();

        // Initialize ActivityResultLauncher for creating a file
        createFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                saveLogToFile(uri);
            } else {
                Toast.makeText(MainActivity.this, "Log saving cancelled.", Toast.LENGTH_SHORT).show();
            }
        });

        buttonStartService.setOnClickListener(v -> {
            startWebSocketService();
            buttonStartService.setEnabled(false);
            buttonStopService.setEnabled(true);
            buttonConnect.setEnabled(true);
        });

        buttonStopService.setOnClickListener(v -> {
            stopWebSocketService();
            buttonStartService.setEnabled(true);
            buttonStopService.setEnabled(false);
            buttonConnect.setEnabled(false);
            buttonDisconnect.setEnabled(false);
        });

        buttonConnect.setOnClickListener(v -> {
            String ipAddress = editTextEspIp.getText().toString().trim();
            if (ipAddress.isEmpty()) {
                Toast.makeText(MainActivity.this, "Please enter ESP32 IP Address", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent serviceIntent = new Intent(this, WebSocketService.class);
            serviceIntent.setAction(WebSocketService.ACTION_CONNECT);
            serviceIntent.putExtra(WebSocketService.EXTRA_IP_ADDRESS, ipAddress);
            startService(serviceIntent);
            buttonConnect.setEnabled(false);
            buttonDisconnect.setEnabled(false);
        });

        buttonDisconnect.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, WebSocketService.class);
            serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT);
            startService(serviceIntent);
            buttonConnect.setEnabled(true);
            buttonDisconnect.setEnabled(false);
        });

        buttonSaveLog.setOnClickListener(v -> {
            // Propose a filename, user can change it in the save dialog
            String fileName = "MrCoopersESP32_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            createFileLauncher.launch(fileName);
        });

        // Initial button states
        buttonStopService.setEnabled(false);
        buttonConnect.setEnabled(false);
        buttonDisconnect.setEnabled(false);
    }

    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void saveLogToFile(Uri uri) {
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream)) {

            writer.write("--- Status Log ---\n");
            writer.write(statusLog.toString());
            writer.write("\n--- Message Log ---\n");
            writer.write(messageLog.toString());
            writer.flush();

            Toast.makeText(this, "Log saved to " + uri.getPath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "Error saving log to file", e);
            Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(this, "Notification permission is needed to show alerts from ESP32.", Toast.LENGTH_LONG).show();
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
            }
        }
    }

    private void startWebSocketService() {
        Intent serviceIntent = new Intent(this, WebSocketService.class);
        serviceIntent.setAction(WebSocketService.ACTION_START_FOREGROUND_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        textViewStatus.setText("Status: Service Starting...");
        statusLog.append(getCurrentTimestamp() + " Status: Service Starting...\n");
    }

    private void stopWebSocketService() {
        Intent serviceIntent = new Intent(this, WebSocketService.class);
        serviceIntent.setAction(WebSocketService.ACTION_STOP_FOREGROUND_SERVICE);
        startService(serviceIntent);
        textViewStatus.setText("Status: Service Stopping...");
        statusLog.append(getCurrentTimestamp() + " Status: Service Stopping...\n");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isServiceReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WebSocketService.ACTION_STATUS_UPDATE);
            filter.addAction(WebSocketService.ACTION_MESSAGE_RECEIVED);
            LocalBroadcastManager.getInstance(this).registerReceiver(serviceUpdateReceiver, filter);
            isServiceReceiverRegistered = true;
        }
        // Refresh current status and last message from service if needed, or from stored logs.
        // For simplicity, current UI shows last known, logs accumulate.
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keeping receiver registered to allow background updates if desired.
    }

    @Override
    protected void onDestroy() {
        if (isServiceReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
            isServiceReceiverRegistered = false;
        }
        super.onDestroy();
    }
}