package com.example.mybasicapp;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler; // Added for timeout
import android.os.Looper;  // Added for timeout
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements NsdHelper.NsdHelperListener, DiscoveredServicesAdapter.OnServiceClickListener {

    private static final String TAG = "MainActivity_DEBUG"; // Enhanced Tag
    private static final String ESP_WEBSOCKET_SERVICE_TYPE = "_myespwebsocket._tcp";
    private static final String ESP_SERVICE_NAME_FILTER = "mrcoopersesp";
    private static final String ESP_WEBSOCKET_PATH = "/ws";
    private static final int ESP_DEFAULT_PORT = 80;
    private static final long NSD_DISCOVERY_TIMEOUT_MS = 15000; // 15 seconds for mDNS scan

    private TextInputLayout textInputLayoutEspAddress;
    private TextInputEditText editTextEspAddress;
    private Button buttonConnectManual, buttonDisconnect, buttonStartStopDiscovery, buttonSaveLog;
    private TextView textViewStatus, textViewLastMessage, textViewDiscoveredServicesTitle;
    private RecyclerView recyclerViewDiscoveredServices;
    private DiscoveredServicesAdapter discoveredServicesAdapter;
    private List<DiscoveredService> discoveredServiceList = new ArrayList<>();

    private NsdHelper nsdHelper;
    private StringBuilder statusLog = new StringBuilder();
    private StringBuilder messageLog = new StringBuilder();
    private ActivityResultLauncher<String> createFileLauncher;
    private boolean isWebSocketServiceActive = false;
    private boolean isServiceReceiverRegistered = false;
    private String lastAttemptedUrl = null;
    private Handler discoveryTimeoutHandler = new Handler(Looper.getMainLooper());


    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                Log.d(TAG, "Notification permission granted: " + isGranted);
                if (isGranted) {
                    Toast.makeText(this, "Notifications permission granted.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Notifications permission denied. App may not show alerts.", Toast.LENGTH_LONG).show();
                }
            });

    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String timestamp = getCurrentTimestamp();
            String statusMessage;
            Log.d(TAG, "serviceUpdateReceiver: Received action: " + action);

            if (WebSocketService.ACTION_STATUS_UPDATE.equals(action)) {
                statusMessage = intent.getStringExtra(WebSocketService.EXTRA_STATUS);
                if (statusMessage == null) statusMessage = "Unknown status from service";
                String logEntry = timestamp + " WS_Service_Status_RCV: " + statusMessage + "\n";
                statusLog.append(logEntry);
                Log.i(TAG, "serviceUpdateReceiver << WS_Status: " + statusMessage);

                if (statusMessage.toLowerCase().startsWith("connected to")) {
                    isWebSocketServiceActive = true;
                    updateUIForWsConnected(statusMessage);
                } else if (statusMessage.toLowerCase().startsWith("connecting to")) {
                    isWebSocketServiceActive = false;
                    updateUIForWsConnecting(statusMessage);
                } else if (statusMessage.toLowerCase().startsWith("disconnected") ||
                        statusMessage.toLowerCase().startsWith("connection failed") ||
                        statusMessage.toLowerCase().startsWith("error:") ||
                        statusMessage.toLowerCase().equals("service stopped")) {
                    isWebSocketServiceActive = false;
                    updateUIForWsDisconnected(statusMessage);
                } else {
                    textViewStatus.setText("WS Status: " + statusMessage);
                }

            } else if (WebSocketService.ACTION_MESSAGE_RECEIVED.equals(action)) {
                String title = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_TITLE);
                String body = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_BODY);
                String logEntry = timestamp + " WS_Message_RCV: [" + title + "] " + body + "\n";
                messageLog.append(logEntry);
                statusLog.append(logEntry);
                textViewLastMessage.setText(String.format(Locale.getDefault(),"Last WS Msg: [%s] %s", title, body));
                Log.i(TAG, "serviceUpdateReceiver << WS_Message: " + title + " - " + body);
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Activity Creating");
        setContentView(R.layout.activity_main);

        textInputLayoutEspAddress = findViewById(R.id.textInputLayoutEspAddress);
        editTextEspAddress = findViewById(R.id.editTextEspAddress);
        buttonConnectManual = findViewById(R.id.buttonConnectManual);
        buttonDisconnect = findViewById(R.id.buttonDisconnect);
        buttonStartStopDiscovery = findViewById(R.id.buttonStartStopDiscovery);
        buttonSaveLog = findViewById(R.id.buttonSaveLog);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewLastMessage = findViewById(R.id.textViewLastMessage);
        textViewDiscoveredServicesTitle = findViewById(R.id.textViewDiscoveredServicesTitle);
        recyclerViewDiscoveredServices = findViewById(R.id.recyclerViewDiscoveredServices);

        setupRecyclerView();
        askNotificationPermission();
        Log.d(TAG, "onCreate: Initializing NsdHelper");
        nsdHelper = new NsdHelper(this, this);

        createFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                Log.d(TAG, "createFileLauncher: URI received for saving log: " + uri.getPath());
                saveLogToFile(uri);
            } else {
                Log.d(TAG, "createFileLauncher: Log saving cancelled by user.");
                Toast.makeText(MainActivity.this, "Log saving cancelled.", Toast.LENGTH_SHORT).show();
            }
        });

        editTextEspAddress.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                boolean inputPresent = !TextUtils.isEmpty(s.toString().trim());
                Log.v(TAG, "editTextEspAddress afterTextChanged: inputPresent=" + inputPresent + ", isWebSocketServiceActive=" + isWebSocketServiceActive);
                buttonConnectManual.setEnabled(inputPresent && !isWebSocketServiceActive);
            }
        });

        buttonConnectManual.setOnClickListener(v -> {
            Log.d(TAG, "buttonConnectManual: Clicked");
            connectToEspFromInput();
        });
        buttonDisconnect.setOnClickListener(v -> {
            Log.d(TAG, "buttonDisconnect: Clicked");
            disconnectFromEsp();
        });
        buttonStartStopDiscovery.setOnClickListener(v -> {
            Log.d(TAG, "buttonStartStopDiscovery: Clicked. nsdHelper.isDiscoveryActive()=" + nsdHelper.isDiscoveryActive());
            toggleDiscovery();
        });
        buttonSaveLog.setOnClickListener(v -> {
            Log.d(TAG, "buttonSaveLog: Clicked");
            saveLog();
        });

        updateUIForInitialState();
        registerServiceReceiver();
        Log.d(TAG, "onCreate: Activity Created");
    }

    private void setupRecyclerView() {
        Log.d(TAG, "setupRecyclerView()");
        discoveredServicesAdapter = new DiscoveredServicesAdapter(this);
        recyclerViewDiscoveredServices.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewDiscoveredServices.setAdapter(discoveredServicesAdapter);
        textViewDiscoveredServicesTitle.setVisibility(View.GONE);
        recyclerViewDiscoveredServices.setVisibility(View.GONE);
    }

    private void connectToEspFromInput() {
        String address = Objects.requireNonNull(editTextEspAddress.getText()).toString().trim();
        Log.i(TAG, "connectToEspFromInput: Address from input: '" + address + "'");
        if (TextUtils.isEmpty(address)) {
            Log.w(TAG, "connectToEspFromInput: Address is empty.");
            Toast.makeText(this, "Please enter ESP32 address or select from scan.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isWebSocketServiceActive) {
            Log.w(TAG, "connectToEspFromInput: Already connected. Please disconnect first.");
            Toast.makeText(this, "Already connected. Please disconnect first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String wsUrl;
        if (address.matches("wss?://.*")) {
            wsUrl = address;
            Log.d(TAG, "connectToEspFromInput: Address is a full URL: " + wsUrl);
        } else {
            String hostPart = address;
            int portPart = ESP_DEFAULT_PORT;
            if (address.contains(":")) {
                String[] parts = address.split(":", 2);
                hostPart = parts[0];
                try {
                    if (parts.length > 1 && !TextUtils.isEmpty(parts[1])) {
                        if (parts[1].contains("/")) { // e.g., 192.168.1.5:8080/ws - less common for direct input
                            String[] portAndPath = parts[1].split("/", 2);
                            portPart = Integer.parseInt(portAndPath[0]);
                             Log.d(TAG, "connectToEspFromInput: Address contained host, port ("+portPart+"), and path /"+portAndPath[1]);
                             // Our ESP_WEBSOCKET_PATH will be appended, so this custom path is ignored unless logic changes
                        } else {
                            portPart = Integer.parseInt(parts[1]);
                        }
                    }
                } catch (NumberFormatException e) {
                    Log.e(TAG, "connectToEspFromInput: Invalid port in address: " + address, e);
                    Toast.makeText(this, "Invalid port in address: " + address, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            wsUrl = "ws://" + hostPart + ":" + portPart + ESP_WEBSOCKET_PATH;
            Log.d(TAG, "connectToEspFromInput: Constructed URL: " + wsUrl);
        }
        lastAttemptedUrl = wsUrl;
        initiateWebSocketConnection(wsUrl);
    }

    private void initiateWebSocketConnection(String wsUrl) {
        statusLog.append(getCurrentTimestamp()).append(" CMD_OUT: Attempting WS connect to ").append(wsUrl).append("\n");
        Log.i(TAG, "initiateWebSocketConnection >> Service with URL: " + wsUrl);

        Intent startFgIntent = new Intent(this, WebSocketService.class);
        startFgIntent.setAction(WebSocketService.ACTION_START_FOREGROUND_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startFgIntent);
        } else {
            startService(startFgIntent);
        }
        Log.d(TAG, "initiateWebSocketConnection: ACTION_START_FOREGROUND_SERVICE sent.");

        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.d(TAG, "initiateWebSocketConnection: Sending ACTION_CONNECT for " + wsUrl);
            Intent connectIntent = new Intent(this, WebSocketService.class);
            connectIntent.setAction(WebSocketService.ACTION_CONNECT);
            connectIntent.putExtra(WebSocketService.EXTRA_IP_ADDRESS, wsUrl);
            startService(connectIntent);
        }, 250);

        updateUIForWsConnecting("Connecting to " + wsUrl.replaceFirst("ws://", "").replaceFirst(ESP_WEBSOCKET_PATH, ""));
    }

    private void disconnectFromEsp() {
        statusLog.append(getCurrentTimestamp()).append(" CMD_OUT: User requested WS Disconnect\n");
        Log.i(TAG, "disconnectFromEsp >> Service ACTION_DISCONNECT");
        Intent serviceIntent = new Intent(this, WebSocketService.class);
        serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT);
        startService(serviceIntent);
    }

    private Runnable discoveryTimeoutRunnable = () -> {
        Log.w(TAG, "mDNS Discovery timed out (" + NSD_DISCOVERY_TIMEOUT_MS + "ms)");
        if (nsdHelper.isDiscoveryActive()) {
            Log.d(TAG, "Discovery timeout: Stopping active discovery.");
            nsdHelper.stopDiscovery(); // This will trigger onNsdDiscoveryLifecycleChange -> updateUIDiscoveryState
            Toast.makeText(this, "Network scan timed out.", Toast.LENGTH_SHORT).show();
            // Here you could trigger the HTTP Probe Fallback if desired
        }
    };

    private void toggleDiscovery() {
        Log.d(TAG, "toggleDiscovery: Current discoveryActive=" + nsdHelper.isDiscoveryActive());
        if (nsdHelper.isDiscoveryActive()) {
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable); // Cancel timeout
            nsdHelper.stopDiscovery();
        } else {
            discoveredServiceList.clear();
            discoveredServicesAdapter.clearServices();
            textViewDiscoveredServicesTitle.setVisibility(View.GONE);
            recyclerViewDiscoveredServices.setVisibility(View.GONE);
            Log.i(TAG, "toggleDiscovery: Starting discovery for Type='" + ESP_WEBSOCKET_SERVICE_TYPE + "', NameFilter='" + ESP_SERVICE_NAME_FILTER + "'");
            nsdHelper.discoverServices(ESP_SERVICE_NAME_FILTER, ESP_WEBSOCKET_SERVICE_TYPE);
            discoveryTimeoutHandler.postDelayed(discoveryTimeoutRunnable, NSD_DISCOVERY_TIMEOUT_MS);
        }
    }

    private void saveLog() {
        String fileName = "MrCoopersESP32_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        Log.d(TAG, "saveLog: Requesting to save log as " + fileName);
        createFileLauncher.launch(fileName);
        statusLog.append(getCurrentTimestamp()).append(" CMD_OUT: Log Save Requested to ").append(fileName).append("\n");
    }

    // --- UI Update Methods ---
    private void updateUIForInitialState() {
        Log.d(TAG, "updateUIForInitialState");
        textViewStatus.setText("Status: Idle. Enter address or scan.");
        String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
        buttonConnectManual.setEnabled(!TextUtils.isEmpty(currentAddress));
        buttonDisconnect.setEnabled(false);
        textInputLayoutEspAddress.setEnabled(true);
        editTextEspAddress.setEnabled(true);
        buttonStartStopDiscovery.setText("Scan Network for ESP32");
        buttonStartStopDiscovery.setEnabled(true);
        textViewLastMessage.setText("Last WS Msg: None");
    }

    private void updateUIForWsConnecting(String statusText) {
        Log.d(TAG, "updateUIForWsConnecting: statusText=" + statusText);
        textViewStatus.setText(statusText);
        buttonConnectManual.setEnabled(false);
        buttonDisconnect.setEnabled(false);
        textInputLayoutEspAddress.setEnabled(false);
        editTextEspAddress.setEnabled(false);
    }

    private void updateUIForWsConnected(String statusText) {
        Log.d(TAG, "updateUIForWsConnected: statusText=" + statusText);
        textViewStatus.setText(statusText);
        buttonConnectManual.setEnabled(false);
        buttonDisconnect.setEnabled(true);
        textInputLayoutEspAddress.setEnabled(false);
        editTextEspAddress.setEnabled(false);
        if (nsdHelper.isDiscoveryActive()) { // Stop scanning if we connected
            Log.d(TAG,"updateUIForWsConnected: WebSocket connected, stopping NSD discovery.");
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
            nsdHelper.stopDiscovery();
        }
    }

    private void updateUIForWsDisconnected(String statusText) {
        Log.d(TAG, "updateUIForWsDisconnected: statusText=" + statusText);
        textViewStatus.setText(statusText);
        String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
        buttonConnectManual.setEnabled(!TextUtils.isEmpty(currentAddress));
        buttonDisconnect.setEnabled(false);
        textInputLayoutEspAddress.setEnabled(true);
        editTextEspAddress.setEnabled(true);
        buttonStartStopDiscovery.setText("Scan Network for ESP32"); // Ensure scan button is reset
        buttonStartStopDiscovery.setEnabled(true);
    }

    private void updateUIDiscoveryState(boolean isDiscovering, String serviceType) {
        Log.d(TAG, "updateUIDiscoveryState: isDiscovering=" + isDiscovering + ", serviceType=" + serviceType);
        if (isDiscovering) {
            textViewStatus.setText("Status: Scanning for " + ESP_SERVICE_NAME_FILTER + " (" + serviceType.replaceFirst("\\.$", "") + ")...");
            buttonStartStopDiscovery.setText("Stop Scan");
        } else {
            // Avoid overwriting "Connecting..." or "Connected..." states
            String currentStatusLower = textViewStatus.getText().toString().toLowerCase();
            if (!isWebSocketServiceActive && !currentStatusLower.contains("connecting") && !currentStatusLower.contains("connected")) {
                 textViewStatus.setText("Status: Scan stopped. " + (discoveredServiceList.isEmpty() ? "No matching services found." : "Select from list or enter address."));
            }
            buttonStartStopDiscovery.setText("Scan Network for ESP32");
        }
        buttonStartStopDiscovery.setEnabled(true); // Always enable after start/stop
    }

    // --- NsdHelper.NsdHelperListener Implementation ---
    @Override
    public void onNsdServiceCandidateFound(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.i(TAG, "onNsdServiceCandidateFound: Name='" + serviceInfo.getServiceName() + "', Type='" + serviceInfo.getServiceType() + "'");
            statusLog.append(getCurrentTimestamp()).append(" NSD_Candidate: '").append(serviceInfo.getServiceName()).append("' Type: '").append(serviceInfo.getServiceType()).append("'\n");
            // You could add a temporary "Resolving..." item to the RecyclerView here
        });
    }

    @Override
    public void onNsdServiceResolved(DiscoveredService service) {
        runOnUiThread(() -> {
            Log.i(TAG, "onNsdServiceResolved: Name='" + service.getServiceName() + "', Host='" + service.getHostAddress() + ":" + service.getPort() + "', Type='" + service.getType() + "'");
            statusLog.append(getCurrentTimestamp()).append(" NSD_Resolved: '").append(service.getServiceName()).append("' at ").append(service.getHostAddress()).append(":").append(service.getPort()).append(" Type: '").append(service.getType()).append("'\n");

            // Filter for the specific WebSocket service type before adding to the list.
            // NsdHelper already filters by type and name, but an extra check here is fine.
            if (ESP_WEBSOCKET_SERVICE_TYPE.startsWith(service.getType().replaceFirst("\\.$", ""))) {
                Log.d(TAG, "onNsdServiceResolved: Adding to adapter: " + service.getServiceName());
                discoveredServicesAdapter.addService(service);
                if (recyclerViewDiscoveredServices.getVisibility() == View.GONE && discoveredServicesAdapter.getItemCount() > 0) {
                    textViewDiscoveredServicesTitle.setVisibility(View.VISIBLE);
                    recyclerViewDiscoveredServices.setVisibility(View.VISIBLE);
                }
            } else {
                 Log.d(TAG, "onNsdServiceResolved: Resolved service '" + service.getServiceName() + "' is not the expected WebSocket type ('" + service.getType() + "'). Not adding to UI list.");
            }
        });
    }

    @Override
    public void onNsdServiceLost(DiscoveredService service) {
        runOnUiThread(() -> {
            Log.w(TAG, "onNsdServiceLost: Name='" + service.getServiceName() + "', Type='" + service.getType() + "'");
            statusLog.append(getCurrentTimestamp()).append(" NSD_Lost: '").append(service.getServiceName()).append("' Type: '").append(service.getType()).append("'\n");

            List<DiscoveredService> currentServices = new ArrayList<>(discoveredServiceList);
            boolean removed = currentServices.remove(service);
            if(removed){
                Log.d(TAG, "onNsdServiceLost: Removed '" + service.getServiceName() + "' from local list.");
                discoveredServiceList = currentServices;
                discoveredServicesAdapter.setServices(discoveredServiceList);
            } else {
                 Log.d(TAG, "onNsdServiceLost: Service '" + service.getServiceName() + "' not found in local list to remove.");
            }

            if (discoveredServicesAdapter.getItemCount() == 0) {
                textViewDiscoveredServicesTitle.setVisibility(View.GONE);
                recyclerViewDiscoveredServices.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onNsdDiscoveryFailed(String serviceType, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "onNsdDiscoveryFailed: type=" + serviceType + ", errorCode=" + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD_Discovery_Failed: type='").append(serviceType).append("', ErrorCode=").append(errorCode).append("\n");
            Toast.makeText(this, "Network Discovery Failed (Code: " + errorCode + "). Check Wi-Fi.", Toast.LENGTH_LONG).show();
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable); // Cancel timeout
            updateUIDiscoveryState(false, serviceType);
        });
    }

    @Override
    public void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "onNsdResolveFailed: Service='" + serviceInfo.getServiceName() + "', errorCode=" + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD_Resolve_Failed: '").append(serviceInfo.getServiceName()).append("', ErrorCode=").append(errorCode).append("\n");
        });
    }

    @Override
    public void onNsdDiscoveryLifecycleChange(boolean active, String serviceType) {
        runOnUiThread(() -> {
            Log.i(TAG, "onNsdDiscoveryLifecycleChange: Active=" + active + ", serviceType=" + serviceType);
            statusLog.append(getCurrentTimestamp()).append(" NSD_Lifecycle: Discovery ").append(active ? "STARTED" : "STOPPED").append(" for '").append(serviceType).append("'\n");
            if (!active) { // If discovery stopped (manually or timeout)
                discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
            }
            updateUIDiscoveryState(active, serviceType);
        });
    }

    @Override
    public void onServiceClick(DiscoveredService service) {
        Log.i(TAG, "onServiceClick: Service='" + service.getServiceName() + "', Host='" + service.getHostAddress() + "', Port=" + service.getPort());
        if (service.isValid()) {
            editTextEspAddress.setText(service.getHostAddress() != null ? service.getHostAddress() : service.getServiceName()); // Prefer IP
            Toast.makeText(this, "'" +service.getServiceName() + "' (" + service.getHostAddress() + ") selected. Tap 'Connect'.", Toast.LENGTH_SHORT).show();
            statusLog.append(getCurrentTimestamp()).append(" UI_Action: Clicked discovered service '").append(service.getServiceName()).append("'\n");
            if (nsdHelper.isDiscoveryActive()) {
                Log.d(TAG, "onServiceClick: Stopping discovery after service selection.");
                discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
                nsdHelper.stopDiscovery();
            }
        } else {
            Log.w(TAG, "onServiceClick: Clicked service '" + service.getServiceName() + "' is not fully resolved.");
            Toast.makeText(this, service.getServiceName() + " is not fully resolved. Please wait or scan again.", Toast.LENGTH_SHORT).show();
        }
    }

    private String getCurrentTimestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date()); // Higher precision
    }

    private void saveLogToFile(Uri uri) {
        Log.d(TAG, "saveLogToFile: Attempting to write log to URI: " + uri);
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(Objects.requireNonNull(outputStream))) {
            writer.write("--- MrCooperESP32 App Log ---\n");
            writer.write("Timestamp Format: HH:mm:ss.SSS\n");
            writer.write("Log Start: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n\n");
            writer.write("--- Activity, Status & NSD Log ---\n");
            writer.write(statusLog.toString());
            writer.write("\n--- WebSocket Message Log ---\n");
            writer.write(messageLog.toString());
            writer.flush();
            Toast.makeText(this, "Log saved successfully!", Toast.LENGTH_LONG).show();
            Log.i(TAG, "saveLogToFile: Log saved successfully.");
        } catch (IOException e) {
            Log.e(TAG, "saveLogToFile: IOException: " + e.getMessage(), e);
            Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }  catch (NullPointerException e) {
            Log.e(TAG, "saveLogToFile: NullPointerException (likely from getContentResolver().openOutputStream(uri)): " + e.getMessage(), e);
            Toast.makeText(this, "Error preparing log file for saving (Null pointer).", Toast.LENGTH_LONG).show();
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "askNotificationPermission: Requesting POST_NOTIFICATIONS permission.");
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                Log.d(TAG, "askNotificationPermission: POST_NOTIFICATIONS permission already granted.");
            }
        }
    }

    private void registerServiceReceiver() {
        if (!isServiceReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WebSocketService.ACTION_STATUS_UPDATE);
            filter.addAction(WebSocketService.ACTION_MESSAGE_RECEIVED);
            LocalBroadcastManager.getInstance(this).registerReceiver(serviceUpdateReceiver, filter);
            isServiceReceiverRegistered = true;
            Log.d(TAG, "registerServiceReceiver: ServiceUpdateReceiver registered.");
        } else {
             Log.d(TAG, "registerServiceReceiver: ServiceUpdateReceiver already registered.");
        }
    }

    private void unregisterServiceReceiver() {
        if (isServiceReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
            isServiceReceiverRegistered = false;
            Log.d(TAG, "unregisterServiceReceiver: ServiceUpdateReceiver unregistered.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Activity Resumed. isWebSocketServiceActive=" + isWebSocketServiceActive);
        registerServiceReceiver();
        if (isWebSocketServiceActive) {
            updateUIForWsConnected("WS: Still connected (assumed from previous state)");
        } else {
            // Refresh UI to initial/disconnected state, ensuring connect button is correctly enabled/disabled
            updateUIForWsDisconnected(textViewStatus.getText().toString()); // Pass current status to avoid overwriting useful error
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Activity Paused.");
        // unregisterServiceReceiver(); // Consider unregistering here if you don't need background UI updates
                                   // But for service status, often good to keep registered while activity visible.
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop: Activity Stopped.");
        discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable); // Clean up timeout
        if (nsdHelper.isDiscoveryActive()) {
             Log.i(TAG, "onStop: Stopping NSD discovery as activity is no longer visible.");
             nsdHelper.stopDiscovery();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Activity Destroyed.");
        unregisterServiceReceiver(); // Definitely unregister here
        if (nsdHelper != null) { // Ensure nsdHelper itself isn't null
            nsdHelper.tearDown();
        }
        discoveryTimeoutHandler.removeCallbacksAndMessages(null); // Clean up any pending messages

        // To stop the service when the app is fully closed (MainActivity destroyed):
        // Log.d(TAG, "onDestroy: Requesting WebSocketService stop.");
        // Intent stopServiceIntent = new Intent(this, WebSocketService.class);
        // stopServiceIntent.setAction(WebSocketService.ACTION_STOP_FOREGROUND_SERVICE);
        // startService(stopServiceIntent);
    }
}