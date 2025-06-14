package com.example.mybasicapp.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver; // ADDED IMPORT
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mybasicapp.AlertSoundService; // For playing custom sound alerts
import com.example.mybasicapp.HttpPollingService;
import com.example.mybasicapp.R;
import com.example.mybasicapp.viewmodels.AppViewModel;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment_DBG";

    private AppViewModel appViewModel;
    private TextView textViewHomeStatusTitle, textViewMicData, textViewAppAlertLevelValue;
    private SwitchMaterial switchEnableMicMonitoring, switchEnableAppAlerts;
    private SeekBar seekBarAppAlertLevel;

    // For HttpPollingService interactions and observing data
    private String currentActiveEspIpForFragment = null;
    private boolean isPollingThisEsp = false; // Tracks if polling is active for currentActiveEspIpForFragment

    // SharedPreferences for app-side alert settings (distinct from ESP's internal threshold)
    private static final String PREFS_HOME_FRAGMENT = "HomeFragmentPrefs_v2"; // Unique prefs for this fragment
    private static final String PREF_APP_ALERT_LEVEL_DB = "app_alert_level_db";
    private static final String PREF_APP_ALERTS_ENABLED = "app_alerts_enabled";

    // SharedPreferences for custom sound settings (can be shared with HttpPollingService)
    // These keys should match what HttpPollingService and AlertSoundService expect/use
    private static final String SERVICE_PREFS_NAME = "MrCooperESP_Prefs"; // Matching HttpPollingService
    private static final String PREF_CUSTOM_ALERT_SOUND_URI = "custom_alert_sound_uri";
    private static final String PREF_CUSTOM_ALERT_SOUND_ENABLED = "custom_alert_sound_enabled";

    private SharedPreferences homeFragmentPrefs; // For app-side alert level
    private SharedPreferences serviceSharedPrefs;  // For custom sound URI and enable status (read by service)


    // Custom Sound UI elements from original MainActivity
    private Button buttonSelectCustomSound, buttonTestCustomSound;
    private TextView textViewSelectedCustomSound;
    private SwitchMaterial switchEnableCustomSoundAlert; // Renamed to avoid conflict if any
    private Uri currentCustomSoundUriForPicker = null; // URI selected by the picker in this fragment
    private MediaPlayer localTestMediaPlayer;
    private ActivityResultLauncher<Intent> selectCustomSoundLauncher;
    private ActivityResultLauncher<String> requestStoragePermissionLauncher;


    public HomeFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        homeFragmentPrefs = requireActivity().getSharedPreferences(PREFS_HOME_FRAGMENT, Context.MODE_PRIVATE);
        serviceSharedPrefs = requireActivity().getSharedPreferences(SERVICE_PREFS_NAME, Context.MODE_PRIVATE);

        // Launcher for selecting custom sound file
        selectCustomSoundLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            // Persist read permission for the URI
                            final int takeFlags = result.getData().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                            requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
                            currentCustomSoundUriForPicker = uri;
                            saveCustomSoundUriToServicePrefs(currentCustomSoundUriForPicker); // Save to service prefs
                            updateCustomSoundDisplayUI();
                            Toast.makeText(getContext(), getString(R.string.custom_sound_selected_toast, getFileNameFromUri(currentCustomSoundUriForPicker)), Toast.LENGTH_SHORT).show();
                        } catch (SecurityException e) {
                            Log.e(TAG, "Failed to take persistable URI permission for custom sound", e);
                            Toast.makeText(getContext(), "Failed to get permanent access to sound file.", Toast.LENGTH_LONG).show();
                            currentCustomSoundUriForPicker = null;
                            saveCustomSoundUriToServicePrefs(null); // Clear if permission failed
                            updateCustomSoundDisplayUI();
                        }
                    }
                }
            });

        // Launcher for requesting storage permission for custom sound
        requestStoragePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "Storage permission granted for custom sound selection.");
                    openCustomSoundPicker();
                } else {
                    Toast.makeText(getContext(), R.string.storage_permission_required_toast, Toast.LENGTH_LONG).show();
                }
            });
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        textViewHomeStatusTitle = view.findViewById(R.id.textViewHomeStatusTitle);
        textViewMicData = view.findViewById(R.id.textViewMicData);
        switchEnableMicMonitoring = view.findViewById(R.id.switchEnableMicMonitoring);
        seekBarAppAlertLevel = view.findViewById(R.id.seekBarAppAlertLevel);
        textViewAppAlertLevelValue = view.findViewById(R.id.textViewAppAlertLevelValue);
        switchEnableAppAlerts = view.findViewById(R.id.switchEnableAppAlerts);

        // Custom Sound UI elements
        buttonSelectCustomSound = view.findViewById(R.id.buttonSelectCustomSound);
        textViewSelectedCustomSound = view.findViewById(R.id.textViewSelectedCustomSound);
        buttonTestCustomSound = view.findViewById(R.id.buttonTestCustomSound);
        switchEnableCustomSoundAlert = view.findViewById(R.id.switchEnableCustomSoundAlert); // Use the renamed ID

        loadFragmentSettings(); // Load app-alert level and custom sound settings
        setupUIListeners();
            
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Observe active ESP address
        appViewModel.getActiveEspAddressLiveData().observe(getViewLifecycleOwner(), address -> {
            String oldAddress = currentActiveEspIpForFragment;
            currentActiveEspIpForFragment = address; // This is the normalized address from ViewModel

            if (address != null && !address.isEmpty()) {
                textViewHomeStatusTitle.setText(getString(R.string.home_status_active_esp, getHostFromUrlSafe(address)));
                switchEnableMicMonitoring.setEnabled(true);
                // If address changed, and monitoring was on, stop for old, start for new
                if (switchEnableMicMonitoring.isChecked()) {
                    if (oldAddress != null && !oldAddress.equalsIgnoreCase(address) && isPollingThisEsp) {
                        // Implicitly stopped by starting for new one if service handles one URL at a time
                    }
                    startOrUpdateMicMonitoring(address);
                }
            } else {
                textViewHomeStatusTitle.setText(R.string.home_status_no_active_esp);
                textViewMicData.setText(R.string.mic_data_no_active_esp);
                switchEnableMicMonitoring.setEnabled(false);
                switchEnableMicMonitoring.setChecked(false); // Ensure it's off if no active ESP
                if (isPollingThisEsp) { // if it was polling something before active ESP became null
                    stopMicMonitoringService();
                }
            }
        });

        // Observe last service status
        appViewModel.getLastServiceStatusLiveData().observe(getViewLifecycleOwner(), status -> {
            if (status != null && currentActiveEspIpForFragment != null) {
                 if (status.toLowerCase().contains("polling started for " + getHostFromUrlSafe(currentActiveEspIpForFragment).toLowerCase())) {
                    isPollingThisEsp = true;
                    textViewHomeStatusTitle.setText(getString(R.string.home_status_polling, getHostFromUrlSafe(currentActiveEspIpForFragment)));
                } else if (status.toLowerCase().contains("polling stopped") || status.toLowerCase().contains("service stopped")) {
                    // Check if it's a general stop or related to our specific ESP
                    // This logic might need refinement if multiple ESPs could be "polling stopped"
                    isPollingThisEsp = false;
                    textViewHomeStatusTitle.setText(getString(R.string.home_status_paused, getHostFromUrlSafe(currentActiveEspIpForFragment)));
                }
                switchEnableMicMonitoring.setChecked(isPollingThisEsp);
            } else if (currentActiveEspIpForFragment == null) {
                isPollingThisEsp = false;
                switchEnableMicMonitoring.setChecked(false);
            }
        });

        // Observe last sensor data (JSON string)
        appViewModel.getLastSensorJsonDataLiveData().observe(getViewLifecycleOwner(), jsonData -> {
            if (jsonData != null && !jsonData.isEmpty()) {
                processAndDisplaySensorData(jsonData);
            } else if (currentActiveEspIpForFragment != null) {
                textViewMicData.setText(R.string.mic_data_waiting);
            } else {
                textViewMicData.setText(R.string.mic_data_no_active_esp);
            }
        });
    }

    private void loadFragmentSettings() {
        // Load app-side alert settings
        int alertLevel = homeFragmentPrefs.getInt(PREF_APP_ALERT_LEVEL_DB, 70); // Default 70 dB
        boolean alertsEnabled = homeFragmentPrefs.getBoolean(PREF_APP_ALERTS_ENABLED, false);
        seekBarAppAlertLevel.setProgress(alertLevel);
        textViewAppAlertLevelValue.setText(getString(R.string.decibel_format, alertLevel));
        switchEnableAppAlerts.setChecked(alertsEnabled);

        // Load Custom Sound Settings from service's SharedPreferences
        String soundUriString = serviceSharedPrefs.getString(PREF_CUSTOM_ALERT_SOUND_URI, null);
        if (soundUriString != null) {
            currentCustomSoundUriForPicker = Uri.parse(soundUriString);
            // Verify access to persistable URI (important on app restart)
            try {
                if (getContext() != null) {
                    // Attempting to open it is a way to check persistable permission
                    getContext().getContentResolver().openInputStream(currentCustomSoundUriForPicker).close();
                }
            } catch (Exception e) { // SecurityException or FileNotFoundException
                Log.w(TAG, "loadFragmentSettings: Lost permission or file not found for custom sound URI: " + currentCustomSoundUriForPicker, e);
                Toast.makeText(getContext(), R.string.custom_sound_no_longer_accessible_toast, Toast.LENGTH_LONG).show();
                currentCustomSoundUriForPicker = null;
                saveCustomSoundUriToServicePrefs(null); // Clear from prefs if no longer accessible
            }
        } else {
            currentCustomSoundUriForPicker = null;
        }
        // Default custom sound to enabled if a URI is set, otherwise false
        boolean customSoundIsEnabledInPrefs = serviceSharedPrefs.getBoolean(PREF_CUSTOM_ALERT_SOUND_ENABLED, currentCustomSoundUriForPicker != null);
        switchEnableCustomSoundAlert.setChecked(customSoundIsEnabledInPrefs);
        updateCustomSoundDisplayUI();
        Log.d(TAG, "Loaded settings: AppAlert=" + alertLevel + "dB, AppAlertsEnabled=" + alertsEnabled +
                   ", CustomSoundURI=" + (currentCustomSoundUriForPicker != null) +
                   ", CustomSoundEnabled=" + customSoundIsEnabledInPrefs);
    }

    private void saveAppSettingsToHomePrefs() {
        SharedPreferences.Editor editor = homeFragmentPrefs.edit();
        editor.putInt(PREF_APP_ALERT_LEVEL_DB, seekBarAppAlertLevel.getProgress());
        editor.putBoolean(PREF_APP_ALERTS_ENABLED, switchEnableAppAlerts.isChecked());
        editor.apply();
        Log.d(TAG, "Saved app alert settings to HomeFragmentPrefs.");
    }

    private void setupUIListeners() {
        switchEnableMicMonitoring.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
                if (isChecked) {
                    startOrUpdateMicMonitoring(currentActiveEspIpForFragment);
                } else {
                    stopMicMonitoringService(); // This stops polling for the active ESP
                }
            } else {
                if (isChecked) { // Tried to enable without active ESP
                    Toast.makeText(getContext(), R.string.no_active_esp_selected_toast, Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(false); // Revert switch state
                }
            }
        });

        seekBarAppAlertLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textViewAppAlertLevelValue.setText(getString(R.string.decibel_format, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveAppSettingsToHomePrefs(); }
        });

        switchEnableAppAlerts.setOnCheckedChangeListener((buttonView, isChecked) -> saveAppSettingsToHomePrefs());
        
        // Custom sound UI listeners
        buttonSelectCustomSound.setOnClickListener(v -> checkStoragePermissionAndOpenPicker());
        buttonTestCustomSound.setOnClickListener(v -> {
            if (currentCustomSoundUriForPicker != null) {
                playLocalTestSound(currentCustomSoundUriForPicker);
            } else {
                Toast.makeText(getContext(), R.string.no_custom_sound_selected, Toast.LENGTH_SHORT).show();
            }
        });
        switchEnableCustomSoundAlert.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveCustomSoundEnabledToServicePrefs(isChecked);
        });
    }

    private void startOrUpdateMicMonitoring(String espIpAddress) {
        if (espIpAddress == null || espIpAddress.isEmpty()) {
            Log.e(TAG, "Cannot start monitoring, ESP IP is null or empty.");
            Toast.makeText(getContext(), R.string.esp_address_not_set_toast, Toast.LENGTH_SHORT).show();
            switchEnableMicMonitoring.setChecked(false);
            return;
        }
        Log.i(TAG, "Requesting to start/update mic monitoring for: " + espIpAddress);
        Intent serviceIntent = new Intent(getActivity(), HttpPollingService.class);
        // ACTION_START_FOREGROUND_SERVICE will also handle starting polling.
        // If the service is already running for a different URL, it should update its target.
        serviceIntent.setAction(HttpPollingService.ACTION_START_FOREGROUND_SERVICE);
        // HttpPollingService expects full base URL, EspDevice provides normalized address
        serviceIntent.putExtra(HttpPollingService.EXTRA_BASE_URL, "http://" + espIpAddress);
        if (getActivity() != null) {
            ContextCompat.startForegroundService(requireActivity(), serviceIntent);
        }
        // isPollingThisEsp will be updated by observer listening to service status
    }

    private void stopMicMonitoringService() {
        Log.i(TAG, "Requesting to stop mic monitoring polling.");
        Intent serviceIntent = new Intent(getActivity(), HttpPollingService.class);
        // ACTION_STOP_POLLING will tell the service to stop its current polling loop.
        // The service itself might remain running if it's a foreground service and not explicitly stopped.
        serviceIntent.setAction(HttpPollingService.ACTION_STOP_POLLING);
        if (getActivity() != null) {
             getActivity().startService(serviceIntent);
        }
        // isPollingThisEsp will be updated by observer
    }

    private void processAndDisplaySensorData(String jsonData) {
        // This method assumes the jsonData is from the currently active ESP,
        // as filtering should ideally happen before calling this (e.g., in service or MainActivity receiver).
        if (getContext() == null) return; // Fragment not attached

        try {
            JSONObject json = new JSONObject(jsonData);
            // ESP code sends "db_calibrated", "rms", "status", "error"
            double dbCalibrated = json.optDouble("db_calibrated", -999.0); // Default to a very low value
            double rms = json.optDouble("rms", -1.0);
            String espDeviceStatus = json.optString("status", "N/A");
            String espError = json.optString("error", ""); // Default to empty string

            if (!espError.isEmpty() && !"null".equalsIgnoreCase(espError)) {
                textViewMicData.setText(getString(R.string.mic_data_esp_error, espError));
            } else if (dbCalibrated == -999.0) { // Indicates data not present or invalid
                 textViewMicData.setText(R.string.mic_data_invalid_default);
            }
            else {
                textViewMicData.setText(getString(R.string.mic_data_format, dbCalibrated, rms, espDeviceStatus));
            }

            // App-side alert generation logic
            if (switchEnableAppAlerts.isChecked() && (espError.isEmpty() || "null".equalsIgnoreCase(espError)) && dbCalibrated != -999.0) {
                int appAlertThreshold = seekBarAppAlertLevel.getProgress();
                if (dbCalibrated >= appAlertThreshold) {
                    Log.i(TAG, String.format("APP-SIDE ALERT: Level %.1f dB >= Threshold %d dB", dbCalibrated, appAlertThreshold));
                    // TODO: Trigger app-side visual/sound alert
                    // For sound, use AlertSoundService if custom sound is enabled and URI is set
                    if (switchEnableCustomSoundAlert.isChecked() && currentCustomSoundUriForPicker != null) {
                        Intent alertSoundIntent = new Intent(getContext(), AlertSoundService.class);
                        alertSoundIntent.setAction(AlertSoundService.ACTION_PLAY_CUSTOM_SOUND);
                        alertSoundIntent.putExtra(AlertSoundService.EXTRA_SOUND_URI, currentCustomSoundUriForPicker.toString());
                        alertSoundIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        ContextCompat.startForegroundService(requireContext(), alertSoundIntent);
                        Log.d(TAG, "App-side alert: Started AlertSoundService for custom sound.");
                    } else {
                        // Could play a default notification sound here if no custom sound
                        Log.d(TAG, "App-side alert: Custom sound not enabled or URI not set.");
                    }
                     // Could also show a Toast or update a visual indicator in the fragment
                    Toast.makeText(getContext(), "LOUD! " + String.format(Locale.getDefault(), "%.1f dB", dbCalibrated), Toast.LENGTH_SHORT).show();
                }
            }

        } catch (JSONException e) {
            textViewMicData.setText(R.string.mic_data_parse_error);
            Log.e(TAG, "Error parsing mic data JSON in HomeFragment: " + e.getMessage());
        }
    }
        
    private String getHostFromUrlSafe(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) return "Unknown";
        try {
            // Address from ViewModel is already normalized (no http/https)
            // For display, we just use it. If it were a full URL:
            // java.net.URL url = new java.net.URL(urlString.startsWith("http") ? urlString : "http://" + urlString);
            // return url.getHost() + (url.getPort() != -1 && url.getPort() != 80 && url.getPort() != url.getDefaultPort() ? ":" + url.getPort() : "");
            return urlString; // Since it's already just hostname/IP
        } catch (Exception e) { // e.g. MalformedURLException if it wasn't pre-normalized
            Log.w(TAG, "Malformed URL for getHostFromUrlSafe: " + urlString);
            return urlString; // return original if error
        }
    }
    
    // --- Custom Sound Methods (similar to those in original MainActivity) ---
    private void saveCustomSoundUriToServicePrefs(Uri uri) {
        SharedPreferences.Editor editor = serviceSharedPrefs.edit();
        if (uri != null) {
            editor.putString(PREF_CUSTOM_ALERT_SOUND_URI, uri.toString());
        } else {
            editor.remove(PREF_CUSTOM_ALERT_SOUND_URI);
        }
        editor.apply();
        Log.d(TAG, "Saved custom sound URI to service prefs: " + (uri != null ? uri.toString() : "null"));
    }

    private void saveCustomSoundEnabledToServicePrefs(boolean enabled) {
        serviceSharedPrefs.edit().putBoolean(PREF_CUSTOM_ALERT_SOUND_ENABLED, enabled).apply();
        Log.d(TAG, "Saved custom sound enabled status to service prefs: " + enabled);
    }

    private void updateCustomSoundDisplayUI() {
        // UI update based on currentCustomSoundUriForPicker
        if (currentCustomSoundUriForPicker != null) {
            textViewSelectedCustomSound.setText(getString(R.string.custom_sound_selected_label, getFileNameFromUri(currentCustomSoundUriForPicker)));
            buttonTestCustomSound.setEnabled(true);
            switchEnableCustomSoundAlert.setEnabled(true); // Enable switch if URI is present
        } else {
            textViewSelectedCustomSound.setText(R.string.no_custom_sound_selected);
            buttonTestCustomSound.setEnabled(false);
            switchEnableCustomSoundAlert.setEnabled(false); // Disable switch if no URI
            switchEnableCustomSoundAlert.setChecked(false); // Also uncheck it
            saveCustomSoundEnabledToServicePrefs(false); // Persist disabled state
        }
    }

    private String getFileNameFromUri(Uri uri) {
        if (uri == null || getContext() == null) return "None";
        String result = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) { // Check if column exists
                        result = cursor.getString(displayNameIndex);
                    } else {
                        Log.w(TAG, "Display name column not found for URI: " + uri);
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Error getting file name from content URI: " + uri, e); }
        }
        if (result == null) { // Fallback for file URI or if content resolver fails
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) result = result.substring(cut + 1);
            } else result = uri.toString(); // Absolute fallback
        }
        return result != null ? result : "Unknown File";
    }

    private void checkStoragePermissionAndOpenPicker() {
        if (getContext() == null) return;
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE; // For API < 33
        }

        if (ContextCompat.checkSelfPermission(getContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            openCustomSoundPicker();
        } else {
            Log.d(TAG, "Requesting storage permission: " + permission);
            requestStoragePermissionLauncher.launch(permission);
        }
    }

    private void openCustomSoundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        // Crucial flags for persistent access
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            selectCustomSoundLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error launching custom sound picker", e);
            Toast.makeText(getContext(), "Could not open sound picker.", Toast.LENGTH_SHORT).show();
        }
    }

    private void playLocalTestSound(Uri soundUri) {
        if (getContext() == null) return;
        if (localTestMediaPlayer != null) {
            localTestMediaPlayer.release();
            localTestMediaPlayer = null;
        }
        localTestMediaPlayer = new MediaPlayer();
        localTestMediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA) // For local test, MEDIA is fine
                        .build());
        try {
            localTestMediaPlayer.setDataSource(getContext(), soundUri);
            localTestMediaPlayer.prepareAsync(); // Use prepareAsync for non-blocking UI
            localTestMediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "Local test MediaPlayer prepared, starting playback.");
                mp.start();
                Toast.makeText(getContext(), R.string.custom_sound_playing_test, Toast.LENGTH_SHORT).show();
            });
            localTestMediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "Local test MediaPlayer playback completed.");
                Toast.makeText(getContext(), R.string.custom_sound_test_finished, Toast.LENGTH_SHORT).show();
                if(localTestMediaPlayer != null) { mp.release(); localTestMediaPlayer = null;}
            });
            localTestMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Local test MediaPlayer error: what=" + what + ", extra=" + extra);
                Toast.makeText(getContext(), R.string.custom_sound_error_playing, Toast.LENGTH_SHORT).show();
                if(localTestMediaPlayer != null) {mp.release(); localTestMediaPlayer = null;}
                return true; // Error handled
            });
        } catch (IOException e) {
            Log.e(TAG, "IOException setting data source for local MediaPlayer: " + soundUri, e);
            Toast.makeText(getContext(), R.string.custom_sound_error_preparing, Toast.LENGTH_SHORT).show();
             if (localTestMediaPlayer != null) { localTestMediaPlayer.release(); localTestMediaPlayer = null; }
        } catch (SecurityException se) {
             Log.e(TAG, "SecurityException setting data source (URI permission issue?): " + soundUri, se);
            Toast.makeText(getContext(), "Permission error for sound file.", Toast.LENGTH_SHORT).show();
            if (localTestMediaPlayer != null) { localTestMediaPlayer.release(); localTestMediaPlayer = null; }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        // Re-load settings as they might have been changed by other components or on app restart
        loadFragmentSettings();
        // The observers in onViewCreated will handle UI updates based on ViewModel state
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        // Release media player if it's playing a test sound
        if (localTestMediaPlayer != null) {
            if (localTestMediaPlayer.isPlaying()) {
                localTestMediaPlayer.stop();
            }
            localTestMediaPlayer.release();
            localTestMediaPlayer = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView");
        // Clean up MediaPlayer to prevent leaks
        if (localTestMediaPlayer != null) {
            localTestMediaPlayer.release();
            localTestMediaPlayer = null;
        }
        // View is destroyed, binding should be nulled if using ViewBinding
    }
}