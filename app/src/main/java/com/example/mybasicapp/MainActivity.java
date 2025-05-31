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
import android.content.SharedPreferences;
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
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;
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
// import java.util.HashMap; // No longer using app_config map for these settings
import java.util.List;
import java.util.Locale;
// import java.util.Map; // No longer using app_config map
import java.util.Objects;

public class MainActivity extends AppCompatActivity implements NsdHelper.NsdHelperListener, DiscoveredServicesAdapter.OnServiceClickListener {

    private static final String TAG = "MainActivity_DEBUG";
    private static final String ESP_HTTP_SERVICE_TYPE = "_http._tcp";
    private static final String ESP_SERVICE_NAME_FILTER = "mrcoopersesp";
    private static final int ESP_DEFAULT_HTTP_PORT = 80;
    private static final long NSD_DISCOVERY_TIMEOUT_MS = 15000;

    // SharedPreferences keys
    private static final String PREFS_NAME = "MrCooperESP_Prefs";
    private static final String PREF_TRIGGER_DISTANCE = "trigger_distance_cm";
    private static final String PREF_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final int DEFAULT_TRIGGER_DISTANCE = 50; // cm
    private static final boolean DEFAULT_NOTIFICATIONS_ENABLED = false;


    private TextInputLayout textInputLayoutEspAddress;
    private TextInputEditText editTextEspAddress;
    private Button buttonStartStopPolling, buttonStopService, buttonStartStopDiscovery, buttonSaveLog;
    private TextView textViewStatus, textViewLastMessage, textViewDiscoveredServicesTitle;
    private RecyclerView recyclerViewDiscoveredServices;
    private DiscoveredServicesAdapter discoveredServicesAdapter;
    private List<DiscoveredService> discoveredServiceList = new ArrayList<>();

    private NsdHelper nsdHelper;
    private StringBuilder statusLog = new StringBuilder();
    private StringBuilder messageLog = new StringBuilder();
    private ActivityResultLauncher<String> createFileLauncher;

    private boolean isServiceReceiverRegistered = false;
    private Handler discoveryTimeoutHandler = new Handler(Looper.getMainLooper());

    private boolean isHttpServicePolling = false;

    // New UI Elements
    private SeekBar seekBarTriggerDistance;
    private TextView textViewTriggerDistanceValue;
    private SwitchMaterial switchEnableNotifications;

    private SharedPreferences sharedPreferences;


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
                        double distanceVal = json.optDouble("distance_cm", -3.0);
                        String distDisplay;
                        if (distanceVal == -1.0) distDisplay = "Error Reading";
                        else if (distanceVal == -2.0) distDisplay = "Sensor Disabled (ESP)";
                        else if (distanceVal == -3.0) distDisplay = "Invalid JSON from ESP";
                        else distDisplay = String.format(Locale.getDefault(), "%.2f cm", distanceVal);
                        textViewLastMessage.setText("Last Dist: " + distDisplay);

                        // Notification logic is now primarily in HttpPollingService
                        // It will use SharedPreferences for trigger distance and enabled status

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

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        textInputLayoutEspAddress = findViewById(R.id.textInputLayoutEspAddress);
        editTextEspAddress = findViewById(R.id.editTextEspAddress);
        buttonStartStopPolling = findViewById(R.id.buttonConnectManual);
        buttonStopService = findViewById(R.id.buttonDisconnect);
        buttonStartStopDiscovery = findViewById(R.id.buttonStartStopDiscovery);
        buttonSaveLog = findViewById(R.id.buttonSaveLog);
        textViewStatus = findViewById(R.id.textViewStatus);
        textViewLastMessage = findViewById(R.id.textViewLastMessage);
        textViewDiscoveredServicesTitle = findViewById(R.id.textViewDiscoveredServicesTitle);
        recyclerViewDiscoveredServices = findViewById(R.id.recyclerViewDiscoveredServices);

        // New UI Elements
        seekBarTriggerDistance = findViewById(R.id.seekBarTriggerDistance);
        textViewTriggerDistanceValue = findViewById(R.id.textViewTriggerDistanceValue);
        switchEnableNotifications = findViewById(R.id.switchEnableNotifications);

        // Auto-fill ESP address
        editTextEspAddress.setText("mrcoopersesp.local");


