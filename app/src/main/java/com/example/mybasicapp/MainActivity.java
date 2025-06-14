package com.example.mybasicapp;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar; // Added for Toolbar
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager2.widget.ViewPager2;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
// Removed SharedPreferences import as MainActivity itself won't directly manage them as much
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
// Removed Handler and Looper as NSD discovery timeout will be managed in DeviceManagementFragment
import android.util.Log;
// Removed View import as specific views are now in fragments
import android.widget.Button;
import android.widget.Toast;

import com.example.mybasicapp.adapters.PageAdapter;
import com.example.mybasicapp.viewmodels.AppViewModel;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

// Removed NsdHelper and DiscoveredServicesAdapter imports as they will be used in DeviceManagementFragment
// import com.example.mybasicapp.network.NsdHelper;
// import com.example.mybasicapp.adapters.DiscoveredServicesAdapter;
// import com.example.mybasicapp.model.DiscoveredService;


// For log saving (original code, slightly adapted)
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;


public class MainActivity extends AppCompatActivity { // Removed NsdHelper.NsdHelperListener, DiscoveredServicesAdapter.OnServiceClickListener

    private static final String TAG = "MainActivity_DEBUG";

    private AppViewModel appViewModel;
    private ViewPager2 viewPager;
    private PageAdapter pageAdapter;
    private TabLayout tabLayout;
    private Toolbar toolbar; // Added Toolbar
    private final String[] tabTitles = new String[]{"Home", "Noise Config", "ESP System", "Devices"};

    // For log saving - this log will now be more high-level activity log
    private final StringBuilder activityLog = new StringBuilder();
    private ActivityResultLauncher<String> createFileLauncher;
    private Button buttonSaveLogGlobal;

    // Permissions Launcher
    private ActivityResultLauncher<String> requestPostNotificationPermissionLauncher;


    // Receiver for HttpPollingService updates (status, data)
    // This can be kept in MainActivity to update a global log or for app-wide actions.
    // Fragments will observe AppViewModel data, which can be populated by this receiver or directly by the service.
    private boolean isServiceReceiverRegistered = false;
    private final BroadcastReceiver serviceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String timestamp = getCurrentTimestamp();
            Log.d(TAG, "MainActivity.serviceUpdateReceiver: Action: " + action);

            if (HttpPollingService.ACTION_STATUS_UPDATE.equals(action)) {
                String statusMessage = intent.getStringExtra(HttpPollingService.EXTRA_STATUS);
                if (statusMessage == null) statusMessage = "Unknown status from service";
                String logEntry = timestamp + " HTTP_Service_Status_RCV: " + statusMessage + "\n";
                activityLog.append(logEntry);
                Log.i(TAG, "MainActivity << HTTP_Status: " + statusMessage);
                if (appViewModel != null) {
                    appViewModel.setLastServiceStatus(statusMessage);
                }

            } else if (HttpPollingService.ACTION_DATA_RECEIVED.equals(action)) {
                String dataType = intent.getStringExtra(HttpPollingService.EXTRA_DATA_TYPE);
                String jsonData = intent.getStringExtra(HttpPollingService.EXTRA_DATA_JSON_STRING);
                String logMessage = (jsonData != null ? jsonData.substring(0, Math.min(jsonData.length(), 200)) + (jsonData.length() > 200 ? "..." : "") : "null data");
                String logEntry = timestamp + " HTTP_Data_RCV ("+dataType+"): " + logMessage + "\n";
                activityLog.append(logEntry);
                Log.i(TAG, "MainActivity << HTTP_Data (" + dataType + "): " + logMessage);

                if (appViewModel != null && jsonData != null) {
                    // Assuming all polled data is relevant for the "last sensor data" display in HomeFragment
                    appViewModel.setLastSensorJsonData(jsonData);
                }
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: Activity Creating");
        setContentView(R.layout.activity_main);

        // Initialize ViewModel
        appViewModel = new ViewModelProvider(this).get(AppViewModel.class);

        // Setup Toolbar
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar); // Set the Toolbar as the ActionBar

        // Setup ViewPager2 and TabLayout
        viewPager = findViewById(R.id.viewPager);
        tabLayout = findViewById(R.id.tabLayout);
        buttonSaveLogGlobal = findViewById(R.id.buttonSaveLogGlobal);

        pageAdapter = new PageAdapter(this);
        viewPager.setAdapter(pageAdapter);

