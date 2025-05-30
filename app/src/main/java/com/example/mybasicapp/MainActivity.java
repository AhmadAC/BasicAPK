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
import android.text.TextUtils; // ### NEW ###
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
    private static final String OTA_SERVICE_NAME_FILTER = "mrcoopersesp"; // Matches ESP32 MDNS_TARGET_HOSTNAME

    private static final String MAIN_APP_WS_SERVICE_TYPE = "_myespwebsocket._tcp"; // Matches ESP32 WEBSOCKET_MDNS_SERVICE_TYPE
    // ### MODIFIED ### This filter should match the ESP32's mDNS hostname (instance name for the service)
    private static final String MAIN_APP_WS_SERVICE_NAME_FILTER = "mrcoopersesp"; 
    private static final String ESP_WEBSOCKET_PATH = "/ws";
    private static final int ESP_DEFAULT_PORT = 80; // ### NEW ###

    private enum MonitorMode {
        OTA,
        MAIN_APP_WS
    }
    private MonitorMode currentMonitorMode = MonitorMode.MAIN_APP_WS;

    private EditText editTextEspIpDisplay;
    private Button buttonStartStopDiscovery;
    private Button buttonAction;
    private Button buttonSaveLog;
    private TextView textViewStatus;
    private TextView textViewLastMessage;
    private RadioGroup radioGroupMonitorMode;
    private RadioButton radioButtonOta, radioButtonMainApp;

    private boolean isServiceReceiverRegistered = false;
    private StringBuilder statusLog = new StringBuilder();
    private StringBuilder messageLog = new StringBuilder();

    private ActivityResultLauncher<String> createFileLauncher;
    private NsdHelper nsdHelper;
    private NsdServiceInfo resolvedEspServiceInfo = null;
    private String activeDiscoveryServiceType = null;
    private boolean isWebSocketServiceActive = false;
    private boolean manualIpEntryMode = false; // ### NEW ###

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
                    updateUIForWsDisconnected();
                } else if (status.startsWith("Connection Failed")) { // ### MODIFIED ### More specific handling for failed state
                    isWebSocketServiceActive = false;
                    if (resolvedEspServiceInfo == null && currentMonitorMode == MonitorMode.MAIN_APP_WS) {
                        // If connection failed AND we don't have an mDNS resolved service, re-enable manual input
                        editTextEspIpDisplay.setEnabled(true);
                        editTextEspIpDisplay.setFocusableInTouchMode(true);
                        editTextEspIpDisplay.setHint("Conn. Failed. Retry or enter address.");
                        editTextEspIpDisplay.setText(""); // Clear old resolved info
                        manualIpEntryMode = true;
                        buttonAction.setText("Connect WS");
                        buttonAction.setEnabled(true);
                        textViewStatus.setText("Status (WS): " + status + ". Enter address or discover.");
                    } else {
                         updateUIForWsDisconnected(); // Handles cases where resolvedEspServiceInfo might exist
                    }
                } else if ("Disconnected".equals(status) || "Disconnected by user".equals(status)) {
                    isWebSocketServiceActive = false;
                    updateUIForWsDisconnected();
                } else if ("Connected to ESP32".equals(status)) {
                    updateUIForWsConnected();
                } else if (status.startsWith("Connecting to")) {
                    updateUIForWsConnecting();
                }
                if (!status.startsWith("Connection Failed")) { // Avoid double-setting status text if already handled
                    textViewStatus.setText("Status (WS): " + status);
                }

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
        buttonStartStopDiscovery = findViewById(R.id.buttonStartService);
        buttonAction = findViewById(R.id.buttonConnect);
        buttonSaveLog = findViewById(R.id.buttonSaveLog);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewLastMessage = findViewById(R.id.textViewLastMessage);

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
            stopActiveDiscoveryAndReset();
            if (checkedId == R.id.radioButtonOta) {
                currentMonitorMode = MonitorMode.OTA;
            } else if (checkedId == R.id.radioButtonMainApp) {
                currentMonitorMode = MonitorMode.MAIN_APP_WS;
            }
            updateUIForInitialState();
        });

        buttonStartStopDiscovery.setOnClickListener(v -> {
            if (nsdHelper.isDiscoveryActive()) {
                stopActiveDiscoveryAndReset();
            } else {
                startDiscoveryForCurrentMode();
            }
        });

        buttonAction.setOnClickListener(v -> { // ### MODIFIED ### For manual WS connection
            if (currentMonitorMode == MonitorMode.OTA) {
                if (resolvedEspServiceInfo == null) {
                    Toast.makeText(this, "No OTA service found yet. Start discovery.", Toast.LENGTH_SHORT).show();
                    return;
                }
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
                if (isWebSocketServiceActive) { // Disconnect
                    Intent serviceIntent = new Intent(this, WebSocketService.class);
                    serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT);
                    startService(serviceIntent);
                    statusLog.append(getCurrentTimestamp()).append(" CMD: Disconnect WebSocket\n");
                } else { // Connect
                    String wsUrl = null;
                    if (resolvedEspServiceInfo != null && resolvedEspServiceInfo.getHost() != null) {
                        wsUrl = "ws://" + resolvedEspServiceInfo.getHost().getHostAddress() + ":" + resolvedEspServiceInfo.getPort() + ESP_WEBSOCKET_PATH;
                        statusLog.append(getCurrentTimestamp()).append(" CMD: Auto Connect WebSocket to ").append(wsUrl).append("\n");
                    } else if (manualIpEntryMode) {
                        String manualHostInput = editTextEspIpDisplay.getText().toString().trim();
                        if (!TextUtils.isEmpty(manualHostInput)) {
                            if (manualHostInput.startsWith("ws://")) {
                                wsUrl = manualHostInput; // Assume full URL
                            } else {
                                String hostPart = manualHostInput;
                                int portPart = ESP_DEFAULT_PORT;
                                if (manualHostInput.contains(":")) {
                                    String[] parts = manualHostInput.split(":", 2);
                                    hostPart = parts[0];
                                    try {
                                        if (parts.length > 1 && !TextUtils.isEmpty(parts[1])) {
                                            portPart = Integer.parseInt(parts[1]);
                                        }
                                    } catch (NumberFormatException e) {
                                        Toast.makeText(this, "Invalid port in address: " + manualHostInput, Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                }
                                wsUrl = "ws://" + hostPart + ":" + portPart + ESP_WEBSOCKET_PATH;
                            }
                            Log.d(TAG, "Using manual WebSocket URL: " + wsUrl);
                            statusLog.append(getCurrentTimestamp()).append(" CMD: Manual Connect WebSocket to ").append(wsUrl).append("\n");
                        } else {
                            Toast.makeText(this, "Enter ESP32 address or discover service.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    if (wsUrl != null) {
                        Intent startFgIntent = new Intent(this, WebSocketService.class);
                        startFgIntent.setAction(WebSocketService.ACTION_START_FOREGROUND_SERVICE);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(startFgIntent);
                        } else {
                            startService(startFgIntent);
                        }

                        Intent connectIntent = new Intent(this, WebSocketService.class);
                        connectIntent.setAction(WebSocketService.ACTION_CONNECT);
                        connectIntent.putExtra(WebSocketService.EXTRA_IP_ADDRESS, wsUrl);
                        startService(connectIntent);
                        // UI will update via BroadcastReceiver (updateUIForWsConnecting)
                    } else {
                        Toast.makeText(this, "No service found and no manual address entered.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        buttonSaveLog.setOnClickListener(v -> {
            String fileName = "MrCoopersESP32_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            createFileLauncher.launch(fileName);
        });

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
        resolvedEspServiceInfo = null;
        editTextEspIpDisplay.setText(""); // Clear manual input field on new discovery

        if (currentMonitorMode == MonitorMode.OTA) {
            activeDiscoveryServiceType = OTA_SERVICE_TYPE;
            nsdHelper.discoverServices(OTA_SERVICE_NAME_FILTER, OTA_SERVICE_TYPE);
            statusLog.append(getCurrentTimestamp()).append(" CMD: Start Discovery for OTA (").append(OTA_SERVICE_NAME_FILTER).append(")\n");
        } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
            activeDiscoveryServiceType = MAIN_APP_WS_SERVICE_TYPE;
            nsdHelper.discoverServices(MAIN_APP_WS_SERVICE_NAME_FILTER, MAIN_APP_WS_SERVICE_TYPE);
            statusLog.append(getCurrentTimestamp()).append(" CMD: Start Discovery for Main App WS (").append(MAIN_APP_WS_SERVICE_NAME_FILTER).append(")\n");
            textViewLastMessage.setText("Last WS Msg: None");
        }
        updateUIForDiscovering();
    }

    private void stopActiveDiscoveryAndReset() {
        if (nsdHelper.isDiscoveryActive()) {
            nsdHelper.stopDiscovery();
            statusLog.append(getCurrentTimestamp()).append(" CMD: Stop Discovery for ").append(activeDiscoveryServiceType).append("\n");
        }
        activeDiscoveryServiceType = null;
        // resolvedEspServiceInfo = null; // Keep resolved info if user just stops discovery after finding something
        updateUIBasedOnCurrentState(); // More generic update
    }

    // --- UI Update Methods --- // ### MODIFIED these methods for manualIpEntryMode
    private void updateUIForInitialState() {
        textViewStatus.setText("Status: Idle. Select mode and start discovery.");
        buttonStartStopDiscovery.setText("Start Discovery");
        buttonStartStopDiscovery.setEnabled(true);
        radioGroupMonitorMode.setEnabled(true);

        if (currentMonitorMode == MonitorMode.OTA) {
            editTextEspIpDisplay.setText("");
            editTextEspIpDisplay.setHint("Service Info (Auto-Discovering...)");
            editTextEspIpDisplay.setEnabled(false);
            editTextEspIpDisplay.setFocusable(false);
            manualIpEntryMode = false;
            buttonAction.setText("Open OTA Page");
            buttonAction.setEnabled(resolvedEspServiceInfo != null);
            textViewLastMessage.setVisibility(View.GONE);
        } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
             if (isWebSocketServiceActive) { // If already connected (e.g. app restart)
                updateUIForWsConnected();
            } else if (resolvedEspServiceInfo != null) {
                updateUIForWsServiceResolvedNotConnected();
            } else { // No connection, no resolved service: allow manual input
                editTextEspIpDisplay.setText("");
                editTextEspIpDisplay.setHint("Enter ESP mDNS/IP or Discover");
                editTextEspIpDisplay.setEnabled(true);
                editTextEspIpDisplay.setFocusableInTouchMode(true);
                manualIpEntryMode = true;
                buttonAction.setText("Connect WS");
                buttonAction.setEnabled(true); // Enable for manual input
                textViewStatus.setText("Status: Idle. Enter address or start discovery.");
            }
            textViewLastMessage.setVisibility(View.VISIBLE);
            if (!isWebSocketServiceActive) textViewLastMessage.setText("Last WS Msg: None");
        }
    }

    private void updateUIForDiscovering() {
        textViewStatus.setText("Status: Discovering " +
                (currentMonitorMode == MonitorMode.OTA ? "OTA Server (" + OTA_SERVICE_NAME_FILTER + ")" : "Main App WS (" + MAIN_APP_WS_SERVICE_NAME_FILTER + ")") + "...");
        editTextEspIpDisplay.setText("");
        editTextEspIpDisplay.setHint("Discovering...");
        editTextEspIpDisplay.setEnabled(false);
        editTextEspIpDisplay.setFocusable(false);
        manualIpEntryMode = false;
        buttonStartStopDiscovery.setText("Stop Discovery");
        buttonStartStopDiscovery.setEnabled(true);
        buttonAction.setEnabled(false);
        radioGroupMonitorMode.setEnabled(false);
    }

    private void updateUIForOtaServiceResolved() {
        if (resolvedEspServiceInfo == null) return;
        String serviceName = resolvedEspServiceInfo.getServiceName();
        String host = resolvedEspServiceInfo.getHost() != null ? resolvedEspServiceInfo.getHost().getHostAddress() : "N/A";
        int port = resolvedEspServiceInfo.getPort();
        String displayText = String.format(Locale.US, "Found OTA: %s\nHost: %s:%d", serviceName, host, port);
        
        editTextEspIpDisplay.setText(displayText);
        editTextEspIpDisplay.setEnabled(false);
        editTextEspIpDisplay.setFocusable(false);
        manualIpEntryMode = false;
        textViewStatus.setText("Status: OTA Server Found!");
        buttonStartStopDiscovery.setText("Start Discovery"); 
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
        editTextEspIpDisplay.setEnabled(false);
        editTextEspIpDisplay.setFocusable(false);
        manualIpEntryMode = false;
        textViewStatus.setText("Status: Main App WS Found. Ready to connect.");
        buttonStartStopDiscovery.setText("Start Discovery");
        buttonStartStopDiscovery.setEnabled(true);
        buttonAction.setText("Connect WS");
        buttonAction.setEnabled(true);
        radioGroupMonitorMode.setEnabled(true);
    }

    private void updateUIForWsConnecting() {
        textViewStatus.setText("Status (WS): Connecting...");
        editTextEspIpDisplay.setEnabled(false); // Disable input during connection attempt
        editTextEspIpDisplay.setFocusable(false);
        manualIpEntryMode = false;
        buttonAction.setText("Connecting...");
        buttonAction.setEnabled(false);
        radioGroupMonitorMode.setEnabled(false);
        buttonStartStopDiscovery.setEnabled(false);
    }

    private void updateUIForWsConnected() {
         String displayText = "Connected to ESP32";
        if (resolvedEspServiceInfo != null && resolvedEspServiceInfo.getHost() != null) { // Prefer mDNS info if available
            displayText = String.format(Locale.US, "WS: %s\nHost: %s:%d (Connected)", 
                resolvedEspServiceInfo.getServiceName(), 
                resolvedEspServiceInfo.getHost().getHostAddress(), 
                resolvedEspServiceInfo.getPort());
        } else if (!TextUtils.isEmpty(editTextEspIpDisplay.getText()) && !editTextEspIpDisplay.getHint().toString().toLowerCase().contains("discover")) {
            // If connected via manual input, show that input, otherwise generic.
             displayText = editTextEspIpDisplay.getText().toString() + " (Connected)";
        }

        editTextEspIpDisplay.setText(displayText);
        editTextEspIpDisplay.setEnabled(false);
        editTextEspIpDisplay.setFocusable(false);
        manualIpEntryMode = false;
        textViewStatus.setText("Status (WS): Connected to ESP32");
        buttonAction.setText("Disconnect WS");
        buttonAction.setEnabled(true);
        buttonStartStopDiscovery.setText("Start Discovery");
        buttonStartStopDiscovery.setEnabled(true);
        radioGroupMonitorMode.setEnabled(true);
    }

    private void updateUIForWsDisconnected() {
        textViewStatus.setText("Status (WS): Disconnected.");
        buttonAction.setText("Connect WS");
        
        if (resolvedEspServiceInfo != null && currentMonitorMode == MonitorMode.MAIN_APP_WS) {
            updateUIForWsServiceResolvedNotConnected(); // Revert to "found, ready to connect" state
        } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) { // No resolved service, enable manual
            editTextEspIpDisplay.setText("");
            editTextEspIpDisplay.setHint("Enter ESP mDNS/IP or Discover");
            editTextEspIpDisplay.setEnabled(true);
            editTextEspIpDisplay.setFocusableInTouchMode(true);
            manualIpEntryMode = true;
            buttonAction.setEnabled(true); // Enable connect button for manual input
        } else { // OTA mode or other
            updateUIForInitialState();
        }
        // Ensure discovery button and radio group are enabled if not discovering
        if (!nsdHelper.isDiscoveryActive()) {
             buttonStartStopDiscovery.setText("Start Discovery");
             buttonStartStopDiscovery.setEnabled(true);
             radioGroupMonitorMode.setEnabled(true);
        }
    }

    private void updateUIBasedOnCurrentState() { // ### NEW ### Helper to consolidate UI updates
        if (nsdHelper.isDiscoveryActive()) {
            updateUIForDiscovering();
        } else if (isWebSocketServiceActive && currentMonitorMode == MonitorMode.MAIN_APP_WS) {
            updateUIForWsConnected();
        } else if (resolvedEspServiceInfo != null) {
            if (currentMonitorMode == MonitorMode.OTA) {
                updateUIForOtaServiceResolved();
            } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
                updateUIForWsServiceResolvedNotConnected();
            }
        } else {
            updateUIForInitialState();
        }
    }


    // --- NsdHelper.NsdHelperListener Implementation ---
    @Override
    public void onNsdServiceFound(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Candidate Found: " + serviceInfo.getServiceName() + " Type: " + serviceInfo.getServiceType());
            if (nsdHelper.isDiscoveryActive() && activeDiscoveryServiceType != null &&
                serviceInfo.getServiceType().toLowerCase().startsWith(activeDiscoveryServiceType.toLowerCase().replace(".local.", ""))) {
                
                String filterName = (currentMonitorMode == MonitorMode.OTA) ? OTA_SERVICE_NAME_FILTER : MAIN_APP_WS_SERVICE_NAME_FILTER;
                if (filterName.equalsIgnoreCase(serviceInfo.getServiceName())) {
                     textViewStatus.setText("Status: Candidate " + serviceInfo.getServiceName() + " found. Resolving...");
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

            // Check if this resolution is still relevant for the current mode and discovery type
            if (activeDiscoveryServiceType == null || !serviceTypeDiscovered.toLowerCase().startsWith(activeDiscoveryServiceType.toLowerCase().replace(".local.",""))) {
                 Log.w(TAG, "Resolved service " + serviceInfo.getServiceName() + " for a discovery ("+serviceTypeDiscovered+") that is no longer active or matches current mode. Ignoring.");
                 return;
            }

            resolvedEspServiceInfo = serviceInfo; // Store resolved info
            statusLog.append(getCurrentTimestamp()).append(" NSD: Resolved ").append(serviceInfo.getServiceName()).append(" at ").append(serviceInfo.getHost().getHostAddress()).append(":").append(serviceInfo.getPort()).append("\n");

            String filterName = (currentMonitorMode == MonitorMode.OTA) ? OTA_SERVICE_NAME_FILTER : MAIN_APP_WS_SERVICE_NAME_FILTER;
            
            if (filterName.equalsIgnoreCase(serviceInfo.getServiceName())) {
                if (currentMonitorMode == MonitorMode.OTA) {
                    updateUIForOtaServiceResolved();
                } else if (currentMonitorMode == MonitorMode.MAIN_APP_WS) {
                    updateUIForWsServiceResolvedNotConnected();
                }
                nsdHelper.stopDiscovery(); // Found our specific target, stop discovery.
            } else {
                 Log.w(TAG, "Resolved service '" + serviceInfo.getServiceName() + "' but name does not match filter '" + filterName + "'. Continuing discovery if active.");
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
                    Intent serviceIntent = new Intent(this, WebSocketService.class);
                    serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT); 
                    startService(serviceIntent);
                    // updateUIForWsDisconnected(); // Broadcast receiver will handle this
                } else {
                   updateUIBasedOnCurrentState();
                }
            }
        });
    }

    @Override
    public void onNsdDiscoveryFailed(String serviceType, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Discovery Failed for " + serviceType + ". Error: " + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD: Discovery Failed (").append(serviceType).append("). Error: ").append(errorCode).append("\n");
            textViewStatus.setText("Status: Discovery Failed (" + serviceTypeToShortName(serviceType) + "). Check Network/Permissions.");
            updateUIBasedOnCurrentState();
        });
    }

    @Override
    public void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Resolve Failed for " + serviceInfo.getServiceName() + ". Error: " + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD: Resolve Failed for ").append(serviceInfo.getServiceName()).append(". Error: ").append(errorCode).append("\n");
            textViewStatus.setText("Status: Failed to resolve " + serviceInfo.getServiceName() + ". Still searching if discovery active...");
            // No major UI change, let discovery continue or stop naturally.
        });
    }

    @Override
    public void onNsdDiscoveryStarted(String serviceType) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Discovery actually started for " + serviceType);
            activeDiscoveryServiceType = serviceType; 
            updateUIForDiscovering();
        });
    }

    @Override
    public void onNsdDiscoveryStopped(String serviceType) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Discovery actually stopped for " + serviceType);
            activeDiscoveryServiceType = null; 
            updateUIBasedOnCurrentState(); // Update UI based on whether a service was found or not
        });
    }

    // --- Helper Methods ---
    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private String serviceTypeToShortName(String serviceType) {
        if (OTA_SERVICE_TYPE.equalsIgnoreCase(serviceType) || serviceType.contains("http")) return "OTA"; // More robust check
        if (MAIN_APP_WS_SERVICE_TYPE.equalsIgnoreCase(serviceType) || serviceType.contains("myespwebsocket")) return "MainAppWS";
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
         updateUIBasedOnCurrentState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Optional: Stop discovery if you want to save battery when app is paused.
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
        nsdHelper.tearDown(); 

        if (currentMonitorMode == MonitorMode.MAIN_APP_WS && isWebSocketServiceActive) {
             if (!isChangingConfigurations()) { 
                Log.d(TAG, "onDestroy: Stopping WebSocketService.");
                Intent stopIntent = new Intent(this, WebSocketService.class);
                stopIntent.setAction(WebSocketService.ACTION_STOP_FOREGROUND_SERVICE);
                startService(stopIntent); // Ask service to stop itself
                isWebSocketServiceActive = false;
             }
        }
        super.onDestroy();
    }
}