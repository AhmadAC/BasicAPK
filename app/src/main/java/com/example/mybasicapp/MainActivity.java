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
import android.net.nsd.NsdServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText; // Will be repurposed or hidden
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements NsdHelper.NsdHelperListener {

    private static final String TAG = "MainActivity";

    // This MUST match MDNS_WEBSOCKET_SERVICE_NAME in your ESP32 CircuitPython code
    private static final String TARGET_ESP_SERVICE_NAME = "ESP32 Motion WebSocket";
    private static final String ESP_WEBSOCKET_PATH = "/ws"; // Path on ESP32 server for WebSocket

    private EditText editTextEspIp; // Will now display discovered IP or be hidden
    private Button buttonStartService, buttonStopService;
    private Button buttonManualConnect; // Renamed from buttonConnect
    private Button buttonDisconnect, buttonSaveLog;
    private TextView textViewStatus;
    private TextView textViewLastMessage;

    private boolean isServiceReceiverRegistered = false;
    private StringBuilder statusLog = new StringBuilder();
    private StringBuilder messageLog = new StringBuilder();

    private ActivityResultLauncher<String> createFileLauncher;
    private NsdHelper nsdHelper;
    private NsdServiceInfo resolvedEspService = null; // Store the resolved service
    private boolean isWebSocketServiceManuallyStarted = false; // Track if service was started by user

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

                if ("Service Stopped".equals(status)) {
                    updateUIForServiceStopped();
                } else if ("Disconnected by user".equals(status) || status.startsWith("Connection Failed") || "Disconnected".equals(status) ) {
                    updateUIForDisconnected();
                } else if ("Connected to ESP32".equals(status)) {
                    updateUIForConnected();
                } else if (status.startsWith("Connecting to")) {
                    updateUIForConnecting();
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
        buttonManualConnect = findViewById(R.id.buttonConnect); // Assuming ID is still buttonConnect in XML
        buttonDisconnect = findViewById(R.id.buttonDisconnect);
        buttonSaveLog = findViewById(R.id.buttonSaveLog);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewLastMessage = findViewById(R.id.textViewLastMessage);

        // Repurpose or hide editTextEspIp for mDNS
        editTextEspIp.setHint("ESP32 (Auto-Discovering...)");
        editTextEspIp.setEnabled(false); // Disable input, make it display only
        editTextEspIp.setFocusable(false);


        askNotificationPermission();
        nsdHelper = new NsdHelper(this, this);

        createFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                saveLogToFile(uri);
            } else {
                Toast.makeText(MainActivity.this, "Log saving cancelled.", Toast.LENGTH_SHORT).show();
            }
        });

        buttonStartService.setOnClickListener(v -> {
            startWebSocketService(); // This just starts the foreground service
            isWebSocketServiceManuallyStarted = true;
            // Discovery will be triggered in onResume or if service starts successfully
        });

        buttonStopService.setOnClickListener(v -> {
            nsdHelper.stopDiscovery();
            stopWebSocketService();
            isWebSocketServiceManuallyStarted = false;
        });

        // Button "Connect" is now "Find/Connect ESP32" or can be removed if fully auto
        buttonManualConnect.setText("Find/Connect ESP32");
        buttonManualConnect.setOnClickListener(v -> {
            if (!isWebSocketServiceManuallyStarted) {
                Toast.makeText(this, "Please start the service first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (nsdHelper.isDiscoveryActive()) {
                Toast.makeText(MainActivity.this, "Already searching...", Toast.LENGTH_SHORT).show();
            } else {
                textViewStatus.setText("Status: Searching for ESP32...");
                editTextEspIp.setText("");
                resolvedEspService = null;
                nsdHelper.discoverServices(TARGET_ESP_SERVICE_NAME);
            }
            updateUIForDiscovery();
        });


        buttonDisconnect.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, WebSocketService.class);
            serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT);
            startService(serviceIntent); // Service handles the actual disconnect
        });

        buttonSaveLog.setOnClickListener(v -> {
            String fileName = "MrCoopersESP32_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            createFileLauncher.launch(fileName);
        });

        updateUIForServiceStopped(); // Initial state
    }

    private void updateUIForServiceStopped() {
        textViewStatus.setText("Status: Service Stopped. Tap 'Start Svc'.");
        buttonStartService.setEnabled(true);
        buttonStopService.setEnabled(false);
        buttonManualConnect.setEnabled(false);
        buttonDisconnect.setEnabled(false);
        editTextEspIp.setText("");
        resolvedEspService = null;
    }

    private void updateUIForServiceStarted() {
        textViewStatus.setText("Status: Service Started. Searching ESP32...");
        buttonStartService.setEnabled(false);
        buttonStopService.setEnabled(true);
        buttonManualConnect.setEnabled(true); // Allow manual search trigger
        buttonDisconnect.setEnabled(false);
    }

    private void updateUIForDiscovery() {
        buttonManualConnect.setEnabled(false); // Disable while actively discovering/connecting
        buttonDisconnect.setEnabled(false);
    }
    private void updateUIForConnecting() {
        textViewStatus.setText("Status: Connecting to ESP32...");
        buttonManualConnect.setEnabled(false);
        buttonDisconnect.setEnabled(false); // Can't disconnect if not connected
    }

    private void updateUIForConnected() {
        textViewStatus.setText("Status: Connected to " + (resolvedEspService != null ? resolvedEspService.getServiceName() : "ESP32"));
        if (resolvedEspService != null && resolvedEspService.getHost() != null) {
             editTextEspIp.setText(resolvedEspService.getHost().getHostAddress() + ":" + resolvedEspService.getPort());
        }
        buttonStartService.setEnabled(false); // Assuming service is started if connected
        buttonStopService.setEnabled(true);
        buttonManualConnect.setEnabled(false); // Connected, no need to find
        buttonDisconnect.setEnabled(true);
    }

    private void updateUIForDisconnected() {
        // If service is still running, allow to find/connect again
        if (isWebSocketServiceManuallyStarted) {
            textViewStatus.setText("Status: Disconnected. Tap 'Find/Connect'.");
            buttonManualConnect.setEnabled(true);
            buttonDisconnect.setEnabled(false);
            editTextEspIp.setText(""); // Clear old IP
            resolvedEspService = null;
            // Optionally restart discovery automatically
            // nsdHelper.discoverServices(TARGET_ESP_SERVICE_NAME);
        } else {
            updateUIForServiceStopped(); // If service was stopped, revert to that state
        }
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
            Toast.makeText(this, "Log saved successfully", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "Error saving log to file", e);
            Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
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
        statusLog.append(getCurrentTimestamp() + " CMD: Start Service\n");
        updateUIForServiceStarted();
        // Automatically start discovery when service starts
        textViewStatus.setText("Status: Service Started. Searching for " + TARGET_ESP_SERVICE_NAME + "...");
        nsdHelper.discoverServices(TARGET_ESP_SERVICE_NAME);
    }

    private void stopWebSocketService() {
        Intent serviceIntent = new Intent(this, WebSocketService.class);
        serviceIntent.setAction(WebSocketService.ACTION_STOP_FOREGROUND_SERVICE);
        startService(serviceIntent); // This will trigger service's stopSelf and cleanup
        statusLog.append(getCurrentTimestamp() + " CMD: Stop Service\n");
        updateUIForServiceStopped();
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
        // If service was manually started and not connected, and discovery isn't active, try discovering
        if (isWebSocketServiceManuallyStarted && resolvedEspService == null && !nsdHelper.isDiscoveryActive()) {
            Log.d(TAG, "onResume: Service is started, no resolved ESP, discovery not active. Starting discovery.");
            textViewStatus.setText("Status: Searching for " + TARGET_ESP_SERVICE_NAME + "...");
            nsdHelper.discoverServices(TARGET_ESP_SERVICE_NAME);
            updateUIForDiscovery();
        } else if (resolvedEspService != null) {
            // If we previously resolved a service, refresh UI (e.g. if app was backgrounded and re-opened)
            // Check WebSocketService status to accurately reflect if still connected
             updateUIForConnected(); // This might need to query service for actual connection state
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Optional: stop discovery if app is paused and not connected to save battery
        // if (resolvedEspService == null && nsdHelper.isDiscoveryActive()) {
        //     nsdHelper.stopDiscovery();
        // }
    }

    @Override
    protected void onDestroy() {
        if (isServiceReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
            isServiceReceiverRegistered = false;
        }
        nsdHelper.tearDown(); // Important to release NsdManager resources
        // Consider stopping the service if the activity is destroyed and you don't want it to run headless.
        // However, the current design implies the service can run independently for notifications.
        // If !isChangingConfigurations() { stopWebSocketService(); } // Example: stop if not rotation
        super.onDestroy();
    }

    // --- NsdHelper.NsdHelperListener Implementation ---
    @Override
    public void onNsdServiceResolved(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Target Service Resolved: " + serviceInfo.getServiceName() + " at " + serviceInfo.getHost() + ":" + serviceInfo.getPort());
            if (TARGET_ESP_SERVICE_NAME.equalsIgnoreCase(serviceInfo.getServiceName())) {
                resolvedEspService = serviceInfo; // Store the successfully resolved service
                editTextEspIp.setText(serviceInfo.getHost().getHostAddress() + ":" + serviceInfo.getPort());
                textViewStatus.setText("Status: Found " + serviceInfo.getServiceName() + ". Connecting...");
                statusLog.append(getCurrentTimestamp() + " NSD: Found " + serviceInfo.getServiceName() + " at " + serviceInfo.getHost().getHostAddress() + ":" + serviceInfo.getPort() + "\n");

                // Automatically connect via the WebSocketService
                if (isWebSocketServiceManuallyStarted) {
                    String wsUrl = "ws://" + serviceInfo.getHost().getHostAddress() + ":" + serviceInfo.getPort() + ESP_WEBSOCKET_PATH;
                    Intent serviceIntent = new Intent(this, WebSocketService.class);
                    serviceIntent.setAction(WebSocketService.ACTION_CONNECT);
                    serviceIntent.putExtra(WebSocketService.EXTRA_IP_ADDRESS, wsUrl); // Service expects full URL
                    startService(serviceIntent);
                    updateUIForConnecting();
                } else {
                     textViewStatus.setText("Status: Found " + serviceInfo.getServiceName() + ". Start Service to connect.");
                     Toast.makeText(this, "ESP32 Found. Please 'Start Service' to connect.", Toast.LENGTH_LONG).show();
                     buttonManualConnect.setEnabled(true); // Allow user to connect if service is not yet started
                }
                nsdHelper.stopDiscovery(); // Found our target, stop further discovery for now
            } else {
                Log.w(TAG, "NSD Resolved a service, but it's not our target: " + serviceInfo.getServiceName());
            }
        });
    }

    @Override
    public void onNsdServiceLost(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.w(TAG, "NSD Service Lost: " + serviceInfo.getServiceName());
            statusLog.append(getCurrentTimestamp() + " NSD: Lost " + serviceInfo.getServiceName() + "\n");
            if (resolvedEspService != null && serviceInfo.getServiceName().equalsIgnoreCase(resolvedEspService.getServiceName())) {
                textViewStatus.setText("Status: ESP32 " + serviceInfo.getServiceName() + " connection lost. Searching...");
                editTextEspIp.setText("");
                resolvedEspService = null;
                updateUIForDisconnected(); // This will enable connect button if service is started
                // Automatically try to re-discover
                if (isWebSocketServiceManuallyStarted && !nsdHelper.isDiscoveryActive()) {
                    nsdHelper.discoverServices(TARGET_ESP_SERVICE_NAME);
                }
            }
        });
    }

    @Override
    public void onNsdDiscoveryFailed(String serviceType, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Discovery Failed for " + serviceType + ". Error: " + errorCode);
            statusLog.append(getCurrentTimestamp() + " NSD: Discovery Failed. Error: " + errorCode + "\n");
            textViewStatus.setText("Status: ESP32 Discovery Failed. Check WiFi/Permissions.");
            if(isWebSocketServiceManuallyStarted) buttonManualConnect.setEnabled(true);
        });
    }

    @Override
    public void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Resolve Failed for " + serviceInfo.getServiceName() + ". Error: " + errorCode);
            statusLog.append(getCurrentTimestamp() + " NSD: Resolve Failed for " + serviceInfo.getServiceName() + ". Error: " + errorCode + "\n");
            textViewStatus.setText("Status: Failed to resolve ESP32 ("+serviceInfo.getServiceName()+"). Still searching...");
            // Keep searching if discovery is active
            if (isWebSocketServiceManuallyStarted && !nsdHelper.isDiscoveryActive()) {
                // If discovery stopped due to resolve, restart it.
                nsdHelper.discoverServices(TARGET_ESP_SERVICE_NAME);
            }
        });
    }

    @Override
    public void onNsdDiscoveryStatusChanged(String statusMessage) {
         runOnUiThread(() -> {
            Log.d(TAG, "NSD Status: " + statusMessage);
            // You can update a secondary status view or just log it.
            // textViewStatus might be overwritten by more specific statuses.
            // For now, mainly logging. If status indicates searching, update main status
            if (statusMessage.toLowerCase().contains("searching") || statusMessage.toLowerCase().contains("discovery started")) {
                 if (!textViewStatus.getText().toString().toLowerCase().contains("connected")) { // don't overwrite if connected
                    textViewStatus.setText("Status: " + statusMessage);
                 }
            }
         });
    }

    @Override
    public void onNsdServiceFound(NsdServiceInfo serviceInfo) {
        // This is called when a candidate is found, before resolution.
        // Resolution is attempted automatically by NsdHelper if name filter matches or no filter.
        // MainActivity gets more specific callback onNsdServiceResolved.
        runOnUiThread(() -> {
            Log.d(TAG, "NSD Candidate Found: " + serviceInfo.getServiceName());
             if (!textViewStatus.getText().toString().toLowerCase().contains("connected") && !textViewStatus.getText().toString().toLowerCase().contains("connecting")) {
                textViewStatus.setText("Status: ESP32 Candidate " + serviceInfo.getServiceName() + " found. Resolving...");
             }
        });
    }

    @Override
    public void onNsdDiscoveryStarted() {
        runOnUiThread(() -> {
            Log.d(TAG, "NSD Discovery actually started by NsdManager.");
            if (isWebSocketServiceManuallyStarted) updateUIForDiscovery();
        });
    }
    @Override
    public void onNsdDiscoveryStopped() {
        runOnUiThread(() -> {
            Log.d(TAG, "NSD Discovery actually stopped by NsdManager.");
            // If discovery stopped and we are not connected and service is running, enable manual find
            if (isWebSocketServiceManuallyStarted && resolvedEspService == null) {
                if(!textViewStatus.getText().toString().toLowerCase().contains("failed")) { // don't overwrite failed state
                    textViewStatus.setText("Status: Discovery stopped. Tap 'Find'.");
                }
                buttonManualConnect.setEnabled(true);
            }
        });
    }
}