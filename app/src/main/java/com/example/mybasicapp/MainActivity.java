package com.example.mybasicapp;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int LEGACY_PERMISSIONS_REQUEST_CODE = 101; // For direct onRequestPermissionsResult
    private static final String PREFS_NAME = "AlertPrefs";
    private static final String PREF_SOUND_URI = "soundUri";

    private Button selectSoundButton;
    private Button testSoundButton;
    private Button simulateSensorButton;
    private TextView selectedSoundText;

    private Uri selectedSoundUri;
    private MediaPlayer mediaPlayer; // For local testing only

    private ActivityResultLauncher<Intent> selectSoundLauncher;
    private ActivityResultLauncher<String> requestSinglePermissionLauncher;
    private ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        selectSoundButton = findViewById(R.id.select_sound_button);
        testSoundButton = findViewById(R.id.test_sound_button);
        simulateSensorButton = findViewById(R.id.simulate_sensor_button);
        selectedSoundText = findViewById(R.id.selected_sound_text);

        loadSavedSoundUri();
        updateSelectedSoundTextUI();
        createNotificationChannel();

        // Launcher for selecting a sound file
        selectSoundLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            // Persist read permission for the URI
                            final int takeFlags = result.getData().getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION); // Only need read
                            try {
                                getContentResolver().takePersistableUriPermission(uri, takeFlags);
                                Log.d(TAG, "Persistable URI permission taken for: " + uri.toString());
                            } catch (SecurityException e) {
                                Log.e(TAG, "Failed to take persistable URI permission", e);
                                Toast.makeText(this, "Failed to get permanent access to sound file.", Toast.LENGTH_LONG).show();
                            }
                            selectedSoundUri = uri;
                            saveSoundUriToPrefs(selectedSoundUri);
                            updateSelectedSoundTextUI();
                            Toast.makeText(this, getString(R.string.sound_selected_toast, getFileNameFromUri(selectedSoundUri)), Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        // Launcher for requesting a single permission (e.g., specific storage or notification)
        requestSinglePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        Log.d(TAG, "Permission granted by single launcher.");
                        // Decide next action based on context, e.g., open file picker or trigger service
                        // This is a generic handler; specific actions should be managed where it's launched
                    } else {
                        Toast.makeText(this, R.string.permission_denied_toast, Toast.LENGTH_LONG).show();
                    }
                });
        
        // Launcher for requesting multiple permissions
        requestMultiplePermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (String perm : permissions.keySet()) {
                        if (!Boolean.TRUE.equals(permissions.get(perm))) {
                            allGranted = false;
                            Log.w(TAG, "Permission denied: " + perm);
                        } else {
                             Log.d(TAG, "Permission granted: " + perm);
                        }
                    }
                    if (allGranted) {
                        Log.d(TAG, "All permissions granted by multiple launcher.");
                    } else {
                        Toast.makeText(this, R.string.some_permissions_denied_toast, Toast.LENGTH_LONG).show();
                    }
                });


        selectSoundButton.setOnClickListener(v -> checkAndRequestStoragePermissionToPickFile());

        testSoundButton.setOnClickListener(v -> {
            if (selectedSoundUri != null) {
                playSoundLocally(selectedSoundUri);
            } else {
                Toast.makeText(this, R.string.no_sound_selected, Toast.LENGTH_SHORT).show();
            }
        });

        simulateSensorButton.setOnClickListener(v -> {
            if (selectedSoundUri != null) {
                checkAndRequestNotificationPermissionToStartService();
            } else {
                Toast.makeText(this, R.string.select_sound_first_toast, Toast.LENGTH_SHORT).show();
            }
        });

        checkAndRequestInitialPermissions(); // Request necessary permissions on startup
    }

    private void checkAndRequestInitialPermissions() {
        String[] permissionsToRequest;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            // For file picking and foreground service notifications
            permissionsToRequest = new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
        } else { // Android 12 and below
            permissionsToRequest = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
            // POST_NOTIFICATIONS not needed for foreground service pre-Tiramisu
        }

        boolean allPermissionsNeededAreGranted = true;
        for (String permission : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsNeededAreGranted = false;
                break;
            }
        }

        if (!allPermissionsNeededAreGranted) {
            Log.d(TAG, "Requesting initial permissions...");
            requestMultiplePermissionsLauncher.launch(permissionsToRequest);
        } else {
            Log.d(TAG, "All initial permissions already granted.");
        }
    }


    private void checkAndRequestStoragePermissionToPickFile() {
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            openFilePicker();
        } else {
            Log.d(TAG, "Requesting storage permission to pick file...");
            requestSinglePermissionLauncher.launch(permission); // Re-check and open picker in callback if needed or assume it's for this action
        }
    }
    
    private void checkAndRequestNotificationPermissionToStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission to start service...");
                 requestSinglePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); // Then trigger service if granted
                 // Consider handling the case where permission is denied explicitly here.
                 // For now, if denied, service won't start. User has to click again.
            } else {
                triggerAlertService(); // Permission already granted
            }
        } else {
            triggerAlertService(); // No specific notification permission needed pre-Tiramisu
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        // Permissions are granted via the result, and persisted using takePersistableUriPermission
        selectSoundLauncher.launch(intent);
    }

    private void playSoundLocally(Uri soundUri) {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA) // Or USAGE_ALARM for test
                        .build());
        try {
            mediaPlayer.setDataSource(this, soundUri);
            mediaPlayer.prepareAsync(); 
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                Toast.makeText(MainActivity.this, R.string.playing_test_sound, Toast.LENGTH_SHORT).show();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                mediaPlayer = null;
                Toast.makeText(MainActivity.this, R.string.test_sound_finished, Toast.LENGTH_SHORT).show();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer (local test) error: " + what + ", " + extra);
                Toast.makeText(MainActivity.this, R.string.error_playing_sound, Toast.LENGTH_SHORT).show();
                mp.release();
                mediaPlayer = null;
                return true;
            });
        } catch (IOException e) {
            Log.e(TAG, "Error setting data source for local MediaPlayer", e);
            Toast.makeText(this, R.string.error_preparing_sound, Toast.LENGTH_SHORT).show();
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
        }
    }

    private void triggerAlertService() {
        if (selectedSoundUri == null) {
            Toast.makeText(this, R.string.no_alert_sound_selected_service, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent serviceIntent = new Intent(this, AlertService.class);
        serviceIntent.setAction(AlertService.ACTION_PLAY_SOUND);
        serviceIntent.putExtra(AlertService.EXTRA_SOUND_URI, selectedSoundUri.toString());
        
        // Grant URI permission to the service if it doesn't have persisted access
        // The persistable permission should cover this, but this is an explicit grant for the service's lifetime
        serviceIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, R.string.sensor_triggered_toast, Toast.LENGTH_LONG).show();
    }


    private void saveSoundUriToPrefs(Uri uri) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (uri != null) {
            editor.putString(PREF_SOUND_URI, uri.toString());
        } else {
            editor.remove(PREF_SOUND_URI);
        }
        editor.apply();
    }

    private void loadSavedSoundUri() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(PREF_SOUND_URI, null);
        if (uriString != null) {
            selectedSoundUri = Uri.parse(uriString);
            // Check if we still have permission for this URI
            try {
                 // Check if the URI is still accessible by trying to query its display name or open an input stream.
                 // This implicitly checks persisted URI permissions.
                if (getContentResolver().query(selectedSoundUri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null) == null) {
                    throw new SecurityException("Cannot query URI, permission likely lost.");
                }
                 Log.d(TAG, "Successfully re-accessed persisted URI: " + selectedSoundUri.toString());
            } catch (Exception e) { // Catches SecurityException or others if URI is invalid
                Log.w(TAG, "Failed to re-access persisted URI: " + selectedSoundUri.toString() + ". Clearing it.", e);
                selectedSoundUri = null; 
                saveSoundUriToPrefs(null); 
                Toast.makeText(this, R.string.sound_no_longer_accessible_toast, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateSelectedSoundTextUI() {
        if (selectedSoundUri != null) {
            selectedSoundText.setText(getString(R.string.selected_sound_label, getFileNameFromUri(selectedSoundUri)));
            testSoundButton.setEnabled(true);
            simulateSensorButton.setEnabled(true);
        } else {
            selectedSoundText.setText(R.string.no_sound_selected);
            testSoundButton.setEnabled(false);
            simulateSensorButton.setEnabled(false);
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri == null) return "Unknown";
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                         result = cursor.getString(displayNameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting file name from content URI", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            } else {
                result = "Unknown File";
            }
        }
        return result;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    AlertService.CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW 
            );
            serviceChannel.setDescription(getString(R.string.notification_channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "Notification channel created.");
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Release media player used for local testing if activity is stopping
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    // Fallback for older permission handling if not using ActivityResultLaunchers exclusively
    // Or if a permission is requested directly with ActivityCompat.requestPermissions
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LEGACY_PERMISSIONS_REQUEST_CODE) { // Example, ensure this code is used if ActivityCompat.requestPermissions is called directly
            // This block would be more relevant if not using ActivityResultLaunchers for all permission requests.
            // With launchers, the callbacks are handled by the launchers themselves.
            // This is mostly a fallback or for demonstration.
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "Legacy permission denied: " + permissions[i]);
                    Toast.makeText(this, "Permission " + permissions[i] + " denied.", Toast.LENGTH_LONG).show();
                    break; 
                }
            }
            if (allGranted) {
                 Log.d(TAG, "Legacy permissions granted via onRequestPermissionsResult.");
                 // Potentially re-trigger an action that was waiting for this permission
            }
        }
    }
}