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
import android.net.nsd.NsdServiceInfo; // Keep for NsdHelperListener if it uses raw NsdServiceInfo for some callbacks
import android.os.Build;
import android.os.Bundle;
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

    private static final String TAG = "MainActivity";
    // mDNS Configuration Constants for WebSocket
    private static final String ESP_WEBSOCKET_SERVICE_TYPE = "_myespwebsocket._tcp";
    private static final String ESP_SERVICE_NAME_FILTER = "mrcoopersesp"; // Your ESP32's mDNS instance name
    private static final String ESP_WEBSOCKET_PATH = "/ws"; // Default path on your ESP
    private static final int ESP_DEFAULT_PORT = 80; // Default port for WS if not specified

    private TextInputLayout textInputLayoutEspAddress;
    private TextInputEditText editTextEspAddress;
    private Button buttonConnectManual, buttonDisconnect, buttonStartStopDiscovery, buttonSaveLog;
    private TextView textViewStatus, textViewLastMessage, textViewDiscoveredServicesTitle;
    private RecyclerView recyclerViewDiscoveredServices;
    private DiscoveredServicesAdapter discoveredServicesAdapter;
    private List<DiscoveredService> discoveredServiceList = new ArrayList<>();

    private NsdHelper nsdHelper;
    private StringBuilder statusLog = new StringBuilder(); // For saving to file
    private StringBuilder messageLog = new StringBuilder(); // For saving to file
    private ActivityResultLauncher<String> createFileLauncher;
    private boolean isWebSocketServiceActive = false;
    private boolean isServiceReceiverRegistered = false;
    private String lastAttemptedUrl = null;


    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
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

            if (WebSocketService.ACTION_STATUS_UPDATE.equals(action)) {
                statusMessage = intent.getStringExtra(WebSocketService.EXTRA_STATUS);
                if (statusMessage == null) statusMessage = "Unknown status";
                String logEntry = timestamp + " WS_Service_Status: " + statusMessage + "\n";
                statusLog.append(logEntry);
                Log.d(TAG, "BroadcastReceiver WS_Status: " + statusMessage);

                if (statusMessage.toLowerCase().startsWith("connected to")) {
                    isWebSocketServiceActive = true;
                    updateUIForWsConnected(statusMessage);
                } else if (statusMessage.toLowerCase().startsWith("connecting to")) {
                    isWebSocketServiceActive = false;
                    updateUIForWsConnecting(statusMessage);
                } else if (statusMessage.toLowerCase().startsWith("disconnected") ||
                        statusMessage.toLowerCase().startsWith("connection failed") ||
                        statusMessage.toLowerCase().startsWith("error:") ||
                        statusMessage.toLowerCase().equals("service stopped")) { // "service stopped" added
                    isWebSocketServiceActive = false;
                    updateUIForWsDisconnected(statusMessage);
                } else {
                    textViewStatus.setText("WS: " + statusMessage); // Generic update
                }

            } else if (WebSocketService.ACTION_MESSAGE_RECEIVED.equals(action)) {
                String title = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_TITLE);
                String body = intent.getStringExtra(WebSocketService.EXTRA_MESSAGE_BODY);
                String logEntry = timestamp + " WS_Message_Received: [" + title + "] " + body + "\n";
                messageLog.append(logEntry); // Log to separate message log
                statusLog.append(logEntry); // Also append to main status log for chronological order
                textViewLastMessage.setText(String.format(Locale.getDefault(),"Last WS Msg: [%s] %s", title, body));
                Log.d(TAG, "BroadcastReceiver WS_Message: " + title + " - " + body);
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
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
        nsdHelper = new NsdHelper(this, this);

        createFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                saveLogToFile(uri);
            } else {
                Toast.makeText(MainActivity.this, "Log saving cancelled.", Toast.LENGTH_SHORT).show();
            }
        });

        editTextEspAddress.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                buttonConnectManual.setEnabled(!TextUtils.isEmpty(s.toString().trim()) && !isWebSocketServiceActive);
            }
        });

        buttonConnectManual.setOnClickListener(v -> connectToEspFromInput());
        buttonDisconnect.setOnClickListener(v -> disconnectFromEsp());
        buttonStartStopDiscovery.setOnClickListener(v -> toggleDiscovery());
        buttonSaveLog.setOnClickListener(v -> saveLog());

        updateUIForInitialState(); // Set initial UI state
        registerServiceReceiver();
    }

    private void setupRecyclerView() {
        discoveredServicesAdapter = new DiscoveredServicesAdapter(this);
        recyclerViewDiscoveredServices.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewDiscoveredServices.setAdapter(discoveredServicesAdapter);
        // Initially hide the list and its title
        textViewDiscoveredServicesTitle.setVisibility(View.GONE);
        recyclerViewDiscoveredServices.setVisibility(View.GONE);
    }

    private void connectToEspFromInput() {
        String address = Objects.requireNonNull(editTextEspAddress.getText()).toString().trim();
        if (TextUtils.isEmpty(address)) {
            Toast.makeText(this, "Please enter ESP32 address or select from scan.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isWebSocketServiceActive) {
            Toast.makeText(this, "Already connected. Please disconnect first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String wsUrl;
        if (address.matches("wss?://.*")) {
            wsUrl = address; // Assume full URL including path if schema is present
        } else {
            String hostPart = address;
            int portPart = ESP_DEFAULT_PORT;
            if (address.contains(":")) {
                String[] parts = address.split(":", 2);
                hostPart = parts[0];
                try {
                    if (parts.length > 1 && !TextUtils.isEmpty(parts[1])) {
                        // Check if the port part also contains the path
                        if (parts[1].contains("/")) {
                            String[] portAndPath = parts[1].split("/", 2);
                            portPart = Integer.parseInt(portAndPath[0]);
                            // Path is already implicitly handled by ESP_WEBSOCKET_PATH,
                            // but this structure would be for ws://host:port/customPath
                            // For now, we assume ESP_WEBSOCKET_PATH is standard
                        } else {
                            portPart = Integer.parseInt(parts[1]);
                        }
                    }
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Invalid port in address: " + address, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            wsUrl = "ws://" + hostPart + ":" + portPart + ESP_WEBSOCKET_PATH;
        }
        lastAttemptedUrl = wsUrl;
        initiateWebSocketConnection(wsUrl);
    }

    private void initiateWebSocketConnection(String wsUrl) {
        statusLog.append(getCurrentTimestamp()).append(" CMD: Attempting WS connect to ").append(wsUrl).append("\n");
        Log.i(TAG, "Initiating WS connection to: " + wsUrl);

        Intent startFgIntent = new Intent(this, WebSocketService.class);
        startFgIntent.setAction(WebSocketService.ACTION_START_FOREGROUND_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startFgIntent);
        } else {
            startService(startFgIntent);
        }

        // Short delay to give service time to start if it wasn't running
        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent connectIntent = new Intent(this, WebSocketService.class);
            connectIntent.setAction(WebSocketService.ACTION_CONNECT);
            connectIntent.putExtra(WebSocketService.EXTRA_IP_ADDRESS, wsUrl);
            startService(connectIntent);
        }, 250); // 250ms delay

        updateUIForWsConnecting("Connecting to " + wsUrl.replaceFirst("ws://", "").replaceFirst(ESP_WEBSOCKET_PATH, ""));
    }

    private void disconnectFromEsp() {
        statusLog.append(getCurrentTimestamp()).append(" CMD: User requested WS Disconnect\n");
        Log.i(TAG, "Requesting WS disconnect.");
        Intent serviceIntent = new Intent(this, WebSocketService.class);
        serviceIntent.setAction(WebSocketService.ACTION_DISCONNECT);
        startService(serviceIntent);
    }

    private void toggleDiscovery() {
        if (nsdHelper.isDiscoveryActive()) {
            nsdHelper.stopDiscovery();
        } else {
            discoveredServiceList.clear();
            discoveredServicesAdapter.clearServices(); // Clear adapter data
            textViewDiscoveredServicesTitle.setVisibility(View.GONE);
            recyclerViewDiscoveredServices.setVisibility(View.GONE);
            nsdHelper.discoverServices(ESP_SERVICE_NAME_FILTER, ESP_WEBSOCKET_SERVICE_TYPE);
        }
    }

    private void saveLog() {
        String fileName = "MrCoopersESP32_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        createFileLauncher.launch(fileName);
        statusLog.append(getCurrentTimestamp()).append(" CMD: Log Save Requested to ").append(fileName).append("\n");
    }

    // --- UI Update Methods ---
    private void updateUIForInitialState() {
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
        textViewStatus.setText(statusText); // Status text is already prefixed by service "Connecting to..."
        buttonConnectManual.setEnabled(false);
        buttonDisconnect.setEnabled(false);
        textInputLayoutEspAddress.setEnabled(false);
        editTextEspAddress.setEnabled(false);
        // Keep discovery button as is, or disable:
        // buttonStartStopDiscovery.setEnabled(false);
    }

    private void updateUIForWsConnected(String statusText) {
        textViewStatus.setText(statusText); // Status text is already prefixed by service "Connected to..."
        buttonConnectManual.setEnabled(false);
        buttonDisconnect.setEnabled(true);
        textInputLayoutEspAddress.setEnabled(false);
        editTextEspAddress.setEnabled(false);
        // If discovery was running, it might be good to stop it now, or let user stop it.
        // buttonStartStopDiscovery.setEnabled(!nsdHelper.isDiscoveryActive());
    }

    private void updateUIForWsDisconnected(String statusText) {
        textViewStatus.setText(statusText); // Status text from service "Disconnected..." or "Connection Failed..."
        String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
        buttonConnectManual.setEnabled(!TextUtils.isEmpty(currentAddress));
        buttonDisconnect.setEnabled(false);
        textInputLayoutEspAddress.setEnabled(true);
        editTextEspAddress.setEnabled(true);
        buttonStartStopDiscovery.setText("Scan Network for ESP32");
        buttonStartStopDiscovery.setEnabled(true);
    }

    private void updateUIDiscoveryState(boolean isDiscovering, String serviceType) {
        if (isDiscovering) {
            textViewStatus.setText("Status: Scanning for " + ESP_SERVICE_NAME_FILTER + " (" + serviceType + ")...");
            buttonStartStopDiscovery.setText("Stop Scan");
            buttonStartStopDiscovery.setEnabled(true);
        } else {
            // textViewStatus might be updated by connection status, so only update if not connected/connecting
            if (!isWebSocketServiceActive && (textViewStatus.getText().toString().toLowerCase().contains("scanning") || textViewStatus.getText().toString().toLowerCase().contains("idle"))) {
                 textViewStatus.setText("Status: Scan stopped. " + (discoveredServiceList.isEmpty() ? "No matching services found." : "Select from list or enter address."));
            }
            buttonStartStopDiscovery.setText("Scan Network for ESP32");
            buttonStartStopDiscovery.setEnabled(true);
        }
    }

    // --- NsdHelper.NsdHelperListener Implementation ---
    @Override
    public void onNsdServiceCandidateFound(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Candidate (raw): " + serviceInfo.getServiceName() + " Type: " + serviceInfo.getServiceType());
            // We can add a temporary "Resolving..." item to the RecyclerView here if desired
            // DiscoveredService tempService = new DiscoveredService(serviceInfo); // Uses constructor that sets hostAddress to null initially
            // discoveredServicesAdapter.addService(tempService); // Adapter handles duplicates or updates
            // if (recyclerViewDiscoveredServices.getVisibility() == View.GONE) {
            //     textViewDiscoveredServicesTitle.setVisibility(View.VISIBLE);
            //     recyclerViewDiscoveredServices.setVisibility(View.VISIBLE);
            // }
        });
    }

    @Override
    public void onNsdServiceResolved(DiscoveredService service) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Resolved: " + service.getServiceName() + " at " + service.getHostAddress() + ":" + service.getPort());
            statusLog.append(getCurrentTimestamp()).append(" NSD: Resolved '").append(service.getServiceName()).append("' at ").append(service.getHostAddress()).append(":").append(service.getPort()).append(" Type: ").append(service.getType()).append("\n");

            if (ESP_WEBSOCKET_SERVICE_TYPE.startsWith(service.getType().replaceFirst("\\.$", ""))) { // Check if it's our WS type
                discoveredServicesAdapter.addService(service); // Adapter handles duplicates
                if (recyclerViewDiscoveredServices.getVisibility() == View.GONE && discoveredServicesAdapter.getItemCount() > 0) {
                    textViewDiscoveredServicesTitle.setVisibility(View.VISIBLE);
                    recyclerViewDiscoveredServices.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    @Override
    public void onNsdServiceLost(DiscoveredService service) {
        runOnUiThread(() -> {
            Log.w(TAG, "NSD Lost: " + service.getServiceName());
            statusLog.append(getCurrentTimestamp()).append(" NSD: Lost '").append(service.getServiceName()).append("' Type: ").append(service.getType()).append("\n");

            // Create a list to hold current items, remove the lost one, then update adapter
            List<DiscoveredService> currentServices = new ArrayList<>(discoveredServiceList); // Assuming discoveredServiceList is the source for the adapter
            boolean removed = currentServices.remove(service); // Use DiscoveredService.equals()
            if(removed){
                discoveredServiceList = currentServices; // Update the main list
                discoveredServicesAdapter.setServices(discoveredServiceList); // Update adapter with the new list
            }

            if (discoveredServicesAdapter.getItemCount() == 0) {
                textViewDiscoveredServicesTitle.setVisibility(View.GONE);
                recyclerViewDiscoveredServices.setVisibility(View.GONE);
            }

            if (isWebSocketServiceActive && lastAttemptedUrl != null &&
                    ((service.getHostAddress() != null && lastAttemptedUrl.contains(service.getHostAddress())) ||
                            lastAttemptedUrl.contains(service.getServiceName()))) {
                Toast.makeText(this, service.getServiceName() + " connection may be lost.", Toast.LENGTH_LONG).show();
                // The WebSocketService itself will handle actual connection loss and broadcast updates.
                // MainActivity just notes the mDNS service is gone.
            }
        });
    }

    @Override
    public void onNsdDiscoveryFailed(String serviceType, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Discovery Failed for '" + serviceType + "'. Error: " + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD: Discovery Failed ('").append(serviceType).append("'). Error: ").append(errorCode).append("\n");
            Toast.makeText(this, "Network Discovery Failed. Check Wi-Fi/Permissions.", Toast.LENGTH_LONG).show();
            updateUIDiscoveryState(false, serviceType);
        });
    }

    @Override
    public void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
        runOnUiThread(() -> {
            Log.e(TAG, "NSD Resolve Failed for '" + serviceInfo.getServiceName() + "'. Error: " + errorCode);
            statusLog.append(getCurrentTimestamp()).append(" NSD: Resolve Failed for '").append(serviceInfo.getServiceName()).append("'. Error: ").append(errorCode).append("\n");
            // Optionally remove from RecyclerView if it was added as a "resolving" item
            // DiscoveredService tempService = new DiscoveredService(serviceInfo);
            // List<DiscoveredService> currentServices = new ArrayList<>(discoveredServiceList);
            // if(currentServices.remove(tempService)) {
            //     discoveredServiceList = currentServices;
            //     discoveredServicesAdapter.setServices(discoveredServiceList);
            // }
        });
    }

    @Override
    public void onNsdDiscoveryLifecycleChange(boolean active, String serviceType) {
        runOnUiThread(() -> {
            Log.i(TAG, "NSD Discovery Lifecycle: " + (active ? "STARTED" : "STOPPED") + " for " + serviceType);
            statusLog.append(getCurrentTimestamp()).append(" NSD: Discovery ").append(active ? "STARTED" : "STOPPED").append(" for '").append(serviceType).append("'\n");
            updateUIDiscoveryState(active, serviceType);
        });
    }

    // --- DiscoveredServicesAdapter.OnServiceClickListener ---
    @Override
    public void onServiceClick(DiscoveredService service) {
        if (service.isValid()) {
            Log.d(TAG, "Service clicked: " + service.getServiceName() + " at " + service.getHostAddress() + ":" + service.getPort());
            // Use IP address for direct connection attempt. Hostname.local might be better for browsers.
            editTextEspAddress.setText(service.getHostAddress() != null ? service.getHostAddress() : service.getServiceName());
            Toast.makeText(this, "'" +service.getServiceName() + "' address (" + service.getHostAddress() + ") copied. Tap 'Connect'.", Toast.LENGTH_SHORT).show();
            if (nsdHelper.isDiscoveryActive()) { // Stop discovery once a service is selected
                nsdHelper.stopDiscovery();
            }
        } else {
            Toast.makeText(this, service.getServiceName() + " is not fully resolved. Please wait.", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Helper Methods & Lifecycle ---
    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void saveLogToFile(Uri uri) {
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(Objects.requireNonNull(outputStream))) { // Added Objects.requireNonNull
            writer.write("--- Activity, Status & NSD Log ---\n");
            writer.write(statusLog.toString());
            writer.write("\n--- WebSocket Message Log ---\n");
            writer.write(messageLog.toString());
            writer.flush();
            Toast.makeText(this, "Log saved successfully to " + uri.getPath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "Error saving log to file", e);
            Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }  catch (NullPointerException e) {
            Log.e(TAG, "Error getting output stream for URI: " + uri.toString(), e);
            Toast.makeText(this, "Error preparing log file for saving.", Toast.LENGTH_LONG).show();
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.");
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
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
            Log.d(TAG, "ServiceUpdateReceiver registered.");
        }
    }

    private void unregisterServiceReceiver() {
        if (isServiceReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
            isServiceReceiverRegistered = false;
            Log.d(TAG, "ServiceUpdateReceiver unregistered.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        registerServiceReceiver();
        // Optionally, refresh UI state based on WebSocketService's current state if it was running
        // This is tricky as service state isn't directly queried. Broadcasts are preferred.
        // For now, UI updates based on its own `isWebSocketServiceActive` flag which is set by broadcasts.
        if (isWebSocketServiceActive) {
            updateUIForWsConnected("WS: Still connected (assumed)"); // Or fetch last known status
        } else {
             String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
             buttonConnectManual.setEnabled(!TextUtils.isEmpty(currentAddress));
             buttonDisconnect.setEnabled(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        // Consider if discovery should be stopped when app is paused to save battery
        // if (nsdHelper.isDiscoveryActive()) {
        //     nsdHelper.stopDiscovery();
        // }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
        // If discovery is battery intensive, definitely stop it here.
        if (nsdHelper.isDiscoveryActive()) {
             Log.d(TAG, "Stopping NSD discovery in onStop.");
             nsdHelper.stopDiscovery();
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        unregisterServiceReceiver();
        nsdHelper.tearDown(); // Stop discovery and release NsdManager resources

        // Decide if service should be stopped when MainActivity is destroyed
        // If you want the service to continue running (e.g. receiving notifications)
        // then do not stop it here unless it's explicitly desired (e.g. by user action).
        // If the service is only for when app is active, then stop it:
        // Intent stopServiceIntent = new Intent(this, WebSocketService.class);
        // stopServiceIntent.setAction(WebSocketService.ACTION_STOP_FOREGROUND_SERVICE);
        // startService(stopServiceIntent);
        // Log.d(TAG, "Requested WebSocketService stop in onDestroy.");

        super.onDestroy();
    }
}