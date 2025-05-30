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
import android.os.Handler;
import android.os.Looper;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap; // For app_config
import java.util.List;
import java.util.Locale;
import java.util.Map; // For app_config
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements NsdHelper.NsdHelperListener, DiscoveredServicesAdapter.OnServiceClickListener {

    private static final String TAG = "MainActivity_DEBUG";
    private static final String ESP_HTTP_SERVICE_TYPE = "_http._tcp"; // For mDNS discovery of HTTP service
    private static final String ESP_SERVICE_NAME_FILTER = "mrcoopersesp"; // Your ESP's mDNS name
    private static final int ESP_DEFAULT_HTTP_PORT = 80;
    private static final long NSD_DISCOVERY_TIMEOUT_MS = 15000;

    private TextInputLayout textInputLayoutEspAddress;
    private TextInputEditText editTextEspAddress;
    private Button buttonStartStopPolling, buttonStopService, buttonStartStopDiscovery, buttonSaveLog;
    private TextView textViewStatus, textViewLastMessage, textViewDiscoveredServicesTitle;
    private RecyclerView recyclerViewDiscoveredServices;
    private DiscoveredServicesAdapter discoveredServicesAdapter;
    private List<DiscoveredService> discoveredServiceList = new ArrayList<>(); // Keep this for NSD

    private NsdHelper nsdHelper;
    private StringBuilder statusLog = new StringBuilder();
    private StringBuilder messageLog = new StringBuilder(); // Can be repurposed for HTTP data log
    private ActivityResultLauncher<String> createFileLauncher;

    private boolean isServiceReceiverRegistered = false;
    private Handler discoveryTimeoutHandler = new Handler(Looper.getMainLooper());

    // App config, potentially loaded or set via UI in a more complex app
    // For now, a simple map to hold a trigger distance for demonstration.
    // Your ESP32's `app_config` is on the device itself. This is an app-side equivalent if needed.
    private Map<String, Double> app_config = new HashMap<>();


    // Flag to track if HttpPollingService is actively polling
    private boolean isHttpServicePolling = false;


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
            Log.d(TAG, "serviceUpdateReceiver: Received action: " + action);

            if (HttpPollingService.ACTION_STATUS_UPDATE.equals(action)) {
                String statusMessage = intent.getStringExtra(HttpPollingService.EXTRA_STATUS);
                if (statusMessage == null) statusMessage = "Unknown status from service";
                String logEntry = timestamp + " HTTP_Service_Status_RCV: " + statusMessage + "\n";
                statusLog.append(logEntry);
                Log.i(TAG, "serviceUpdateReceiver << HTTP_Status: " + statusMessage);

                // Update polling state based on messages from the service
                if (statusMessage.toLowerCase().contains("polling started")) {
                    isHttpServicePolling = true;
                } else if (statusMessage.toLowerCase().contains("polling stopped") ||
                           statusMessage.toLowerCase().contains("service stopped")) {
                    isHttpServicePolling = false;
                }
                updateUIForHttpPollingState(isHttpServicePolling, statusMessage);


            } else if (HttpPollingService.ACTION_DATA_RECEIVED.equals(action)) {
                String dataType = intent.getStringExtra(HttpPollingService.EXTRA_DATA_TYPE);
                String jsonData = intent.getStringExtra(HttpPollingService.EXTRA_DATA_JSON_STRING);
                String logEntry = timestamp + " HTTP_Data_RCV ("+dataType+"): " +
                                  (jsonData != null ? jsonData.substring(0, Math.min(jsonData.length(), 100)) + "..." : "null") + "\n";
                messageLog.append(logEntry);
                statusLog.append(logEntry);

                Log.i(TAG, "serviceUpdateReceiver << HTTP_Data (" + dataType + "): " + jsonData);

                if ("distance".equals(dataType) && jsonData != null) {
                    try {
                        JSONObject json = new JSONObject(jsonData);
                        double distanceVal = json.optDouble("distance_cm", -3.0); // Default if key missing
                        String distDisplay;
                        if (distanceVal == -1.0) distDisplay = "Error Reading";
                        else if (distanceVal == -2.0) distDisplay = "Sensor Disabled (ESP)";
                        else if (distanceVal == -3.0) distDisplay = "Invalid JSON from ESP";
                        else distDisplay = String.format(Locale.getDefault(), "%.2f cm", distanceVal);
                        textViewLastMessage.setText("Last Dist: " + distDisplay);

                        double triggerDistance = app_config.getOrDefault("trigger_distance_cm", 5.0);
                        if (distanceVal >= 0 && distanceVal < triggerDistance) {
                            Log.d(TAG, "Potential motion detected via HTTP poll: " + distDisplay);
                            // You could show a system notification here using HttpPollingService.showDataNotification
                            // but it's better if the service itself decides based on rules.
                            // For now, just log it.
                        }

                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing distance JSON in MainActivity: " + e.getMessage());
                        textViewLastMessage.setText("Last Dist: JSON Parse Err");
                    }
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Activity Creating");
        setContentView(R.layout.activity_main);

        // Initialize app_config with a default (could be loaded from SharedPreferences)
        app_config.put("trigger_distance_cm", 5.0);

        textInputLayoutEspAddress = findViewById(R.id.textInputLayoutEspAddress);
        editTextEspAddress = findViewById(R.id.editTextEspAddress);
        buttonStartStopPolling = findViewById(R.id.buttonConnectManual); // Repurposed this button
        buttonStopService = findViewById(R.id.buttonDisconnect);    // Repurposed this button
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
                buttonStartStopPolling.setEnabled(inputPresent);
            }
        });

        buttonStartStopPolling.setOnClickListener(v -> {
            Log.d(TAG, "buttonStartStopPolling (Service): Clicked");
            String address = Objects.requireNonNull(editTextEspAddress.getText()).toString().trim();
            if (TextUtils.isEmpty(address)) {
                Toast.makeText(this, "Please enter ESP32 address.", Toast.LENGTH_SHORT).show();
                return;
            }

            String baseUrl = address;
            if (!baseUrl.matches("^[a-zA-Z]+://.*")) {
                baseUrl = "http://" + baseUrl;
            }
            try {
                java.net.URL tempUrl = new java.net.URL(baseUrl);
                if (tempUrl.getPort() == -1 && !tempUrl.getHost().endsWith(".local")) {
                     // If no port and not .local, assume default HTTP port 80 is intended
                    // The ESP code serves on 80. Let service handle this if necessary or assume 80.
                    // For clarity, we can append it if it's an IP or other hostname.
                    // If it's "mrcooperesp.local", http scheme already implies port 80.
                }
                 // If tempUrl.getHost().endsWith(".local") or port is specified, use as is.
                 // If it's an IP without port, HttpPollingService might need to assume 80 or we add it here.
                 // For now, let's assume user includes port if not 80, or it's a .local name.
                 // HttpPollingService's getHostFromUrl will strip path, so full "http://host:port" is fine.

            } catch (java.net.MalformedURLException e) {
                Toast.makeText(this, "Invalid address format: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }

            if (!isHttpServicePolling) {
                Intent startServiceIntent = new Intent(this, HttpPollingService.class);
                startServiceIntent.setAction(HttpPollingService.ACTION_START_FOREGROUND_SERVICE);
                startServiceIntent.putExtra(HttpPollingService.EXTRA_BASE_URL, baseUrl);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startServiceIntent);
                } else {
                    startService(startServiceIntent);
                }
            } else {
                Intent stopPollingIntent = new Intent(this, HttpPollingService.class);
                stopPollingIntent.setAction(HttpPollingService.ACTION_STOP_POLLING);
                startService(stopPollingIntent); // Service remains foreground, just stops polling
            }
        });

        buttonStopService.setOnClickListener(v -> {
            Log.d(TAG, "buttonStopService Clicked");
            Intent stopServiceIntent = new Intent(this, HttpPollingService.class);
            stopServiceIntent.setAction(HttpPollingService.ACTION_STOP_FOREGROUND_SERVICE);
            startService(stopServiceIntent);
            // isHttpServicePolling will be set to false via broadcast from service
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

    private Runnable discoveryTimeoutRunnable = () -> {
        Log.w(TAG, "mDNS Discovery timed out (" + NSD_DISCOVERY_TIMEOUT_MS + "ms)");
        if (nsdHelper.isDiscoveryActive()) {
            Log.d(TAG, "Discovery timeout: Stopping active discovery.");
            nsdHelper.stopDiscovery();
            Toast.makeText(this, "Network scan timed out.", Toast.LENGTH_SHORT).show();
        }
    };

    private void toggleDiscovery() {
        Log.d(TAG, "toggleDiscovery: Current discoveryActive=" + nsdHelper.isDiscoveryActive());
        if (nsdHelper.isDiscoveryActive()) {
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
            nsdHelper.stopDiscovery();
        } else {
            // discoveredServiceList.clear(); // NsdHelper usually manages this internally, but explicit clear for UI is fine
            discoveredServicesAdapter.clearServices();
            textViewDiscoveredServicesTitle.setVisibility(View.GONE);
            recyclerViewDiscoveredServices.setVisibility(View.GONE);
            Log.i(TAG, "toggleDiscovery: Starting discovery for Type='" + ESP_HTTP_SERVICE_TYPE + "', NameFilter='" + ESP_SERVICE_NAME_FILTER + "'");
            // Discover HTTP service now
            nsdHelper.discoverServices(ESP_SERVICE_NAME_FILTER, ESP_HTTP_SERVICE_TYPE);
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
        textViewLastMessage.setText("Last Dist: None");
        String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
        buttonStartStopPolling.setEnabled(!TextUtils.isEmpty(currentAddress));
        buttonStartStopPolling.setText("Start Polling (Service)");
        buttonStopService.setText("Stop Service");
        buttonStopService.setEnabled(false); // Enable when service is known to be running/polling
        textInputLayoutEspAddress.setEnabled(true);
        editTextEspAddress.setEnabled(true);
        buttonStartStopDiscovery.setText("Scan Network for ESP32");
        buttonStartStopDiscovery.setEnabled(true);
    }

    private void updateUIForHttpPollingState(boolean isPolling, String statusTextFromService) {
        Log.d(TAG, "updateUIForHttpPollingState: isPolling=" + isPolling + ", statusText=" + statusTextFromService);
        textViewStatus.setText(statusTextFromService); // Show the detailed status from service
        String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
        boolean inputPresent = !TextUtils.isEmpty(currentAddress);

        buttonStartStopPolling.setEnabled(inputPresent);
        if (isPolling) {
            buttonStartStopPolling.setText("Stop Polling (Service)");
            buttonStopService.setEnabled(true); // Can stop the service if it's polling
        } else {
            buttonStartStopPolling.setText("Start Polling (Service)");
            // Only enable stop service if we are sure service is running but just not polling
            // This logic might need refinement based on how "service stopped" event is handled
            // For now, if not polling, assume service might be idle or stopped, so disable stop button unless we know it's active.
             buttonStopService.setEnabled(isServiceReceiverRegistered && !statusTextFromService.toLowerCase().contains("service stopped"));
        }
        textInputLayoutEspAddress.setEnabled(!isPolling); // Disable address changes while polling
        editTextEspAddress.setEnabled(!isPolling);

        // If polling starts and discovery is active, stop discovery
        if (isPolling && nsdHelper.isDiscoveryActive()) {
            Log.d(TAG,"updateUIForHttpPollingState: HTTP polling started, stopping NSD discovery.");
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
            nsdHelper.stopDiscovery();
        }
    }

    private void updateUIDiscoveryState(boolean isDiscovering, String serviceType) {
        Log.d(TAG, "updateUIDiscoveryState: isDiscovering=" + isDiscovering + ", serviceType=" + serviceType);
        if (isDiscovering) {
            textViewStatus.setText("Status: Scanning for " + ESP_SERVICE_NAME_FILTER + " (" + serviceType.replaceFirst("\\.$", "") + ")...");
            buttonStartStopDiscovery.setText("Stop Scan");
        } else {
            String currentStatusLower = textViewStatus.getText().toString().toLowerCase();
            if (!isHttpServicePolling && !currentStatusLower.contains("polling")) { // Avoid overwriting polling status
                 textViewStatus.setText("Status: Scan stopped. " + (discoveredServicesAdapter.getItemCount() == 0 ? "No matching services found." : "Select from list or enter address."));
            }
            buttonStartStopDiscovery.setText("Scan Network for ESP32");
        }
        buttonStartStopDiscovery.setEnabled(true);
    }

    // --- NsdHelper.NsdHelperListener Implementation ---
    @Override
    public void onNsdServiceCandidateFound(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            Log.i(TAG, "onNsdServiceCandidateFound: Name='" + serviceInfo.getServiceName() + "', Type='" + serviceInfo.getServiceType() + "'");
            statusLog.append(getCurrentTimestamp()).append(" NSD_Candidate: '").append(serviceInfo.getServiceName()).append("' Type: '").append(serviceInfo.getServiceType()).append("'\n");
        });
    }

    @Override
    public void onNsdServiceResolved(DiscoveredService service) {
        runOnUiThread(() -> {
            Log.i(TAG, "onNsdServiceResolved: Name='" + service.getServiceName() + "', Host='" + service.getHostAddress() + ":" + service.getPort() + "', Type='" + service.getType() + "'");
            statusLog.append(getCurrentTimestamp()).append(" NSD_Resolved: '").append(service.getServiceName()).append("' at ").append(service.getHostAddress()).append(":").append(service.getPort()).append(" Type: '").append(service.getType()).append("'\n");

            // Filter for the HTTP service type
            if (ESP_HTTP_SERVICE_TYPE.startsWith(service.getType().replaceFirst("\\.$", ""))) {
                Log.d(TAG, "onNsdServiceResolved: Adding HTTP service to adapter: " + service.getServiceName());
                discoveredServicesAdapter.addService(service); // Using the adapter's addService
                if (recyclerViewDiscoveredServices.getVisibility() == View.GONE && discoveredServicesAdapter.getItemCount() > 0) {
                    textViewDiscoveredServicesTitle.setVisibility(View.VISIBLE);
                    recyclerViewDiscoveredServices.setVisibility(View.VISIBLE);
                }
            } else {
                 Log.d(TAG, "onNsdServiceResolved: Resolved service '" + service.getServiceName() + "' is not the expected HTTP type ('" + service.getType() + "'). Not adding.");
            }
        });
    }

    @Override
    public void onNsdServiceLost(DiscoveredService service) {
        runOnUiThread(() -> {
            Log.w(TAG, "onNsdServiceLost: Name='" + service.getServiceName() + "', Type='" + service.getType() + "'");
            statusLog.append(getCurrentTimestamp()).append(" NSD_Lost: '").append(service.getServiceName()).append("' Type: '").append(service.getType()).append("'\n");
            // Let DiscoveredServicesAdapter handle removal if it has such a method, or manage list here
            // For simplicity, we re-set the adapter's list. A more efficient way is to remove the specific item.
            List<DiscoveredService> currentServices = new ArrayList<>();
            for (int i = 0; i < discoveredServicesAdapter.getItemCount(); i++) {
                 // This needs a way to get item from adapter or manage a parallel list
                 // discoveredServicesAdapter.getServices().remove(service) if adapter has getServices()
            }
            // If using a local list:
            // discoveredServiceList.remove(service);
            // discoveredServicesAdapter.setServices(discoveredServiceList);
            // This part needs to be correctly implemented based on how you manage the adapter's data source
            // A simple clear and re-add or a dedicated remove method in adapter is better.
            // For now, let's assume the adapter can handle this or we clear and re-add if needed.
            // If the adapter's internal list is `discoveredServices`, then:
            // discoveredServicesAdapter.removeService(service); // IF YOU ADD SUCH A METHOD
            // If not, you might need to rebuild the list.
            // For this example, let's log and be aware this UI part of NSD lost might be incomplete.
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
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
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
            if (!active) {
                discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
            }
            updateUIDiscoveryState(active, serviceType);
        });
    }

    @Override
    public void onServiceClick(DiscoveredService service) {
        Log.i(TAG, "onServiceClick: Service='" + service.getServiceName() + "', Host='" + service.getHostAddress() + "', Port=" + service.getPort());
        if (service.isValid()) {
            // For HTTP, we just need the host. Port 80 is usually implied by http:// or handled by service.
            // If NSD resolves a different port for _http, use it.
            String addressToUse = service.getHostAddress();
            if (service.getPort() != ESP_DEFAULT_HTTP_PORT && service.getPort() > 0) {
                // addressToUse += ":" + service.getPort(); // Let HttpPollingService handle base URL construction
            }
            editTextEspAddress.setText(addressToUse); // Set the IP/hostname
            Toast.makeText(this, "'" +service.getServiceName() + "' (" + service.getHostAddress() + ") selected. Tap 'Start Polling'.", Toast.LENGTH_SHORT).show();
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
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private void saveLogToFile(Uri uri) {
        Log.d(TAG, "saveLogToFile: Attempting to write log to URI: " + uri);
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(Objects.requireNonNull(outputStream))) {
            writer.write("--- MrCooperESP32 App Log (HTTP Mode) ---\n");
            writer.write("Timestamp Format: HH:mm:ss.SSS\n");
            writer.write("Log Start: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n\n");
            writer.write("--- Activity, Status & NSD Log ---\n");
            writer.write(statusLog.toString());
            writer.write("\n--- HTTP Data Log ---\n"); // Changed from WebSocket Message Log
            writer.write(messageLog.toString());
            writer.flush();
            Toast.makeText(this, "Log saved successfully!", Toast.LENGTH_LONG).show();
            Log.i(TAG, "saveLogToFile: Log saved successfully.");
        } catch (IOException | NullPointerException e) {
            Log.e(TAG, "saveLogToFile: Error: " + e.getMessage(), e);
            Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
            filter.addAction(HttpPollingService.ACTION_STATUS_UPDATE);
            filter.addAction(HttpPollingService.ACTION_DATA_RECEIVED);
            LocalBroadcastManager.getInstance(this).registerReceiver(serviceUpdateReceiver, filter);
            isServiceReceiverRegistered = true;
            Log.d(TAG, "registerServiceReceiver: ServiceUpdateReceiver registered for HttpPollingService.");
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
        Log.d(TAG, "onResume: Activity Resumed. isHttpServicePolling=" + isHttpServicePolling);
        registerServiceReceiver();
        // Update UI based on the current polling state
        // This might need a more robust way to check actual service state if activity was destroyed
        updateUIForHttpPollingState(isHttpServicePolling, textViewStatus.getText().toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Activity Paused.");
        // Consider if you want to unregister receiver here. For background updates, keep it.
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop: Activity Stopped.");
        discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable);
        if (nsdHelper.isDiscoveryActive()) {
             Log.i(TAG, "onStop: Stopping NSD discovery as activity is no longer visible.");
             nsdHelper.stopDiscovery();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Activity Destroyed.");
        unregisterServiceReceiver();
        if (nsdHelper != null) {
            nsdHelper.tearDown();
        }
        discoveryTimeoutHandler.removeCallbacksAndMessages(null);

        // Optional: Stop the service if the main activity is destroyed
        // Intent stopServiceIntent = new Intent(this, HttpPollingService.class);
        // stopServiceIntent.setAction(HttpPollingService.ACTION_STOP_FOREGROUND_SERVICE);
        // startService(stopServiceIntent);
        // Log.d(TAG, "onDestroy: Requested HttpPollingService stop.");
    }
}