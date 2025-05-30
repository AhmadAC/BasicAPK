package com.example.mybasicapp;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.Manifest;
import android.content.ActivityNotFoundException;
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
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.RadioButton;
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

    // mDNS Configuration Constants
    private static final String OTA_SERVICE_TYPE = "_http._tcp";
    private static final String OTA_SERVICE_NAME_FILTER = "mrcoopersesp";

    private static final String MAIN_APP_WS_SERVICE_TYPE = "_myespwebsocket._tcp";
    private static final String MAIN_APP_WS_SERVICE_NAME_FILTER = "ESP32 Motion WebSocket";
    private static final String ESP_WEBSOCKET_PATH = "/ws";

    private enum MonitorMode {
        OTA,
        MAIN_APP_WS
    }
    private MonitorMode currentMonitorMode = MonitorMode.MAIN_APP_WS; // Default

    private EditText editTextEspIpDisplay;
    private Button buttonStartStopDiscovery;
    private Button buttonAction;
    private Button buttonSaveLog;
    private TextView textViewStatus;
    private TextView textViewLastMessage;
    private RadioGroup radioGroupMonitorMode;
    private RadioButton radioButtonOta, radioButtonMainApp;

    private boolean isServiceReceiverRegistered = false; // For WebSocketService
    private StringBuilder statusLog = new StringBuilder();
    private StringBuilder messageLog = new StringBuilder(); // For WebSocketService

    private ActivityResultLauncher<String> createFileLauncher;
    private NsdHelper nsdHelper;
    private NsdServiceInfo resolvedEspServiceInfo = null;
    private String activeDiscoveryServiceType = null; // Current type being searched by NsdHelper
    private boolean isWebSocketServiceActive = false; // Is WebSocketService started and connected

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
            if (currentMonitorMode != MonitorMode.MAIN_APP_WS) return;

            String action = intent.getAction();
            String timestamp = getCurrentTimestamp();
            String status = "";

            if (WebSocketService.ACTION_STATUS_UPDATE.equals(action)) {
                status = intent.getStringExtra(WebSocketService.EXTRA_STATUS);
                String logEntry = timestamp + " WS_Status: " + status + "\n";
                statusLog.append(logEntry);
                Log.d(TAG, "WebSocket Service Status Update: " + status);

                isWebSocketServiceActive = "Connected to ESP32".equals(status);

                if ("Service Stopped".equals(status)) {
                    isWebSocketServiceActive = false;
                    updateUIForWsDisconnected(); // Or a more specific state
                } else if (status.startsWith("Connection Failed") || "Disconnected".equals(status) || "Disconnected by user".equals(status)) {
                    isWebSocketServiceActive = false;
                    updateUIForWsDisconnected();
                } else if ("Connected to ESP32".equals(status)) {
                    updateUIForWsConnected();
                } else if (status.startsWith("Connecting to")) {
                    updateUIForWsConnecting();
                }
                textViewStatus.setText("Status (WS): " + status);

            } else if (WebSocketService.ACTION_MESSAGE_RECEIVED.equals(action)) {
                String title = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_TITLE);
                String body = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_BODY);
                String logEntry = timestamp + " WS_Message: [" + title + "] " + body + "\n";
                messageLog.append(logEntry);
                textViewLastMessage.setText("Last WS Msg: " + title + " - " + body);
                Log.d(TAG, "WebSocket Service Message Received: " + title + " - " + body);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextEspIpDisplay = findViewById(R.id.editTextEspIp);
        radioGroupMonitorMode = findViewById(R.id.radioGroupMonitorMode);
        radioButtonOta = findViewById(R.id.radioButtonOta);
        radioButtonMainApp = findViewById(R.id.radioButtonMainApp);
        buttonStartStopDiscovery = findViewById(R.id.buttonStartService); // Will rename in UI
        buttonAction = findViewById(R.id.buttonConnect); // Will rename / change function
        buttonSaveLog = findViewById(R.id.buttonSaveLog);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewLastMessage = findViewById(R.id.textViewLastMessage);

        // --- Initial UI Setup ---
        editTextEspIpDisplay.setHint("Service Info (Auto-Discovering...)");
        editTextEspIpDisplay.setEnabled(false);
        editTextEspIpDisplay.setFocusable(false);
        buttonStartStopDiscovery.setText("Start Discovery");

        askNotificationPermission();
        nsdHelper = new NsdHelper(this, this);

        createFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                saveLogToFile(uri);
            } else {
                Toast.makeText(MainActivity.this, "Log saving cancelled.", Toast.LENGTH_SHORT).show();
            }
        });

        radioGroupMonitorMode.setOnCheckedChangeListener((group, checkedId) -> {
            stopActiveDiscoveryAndReset(); // Stop current discovery and clear resolved service
            if (checkedId == R.id.radioButtonOta) {
                currentMonitorMode = MonitorMode.OTA;
            } else if (checkedId == R.id.radioButtonMainApp) {
                currentMonitorMode = MonitorMode.MAIN_APP_WS;
            }
            updateUIForInitialState(); // Reset UI to reflect new mode
        });

        buttonStartStopDiscovery.setOnClickListener(v -> {
            if (nsdHelper.isDiscoveryActive()) {
                stopActiveDiscoveryAndReset();
            } else {
                startDiscoveryForCurrentMode();
            }
        });

        buttonAction.setOnClickListener(v -> {
            if (resolvedEspServiceInfo == null) {
                Toast.makeText(this, "No service found yet. Start discovery.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (currentMonitorMode == MonitorMode.OTA) {
                // Open Web Page
                String hostAddress = resolvedEspServiceInfo.getHost().getHostAddress();
                int port = resolvedEspServiceInfo.getPort();
                String url = "http://" + hostAddress + ":" + port;
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                try {
                    startActivity(browserIntent);
                    statusLog.append(getCurrentTimestamp()).append(" CMD: Opened OTA page at ").append(url).append("\n");
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "No web browser found to open URL.", Toast.LENGTH_LONG).show();
                    statusLog.append(getCurrentTimestamp()).append(" ERR: No browser for OTA page ").append(url).append("\n");
                }
            } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
                // Connect or Disconnect WebSocket
                if (isWebSocketServiceActive) {
                    // Disconnect
                    Intent serviceIntent = new Intent(this, WebSocketService.class);
                    serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT);
                    startService(serviceIntent); // Service handles actual disconnect
                    statusLog.append(getCurrentTimestamp()).append(" CMD: Disconnect WebSocket\n");
                    // UI will be updated by BroadcastReceiver
                } else {
                    // Connect
                    if (resolvedEspServiceInfo.getHost() != null) {
                        String wsUrl = "ws://" + resolvedEspServiceInfo.getHost().getHostAddress() + ":" + resolvedEspServiceInfo.getPort() + ESP_WEBSOCKET_PATH;

                        // Ensure WebSocketService is running in foreground first
                        Intent startFgIntent = new Intent(this, WebSocketService.class);
                        startFgIntent.setAction(WebSocketService.ACTION_START_FOREGROUND_SERVICE);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(startFgIntent);
                        } else {
                            startService(startFgIntent);
                        }

                        // Then send connect command
                        Intent connectIntent = new Intent(this, WebSocketService.class);
                        connectIntent.setAction(WebSocketService.ACTION_CONNECT);
                        connectIntent.putExtra(WebSocketService.EXTRA_IP_ADDRESS, wsUrl);
                        startService(connectIntent);
                        statusLog.append(getCurrentTimestamp()).append(" CMD: Connect WebSocket to ").append(wsUrl).append("\n");
                        // UI will update via BroadcastReceiver
                    } else {
                        Toast.makeText(this, "Service resolved but host is null!", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        buttonSaveLog.setOnClickListener(v -> {
            String fileName = "MrCoopersESP32_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            createFileLauncher.launch(fileName);
        });

        // Set initial state based on default mode
        if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
            radioButtonMainApp.setChecked(true);
        } else {
            radioButtonOta.setChecked(true);
        }
        updateUIForInitialState();
    }

    private void startDiscoveryForCurrentMode() {
        if (nsdHelper.isDiscoveryActive()) {
            Log.d(TAG, "Discovery already active for " + activeDiscoveryServiceType);
            return;
        }
        resolvedEspServiceInfo = null; // Clear previous findings

        if (currentMonitorMode == MonitorMode.OTA) {
            activeDiscoveryServiceType = OTA_SERVICE_TYPE;
            nsdHelper.discoverServices(OTA_SERVICE_NAME_FILTER, OTA_SERVICE_TYPE);
            statusLog.append(getCurrentTimestamp()).append(" CMD: Start Discovery for OTA (").append(OTA_SERVICE_NAME_FILTER).append(")\n");
        } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
            activeDiscoveryServiceType = MAIN_APP_WS_SERVICE_TYPE;
            nsdHelper.discoverServices(MAIN_APP_WS_SERVICE_NAME_FILTER, MAIN_APP_WS_SERVICE_TYPE);
            statusLog.append(getCurrentTimestamp()).append(" CMD: Start Discovery for Main App WS (").append(MAIN_APP_WS_SERVICE_NAME_FILTER).append(")\n");
            textViewLastMessage.setText("Last WS Msg: None"); // Reset
        }
        updateUIForDiscovering();
    }

    private void stopActiveDiscoveryAndReset() {
        if (nsdHelper.isDiscoveryActive()) {
            nsdHelper.stopDiscovery();
            statusLog.append(getCurrentTimestamp()).append(" CMD: Stop Discovery for ").append(activeDiscoveryServiceType).append("\n");
        }
        activeDiscoveryServiceType = null;
        resolvedEspServiceInfo = null;
        // Do not stop WebSocketService here if it's connected, only discovery.
        // WebSocketService stop/disconnect is handled by its own logic or buttonAction.
        // If it was in MAIN_APP_WS mode and WS was connected, it remains connected.
        // If it was OTA, UI simply resets.
        updateUIForInitialState(); // Or a more nuanced "discovery stopped" UI update
    }


    // --- UI Update Methods ---
    private void updateUIForInitialState() {
        textViewStatus.setText("Status: Idle. Select mode and start discovery.");
        editTextEspIpDisplay.setText("");
        buttonStartStopDiscovery.setText("Start Discovery");
        buttonStartStopDiscovery.setEnabled(true);
        radioGroupMonitorMode.setEnabled(true);

        if (currentMonitorMode == MonitorMode.OTA) {
            buttonAction.setText("Open OTA Page");
            buttonAction.setEnabled(false); // Enable when service resolved
            textViewLastMessage.setVisibility(View.GONE);
        } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
            buttonAction.setText("Connect WS");
            buttonAction.setEnabled(false); // Enable when service resolved
            textViewLastMessage.setVisibility(View.VISIBLE);
            textViewLastMessage.setText("Last WS Msg: None");
             if(isWebSocketServiceActive) updateUIForWsConnected(); // Persist connected state if service is active
        }
    }

    private void updateUIForDiscovering() {
        textViewStatus.setText("Status: Discovering " +
                (currentMonitorMode == MonitorMode.OTA ? "OTA Server (" + OTA_SERVICE_NAME_FILTER + ")" : "Main App WS (" + MAIN_APP_WS_SERVICE_NAME_FILTER + ")") + "...");
        editTextEspIpDisplay.setText("");
        buttonStartStopDiscovery.setText("Stop Discovery");
        buttonStartStopDiscovery.setEnabled(true);
        buttonAction.setEnabled(false); // Disabled until a service is resolved
        radioGroupMonitorMode.setEnabled(false); // Disable mode change during discovery
    }

    private void updateUIForOtaServiceResolved() {
        if (resolvedEspServiceInfo == null) return;
        String serviceName = resolvedEspServiceInfo.getServiceName();
        String host = resolvedEspServiceInfo.getHost() != null ? resolvedEspServiceInfo.getHost().getHostAddress() : "N/A";
        int port = resolvedEspServiceInfo.getPort();
        String displayText = String.format(Locale.US, "Found OTA: %s\nHost: %s:%d", serviceName, host, port);
        editTextEspIpDisplay.setText(displayText);
        textViewStatus.setText("Status: OTA Server Found!");
        buttonStartStopDiscovery.setText("Start Discovery"); // Ready for new discovery
        buttonStartStopDiscovery.setEnabled(true);
        buttonAction.setText("Open OTA Page");
        buttonAction.setEnabled(true);
        radioGroupMonitorMode.setEnabled(true);
    }

    private void updateUIForWsServiceResolvedNotConnected() {
        if (resolvedEspServiceInfo == null) return;
        String serviceName = resolvedEspServiceInfo.getServiceName();
        String host = resolvedEspServiceInfo.getHost() != null ? resolvedEspServiceInfo.getHost().getHostAddress() : "N/A";
        int port = resolvedEspServiceInfo.getPort();
        String displayText = String.format(Locale.US, "Found WS: %s\nHost: %s:%d", serviceName, host, port);
        editTextEspIpDisplay.setText(displayText);
        textViewStatus.setText("Status: Main App WS Found. Ready to connect.");
        buttonStartStopDiscovery.setText("Start Discovery"); // Ready for new discovery
        buttonStartStopDiscovery.setEnabled(true);
        buttonAction.setText("Connect WS");
        buttonAction.setEnabled(true);
        radioGroupMonitorMode.setEnabled(true);
    }

    private void updateUIForWsConnecting() {
        textViewStatus.setText("Status (WS): Connecting...");
        // buttonAction might be temporarily disabled or show "Connecting..."
        buttonAction.setEnabled(false); // Disable action while connecting
        radioGroupMonitorMode.setEnabled(false); // Keep disabled
        buttonStartStopDiscovery.setEnabled(false); // Keep disabled
    }

    private void updateUIForWsConnected() {
        if (resolvedEspServiceInfo == null && currentMonitorMode == MonitorMode.MAIN_APP_WS) {
             // If connected but resolvedEspServiceInfo is null (e.g. app restart while service was connected)
             // We don't have the display text, but WebSocketService is connected.
             textViewStatus.setText("Status (WS): Connected");
             editTextEspIpDisplay.setText("Connected (Service was running)");
        } else if (resolvedEspServiceInfo != null) {
            String serviceName = resolvedEspServiceInfo.getServiceName();
            String host = resolvedEspServiceInfo.getHost() != null ? resolvedEspServiceInfo.getHost().getHostAddress() : "N/A";
            int port = resolvedEspServiceInfo.getPort();
            String displayText = String.format(Locale.US, "WS: %s\nHost: %s:%d", serviceName, host, port);
            editTextEspIpDisplay.setText(displayText);
            textViewStatus.setText("Status (WS): Connected to " + serviceName);
        }

        buttonAction.setText("Disconnect WS");
        buttonAction.setEnabled(true);
        buttonStartStopDiscovery.setText("Start Discovery"); // Can still look for other services
        buttonStartStopDiscovery.setEnabled(true);
        radioGroupMonitorMode.setEnabled(true); // Can change mode now
    }

    private void updateUIForWsDisconnected() {
        textViewStatus.setText("Status (WS): Disconnected. Discover or Connect.");
        // editTextEspIpDisplay can retain old info or be cleared
        // If resolvedEspServiceInfo still exists, we can attempt to reconnect
        if (resolvedEspServiceInfo != null && currentMonitorMode == MonitorMode.MAIN_APP_WS) {
            updateUIForWsServiceResolvedNotConnected(); // Revert to "found, ready to connect" state
        } else {
            updateUIForInitialState(); // Or a more specific "WS was disconnected" state
        }
        buttonAction.setText("Connect WS");
        buttonAction.setEnabled(resolvedEspServiceInfo != null);
    }


    // --- NsdHelper.NsdHelperListener Implementation ---
    @Override
    public void onNsdServiceFound(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Candidate Found: " + serviceInfo.getServiceName() + " Type: " + serviceInfo.getServiceType());
            // Update status to show a candidate is being processed, only if discovery is active for this type.
            // This check ensures that if a user quickly switches modes, a "found" message for an old discovery type isn't shown.
            if (nsdHelper.isDiscoveryActive() && serviceInfo.getServiceType().startsWith(activeDiscoveryServiceType.substring(0, activeDiscoveryServiceType.indexOf("."))) ) {
                if (currentMonitorMode == MonitorMode.OTA && OTA_SERVICE_NAME_FILTER.equalsIgnoreCase(serviceInfo.getServiceName())) {
                    textViewStatus.setText("Status: OTA Candidate " + serviceInfo.getServiceName() + " found. Resolving...");
                } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS && MAIN_APP_WS_SERVICE_NAME_FILTER.equalsIgnoreCase(serviceInfo.getServiceName())) {
                    textViewStatus.setText("Status: Main App Candidate " + serviceInfo.getServiceName() + " found. Resolving...");
                }
            }
        });
    }

    @Override
    public void onNsdServiceResolved(NsdServiceInfo serviceInfo, String serviceTypeDiscovered) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Service Resolved: " + serviceInfo.getServiceName() +
                    " on host " + serviceInfo.getHost() + ":" + serviceInfo.getPort() +
                    " (Original discovery type: " + serviceTypeDiscovered + ")");

            // Critical: Ensure this resolved service matches the *currently active* discovery type and mode
            if (!nsdHelper.isDiscoveryActive() && !serviceTypeDiscovered.equals(activeDiscoveryServiceType)) {
                 Log.w(TAG, "Resolved service " + serviceInfo.getServiceName() + " for a discovery ("+serviceTypeDiscovered+") that is no longer active or matches current mode. Ignoring.");
                 return;
            }

            resolvedEspServiceInfo = serviceInfo;
            statusLog.append(getCurrentTimestamp()).append(" NSD: Resolved ").append(serviceInfo.getServiceName()).append(" at ").append(serviceInfo.getHost().getHostAddress()).append(":").append(serviceInfo.getPort()).append("\n");

            if (currentMonitorMode == MonitorMode.OTA && OTA_SERVICE_TYPE.equals(serviceTypeDiscovered) && OTA_SERVICE_NAME_FILTER.equalsIgnoreCase(serviceInfo.getServiceName())) {
                updateUIForOtaServiceResolved();
                nsdHelper.stopDiscovery(); // Found our target
            } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS && MAIN_APP_WS_SERVICE_TYPE.equals(serviceTypeDiscovered) && MAIN_APP_WS_SERVICE_NAME_FILTER.equalsIgnoreCase(serviceInfo.getServiceName())) {
                updateUIForWsServiceResolvedNotConnected();
                nsdHelper.stopDiscovery(); // Found our target
            } else {
                Log.w(TAG, "Resolved service '" + serviceInfo.getServiceName() + "' of type '" + serviceTypeDiscovered + "' but current mode/filter does not match. Mode: " + currentMonitorMode);
                // If it doesn't match, don't stop discovery, let it continue for the right service.
            }
        });
    }

    @Override
    public void onNsdServiceLost(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.w(TAG, "NSD Service Lost: " + serviceInfo.getServiceName());
            statusLog.append(getCurrentTimestamp()).append(" NSD: Lost ").append(serviceInfo.getServiceName()).append("\n");

            if (resolvedEspServiceInfo != null && serviceInfo.getServiceName().equalsIgnoreCase(resolvedEspServiceInfo.getServiceName())) {
                resolvedEspServiceInfo = null;
                Toast.makeText(this, serviceInfo.getServiceName() + " lost.", Toast.LENGTH_SHORT).show();

                if (currentMonitorMode == MonitorMode.MAIN_APP_WS && isWebSocketServiceActive) {
                    // If it was the WebSocket service that got lost, tell WebSocketService to disconnect/handle it
                    Intent serviceIntent = new Intent(this, WebSocketService.class);
                    serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT); // Or a specific "handle_lost_server" action
                    startService(serviceIntent);
                    updateUIForWsDisconnected();
                } else {
                    updateUIForInitialState(); // Reset to allow new discovery
                }
                // Optionally restart discovery for the current mode if desired
                // if (!nsdHelper.isDiscoveryActive()) {
                //     startDiscoveryForCurrentMode();
                // }
            }
        });
    }

    @Override
    public void onNsdDiscoveryFailed(String serviceType, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Discovery Failed for " + serviceType + ". Error: " + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD: Discovery Failed (").append(serviceType).append("). Error: ").append(errorCode).append("\n");
            textViewStatus.setText("Status: Discovery Failed (" + serviceTypeToShortName(serviceType) + "). Check Network/Permissions.");
            updateUIForInitialState(); // Reset UI
            buttonStartStopDiscovery.setText("Start Discovery"); // Ensure it says start
            radioGroupMonitorMode.setEnabled(true);
        });
    }

    @Override
    public void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Resolve Failed for " + serviceInfo.getServiceName() + ". Error: " + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD: Resolve Failed for ").append(serviceInfo.getServiceName()).append(". Error: ").append(errorCode).append("\n");
            textViewStatus.setText("Status: Failed to resolve " + serviceInfo.getServiceName() + ". Still searching if discovery active...");
            // Don't necessarily change UI too much, discovery might still be running for other candidates.
            // If discovery stops due to this, onNsdDiscoveryStopped will handle UI reset.
        });
    }

    @Override
    public void onNsdDiscoveryStarted(String serviceType) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Discovery actually started for " + serviceType);
            activeDiscoveryServiceType = serviceType; // Confirm active discovery type
            updateUIForDiscovering();
        });
    }

    @Override
    public void onNsdDiscoveryStopped(String serviceType) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Discovery actually stopped for " + serviceType);
            activeDiscoveryServiceType = null; // Clear active discovery type
            // If discovery stopped and we haven't resolved a service for the current mode, reset UI.
            // If a service IS resolved, the UI should already reflect that.
            if (resolvedEspServiceInfo == null) {
                updateUIForInitialState();
            } else {
                // If a service IS resolved, ensure UI matches (e.g. if stopped manually after resolve)
                if (currentMonitorMode == MonitorMode.OTA) updateUIForOtaServiceResolved();
                else if (currentMonitorMode == MonitorMode.MAIN_APP_WS && !isWebSocketServiceActive) updateUIForWsServiceResolvedNotConnected();
                else if (currentMonitorMode == MonitorMode.MAIN_APP_WS && isWebSocketServiceActive) updateUIForWsConnected();
            }
            buttonStartStopDiscovery.setText("Start Discovery");
            radioGroupMonitorMode.setEnabled(true);
        });
    }

    // --- Helper Methods ---
    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private String serviceTypeToShortName(String serviceType) {
        if (OTA_SERVICE_TYPE.equals(serviceType)) return "OTA";
        if (MAIN_APP_WS_SERVICE_TYPE.equals(serviceType)) return "MainAppWS";
        return "Unknown";
    }

    private void saveLogToFile(Uri uri) {
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream)) {
            writer.write("--- Status Log ---\n");
            writer.write(statusLog.toString());
            if (currentMonitorMode == MonitorMode.MAIN_APP_WS || messageLog.length() > 0) {
                writer.write("\n--- WebSocket Message Log ---\n");
                writer.write(messageLog.toString());
            }
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

    // --- Lifecycle Methods ---
    @Override
    protected void onResume() {
        super.onResume();
        if (currentMonitorMode == MonitorMode.MAIN_APP_WS && !isServiceReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WebSocketService.ACTION_STATUS_UPDATE);
            filter.addAction(WebSocketService.ACTION_MESSAGE_RECEIVED);
            LocalBroadcastManager.getInstance(this).registerReceiver(serviceUpdateReceiver, filter);
            isServiceReceiverRegistered = true;
        }
        // If discovery was active and app was paused, it might have been stopped by OS or NsdHelper.
        // Re-evaluate UI based on current state.
        if (nsdHelper.isDiscoveryActive()){
            updateUIForDiscovering();
        } else if (resolvedEspServiceInfo != null) {
             if(currentMonitorMode == MonitorMode.OTA) updateUIForOtaServiceResolved();
             else if (currentMonitorMode == MonitorMode.MAIN_APP_WS && isWebSocketServiceActive) updateUIForWsConnected();
             else if (currentMonitorMode == MonitorMode.MAIN_APP_WS && !isWebSocketServiceActive) updateUIForWsServiceResolvedNotConnected();
        } else {
            updateUIForInitialState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Optional: Stop discovery when app is paused to save battery,
        // but user might want it running in background if connected to WS.
        // For simplicity, we are not stopping discovery here, but you might want to.
        // if (nsdHelper.isDiscoveryActive()) {
        //     nsdHelper.stopDiscovery();
        // }
    }

    @Override
    protected void onDestroy() {
        if (isServiceReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
            isServiceReceiverRegistered = false;
        }
        nsdHelper.tearDown(); // Important!

        // Decide if WebSocketService should be stopped
        // If you want it to stop when MainActivity is destroyed:
        if (currentMonitorMode == MonitorMode.MAIN_APP_WS && isWebSocketServiceActive) {
             if (!isChangingConfigurations()) { // Don't stop on rotation
                Log.d(TAG, "onDestroy: Stopping WebSocketService.");
                Intent stopIntent = new Intent(this, WebSocketService.class);
                stopIntent.setAction(WebSocketService.ACTION_STOP_FOREGROUND_SERVICE);
                startService(stopIntent);
                isWebSocketServiceActive = false;
             }
        }
        super.onDestroy();
    }
}