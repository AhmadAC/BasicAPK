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
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private EditText editTextEspIp;
    private Button buttonStartService, buttonStopService, buttonConnect, buttonDisconnect;
    private TextView textViewStatus;
    private TextView textViewLastMessage;

    private boolean isServiceReceiverRegistered = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Notifications permission granted", Toast.LENGTH_SHORT).show();
                    // You can now start the service or trigger notifications that might have been pending
                } else {
                    Toast.makeText(this, "Notifications permission denied. App may not show alerts.", Toast.LENGTH_LONG).show();
                }
            });

    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WebSocketService.ACTION_STATUS_UPDATE.equals(action)) {
                String status = intent.getStringExtra(WebSocketService.EXTRA_STATUS);
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
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewLastMessage = findViewById(R.id.textViewLastMessage);

        askNotificationPermission(); // Ask on create

        buttonStartService.setOnClickListener(v -> {
            startWebSocketService();
            buttonStartService.setEnabled(false);
            buttonStopService.setEnabled(true);
            buttonConnect.setEnabled(true); // Enable connect after service starts
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
            startService(serviceIntent); // Use startService for commands to an already running service
            buttonConnect.setEnabled(false); // Disable until status update
            buttonDisconnect.setEnabled(false);
        });

        buttonDisconnect.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, WebSocketService.class);
            serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT);
            startService(serviceIntent);
            buttonConnect.setEnabled(true); // Re-enable connect button
            buttonDisconnect.setEnabled(false);
        });

        // Initial button states
        buttonStopService.setEnabled(false);
        buttonConnect.setEnabled(false);
        buttonDisconnect.setEnabled(false);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(this, "Notification permission is needed to show alerts from ESP32.", Toast.LENGTH_LONG).show();
                    // Consider showing a dialog here explaining why the permission is needed.
                    // For simplicity, requesting directly.
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
    }

    private void stopWebSocketService() {
        Intent serviceIntent = new Intent(this, WebSocketService.class);
        serviceIntent.setAction(WebSocketService.ACTION_STOP_FOREGROUND_SERVICE);
        startService(serviceIntent); // Send stop command
        textViewStatus.setText("Status: Service Stopping...");
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Consider unregistering if you only want updates when activity is visible.
        // For this app, keeping it registered to see logs and last message is fine.
        // If you unregister here, make sure to handle service state appropriately onResume.
        // LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
        // isServiceReceiverRegistered = false;
    }

    @Override
    protected void onDestroy() {
        // If you want the service to stop when the app is fully closed from recents,
        // you could call stopWebSocketService() here.
        // However, a common use case for a foreground service is to keep running.
        // For this example, we let the user explicitly stop the service via the button.
        if (isServiceReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
            isServiceReceiverRegistered = false;
        }
        super.onDestroy();
    }
}