        setupRecyclerView();
        askNotificationPermission();
        loadSettings(); // Load saved settings for slider and switch
        setupNewUIListeners(); // Setup listeners for new UI elements

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
                new java.net.URL(baseUrl); // Validate URL format
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
                startService(stopPollingIntent);
            }
        });

        buttonStopService.setOnClickListener(v -> {
            Log.d(TAG, "buttonStopService Clicked");
            Intent stopServiceIntent = new Intent(this, HttpPollingService.class);
            stopServiceIntent.setAction(HttpPollingService.ACTION_STOP_FOREGROUND_SERVICE);
            startService(stopServiceIntent);
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

    private void loadSettings() {
        int triggerDistance = sharedPreferences.getInt(PREF_TRIGGER_DISTANCE, DEFAULT_TRIGGER_DISTANCE);
        boolean notificationsEnabled = sharedPreferences.getBoolean(PREF_NOTIFICATIONS_ENABLED, DEFAULT_NOTIFICATIONS_ENABLED);

        seekBarTriggerDistance.setProgress(triggerDistance);
        textViewTriggerDistanceValue.setText(String.format(Locale.getDefault(), "%d cm", triggerDistance));
        switchEnableNotifications.setChecked(notificationsEnabled);
        Log.d(TAG, "loadSettings: TriggerDist=" + triggerDistance + "cm, NotificationsEnabled=" + notificationsEnabled);
    }

    private void saveTriggerDistance(int distance) {
        sharedPreferences.edit().putInt(PREF_TRIGGER_DISTANCE, distance).apply();
        Log.d(TAG, "saveTriggerDistance: Saved " + distance + "cm");
    }

    private void saveNotificationsEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(PREF_NOTIFICATIONS_ENABLED, enabled).apply();
        Log.d(TAG, "saveNotificationsEnabled: Saved " + enabled);
    }


    private void setupNewUIListeners() {
        seekBarTriggerDistance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textViewTriggerDistanceValue.setText(String.format(Locale.getDefault(), "%d cm", progress));
                 if (fromUser) { // Save only if changed by user to avoid saving during initial load
                    saveTriggerDistance(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                // final save, in case onProgressChanged didn't catch the last user interaction for some reason
                 saveTriggerDistance(seekBar.getProgress());
            }
        });

        switchEnableNotifications.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveNotificationsEnabled(isChecked);
        });
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
            discoveredServicesAdapter.clearServices();
            textViewDiscoveredServicesTitle.setVisibility(View.GONE);
            recyclerViewDiscoveredServices.setVisibility(View.GONE);
            Log.i(TAG, "toggleDiscovery: Starting discovery for Type='" + ESP_HTTP_SERVICE_TYPE + "', NameFilter='" + ESP_SERVICE_NAME_FILTER + "'");
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

    private void updateUIForInitialState() {
        Log.d(TAG, "updateUIForInitialState");
        textViewStatus.setText("Status: Idle. Enter address or scan.");
        textViewLastMessage.setText("Last Dist: None");
        String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
        buttonStartStopPolling.setEnabled(!TextUtils.isEmpty(currentAddress));
        buttonStartStopPolling.setText("Start Polling (Service)");
        buttonStopService.setText("Stop Service");
        buttonStopService.setEnabled(false);
        textInputLayoutEspAddress.setEnabled(true);
        editTextEspAddress.setEnabled(true);
        buttonStartStopDiscovery.setText("Scan Network for ESP32");
        buttonStartStopDiscovery.setEnabled(true);

        // Initial state for new controls (already handled by loadSettings)
    }

    private void updateUIForHttpPollingState(boolean isPolling, String statusTextFromService) {
        Log.d(TAG, "updateUIForHttpPollingState: isPolling=" + isPolling + ", statusText=" + statusTextFromService);
        textViewStatus.setText(statusTextFromService);
        String currentAddress = editTextEspAddress.getText() != null ? editTextEspAddress.getText().toString().trim() : "";
        boolean inputPresent = !TextUtils.isEmpty(currentAddress);

        buttonStartStopPolling.setEnabled(inputPresent);
        if (isPolling) {
            buttonStartStopPolling.setText("Stop Polling (Service)");
            buttonStopService.setEnabled(true);
        } else {
            buttonStartStopPolling.setText("Start Polling (Service)");
             buttonStopService.setEnabled(isServiceReceiverRegistered && !statusTextFromService.toLowerCase().contains("service stopped") && !statusTextFromService.toLowerCase().contains("error"));
        }
        textInputLayoutEspAddress.setEnabled(!isPolling);
        editTextEspAddress.setEnabled(!isPolling);

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
            if (!isHttpServicePolling && !currentStatusLower.contains("polling")) {
                 textViewStatus.setText("Status: Scan stopped. " + (discoveredServicesAdapter.getItemCount() == 0 ? "No matching services found." : "Select from list or enter address."));
            }
            buttonStartStopDiscovery.setText("Scan Network for ESP32");
        }
        buttonStartStopDiscovery.setEnabled(true);
    }

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

            if (ESP_HTTP_SERVICE_TYPE.startsWith(service.getType().replaceFirst("\\.$", ""))) {
                Log.d(TAG, "onNsdServiceResolved: Adding HTTP service to adapter: " + service.getServiceName());
                discoveredServicesAdapter.addService(service);
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
            // This requires a removeService method in adapter or managing the list here and calling setServices
            // For simplicity, if adapter internal list 'discoveredServices' is modified:
            // discoveredServicesAdapter.removeService(service); // You'd need to implement this in DiscoveredServicesAdapter
            // Or rebuild if you manage the list directly in MainActivity
            // discoveredServiceList.remove(service);
            // discoveredServicesAdapter.setServices(new ArrayList<>(discoveredServiceList));
             Toast.makeText(this, "Lost service: " + service.getServiceName(), Toast.LENGTH_SHORT).show();
             if (discoveredServicesAdapter.getItemCount() == 0) { // Re-check after potential removal
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
            String addressToUse = service.getHostAddress();
            // Port is usually 80 for HTTP, mDNS can specify others but our service will form http://host
            // If your ESP HTTP server is on a non-standard port found by NSD, you might need to append it:
            // if (service.getPort() != ESP_DEFAULT_HTTP_PORT && service.getPort() > 0) {
            //    addressToUse += ":" + service.getPort();
            // }
            editTextEspAddress.setText(addressToUse);
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
            writer.write("\n--- HTTP Data Log ---\n");
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
        loadSettings(); // Ensure UI reflects current settings on resume
        updateUIForHttpPollingState(isHttpServicePolling, textViewStatus.getText().toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Activity Paused.");
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
    }
}