        // Link TabLayout with ViewPager2
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(tabTitles[position])
        ).attach();

        // Initialize permission launcher
        requestPostNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                Log.d(TAG, "POST_NOTIFICATIONS permission result: " + isGranted);
                if (isGranted) {
                    Toast.makeText(this, "Notifications permission granted.", Toast.LENGTH_SHORT).show();
                    activityLog.append(getCurrentTimestamp()).append(" Permission: POST_NOTIFICATIONS Granted\n");
                } else {
                    Toast.makeText(this, "Notifications permission denied. App may not show alerts.", Toast.LENGTH_LONG).show();
                    activityLog.append(getCurrentTimestamp()).append(" Permission: POST_NOTIFICATIONS Denied\n");
                }
            });

        askNotificationPermission(); // For foreground services and alerts

        // Observe active ESP address changes from ViewModel
        appViewModel.getActiveEspAddressLiveData().observe(this, activeAddress -> {
            Log.i(TAG, "MainActivity Observer: Active ESP Address changed to: " + activeAddress);
            activityLog.append(getCurrentTimestamp()).append(" Active ESP changed to: ").append(activeAddress == null ? "None" : activeAddress).append("\n");

            // If the active address becomes null, ensure HttpPollingService is stopped.
            // If it changes to a new address, fragments (like HomeFragment) will be responsible
            // for initiating polling for that new address if monitoring is enabled.
            if (activeAddress == null || activeAddress.isEmpty()) {
                Log.d(TAG, "Active ESP is null/empty, ensuring HttpPollingService is stopped.");
                Intent stopServiceIntent = new Intent(this, HttpPollingService.class);
                stopServiceIntent.setAction(HttpPollingService.ACTION_STOP_FOREGROUND_SERVICE);
                startService(stopServiceIntent); // Use startService, not startForegroundService to stop
            }
            // Update Toolbar subtitle with active ESP
            if (getSupportActionBar() != null) {
                if (activeAddress != null && !activeAddress.isEmpty()) {
                    getSupportActionBar().setSubtitle("Active: " + activeAddress);
                } else {
                    getSupportActionBar().setSubtitle("No Active ESP");
                }
            }
        });

        // Initialize log saving launcher
        createFileLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                Log.d(TAG, "createFileLauncher: URI received for saving log: " + uri.getPath());
                saveLogToFile(uri);
            } else {
                Log.d(TAG, "createFileLauncher: Log saving cancelled by user.");
                Toast.makeText(MainActivity.this, "Log saving cancelled.", Toast.LENGTH_SHORT).show();
            }
        });
        
        buttonSaveLogGlobal.setOnClickListener(v -> {
            Log.d(TAG, "buttonSaveLogGlobal: Clicked");
            saveLog();
        });

        registerServiceUpdateReceiver(); // Register receiver for service updates
        activityLog.append(getCurrentTimestamp()).append(" MainActivity onCreate: Setup complete.\n");
        Log.d(TAG, "onCreate: Activity Created and UI Initialized");
    }


    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "askNotificationPermission: Requesting POST_NOTIFICATIONS permission.");
                activityLog.append(getCurrentTimestamp()).append(" Permission: Requesting POST_NOTIFICATIONS\n");
                requestPostNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                Log.d(TAG, "askNotificationPermission: POST_NOTIFICATIONS permission already granted.");
                activityLog.append(getCurrentTimestamp()).append(" Permission: POST_NOTIFICATIONS already granted\n");
            }
        }
    }
    
    private String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private void saveLog() {
        String fileName = "MrCoopersESP32_App_Log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        Log.d(TAG, "saveLog: Requesting to save log as " + fileName);
        activityLog.append(getCurrentTimestamp()).append(" UI_Action: Log Save Requested to file: ").append(fileName).append("\n");
        createFileLauncher.launch(fileName);
    }

    private void saveLogToFile(Uri uri) {
        Log.d(TAG, "saveLogToFile: Attempting to write log to URI: " + uri);
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
             OutputStreamWriter writer = new OutputStreamWriter(Objects.requireNonNull(outputStream))) {
            writer.write("--- MrCooperESP32 App General Log ---\n");
            writer.write("Log Start: " + getCurrentTimestamp() + "\n\n");
            writer.write(activityLog.toString());
            writer.flush();
            Toast.makeText(this, "Log saved successfully!", Toast.LENGTH_LONG).show();
            Log.i(TAG, "saveLogToFile: Log saved successfully to " + uri.getPath());
        } catch (IOException | NullPointerException e) {
            Log.e(TAG, "saveLogToFile: Error: " + e.getMessage(), e);
            Toast.makeText(this, "Error saving log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private void registerServiceUpdateReceiver() {
        if (!isServiceReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(HttpPollingService.ACTION_STATUS_UPDATE);
            filter.addAction(HttpPollingService.ACTION_DATA_RECEIVED);
            LocalBroadcastManager.getInstance(this).registerReceiver(serviceUpdateReceiver, filter);
            isServiceReceiverRegistered = true;
            Log.d(TAG, "registerServiceUpdateReceiver: ServiceUpdateReceiver registered in MainActivity.");
        }
    }

    private void unregisterServiceUpdateReceiver() {
        if (isServiceReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceUpdateReceiver);
            isServiceReceiverRegistered = false;
            Log.d(TAG, "unregisterServiceUpdateReceiver: ServiceUpdateReceiver unregistered in MainActivity.");
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Activity Resumed.");
        activityLog.append(getCurrentTimestamp()).append(" MainActivity onResume\n");
        registerServiceUpdateReceiver(); // Ensure receiver is registered
        // Refresh subtitle based on current active ESP from ViewModel
        if (getSupportActionBar() != null && appViewModel != null) {
            String activeAddress = appViewModel.getActiveEspAddressLiveData().getValue();
            if (activeAddress != null && !activeAddress.isEmpty()) {
                getSupportActionBar().setSubtitle("Active: " + activeAddress);
            } else {
                getSupportActionBar().setSubtitle("No Active ESP");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Activity Paused.");
        activityLog.append(getCurrentTimestamp()).append(" MainActivity onPause\n");
        // Keeping receiver registered to log service updates even if paused.
        // If this causes issues or is not desired, unregister here.
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop: Activity Stopped.");
        activityLog.append(getCurrentTimestamp()).append(" MainActivity onStop\n");
        // Any cleanup related to discovery if it were managed here would go here
        // But NSD is now in DeviceManagementFragment.
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: Activity Destroying.");
        activityLog.append(getCurrentTimestamp()).append(" MainActivity onDestroy. Final log dump before clear:\n").append(activityLog.toString());
        unregisterServiceUpdateReceiver(); // Clean up receiver
        // NsdHelper tearDown will be handled by DeviceManagementFragment.
        super.onDestroy(); // Call super.onDestroy() last
    }
}