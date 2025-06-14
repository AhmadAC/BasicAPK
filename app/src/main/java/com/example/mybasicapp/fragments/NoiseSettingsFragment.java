package com.example.mybasicapp.fragments;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mybasicapp.R;
import com.example.mybasicapp.network.EspConfigClient;
import com.example.mybasicapp.viewmodels.AppViewModel;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout; // If you need to access the TextInputLayout

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

public class NoiseSettingsFragment extends Fragment {
    private static final String TAG = "NoiseSettingsFrag_DBG";

    private AppViewModel appViewModel;
    private EspConfigClient espConfigClient;

    private TextInputEditText editTextEspThreshold;
    private Button buttonSetEspThreshold, buttonRefreshEspThreshold; // Added refresh button
    private TextView textViewCurrentEspThresholdValue, textViewNoiseSettingsStatus;
    private TextInputLayout textInputLayoutEspThreshold; // For enabling/disabling

    private String currentActiveEspIpForFragment = null;
    private boolean isFetchingThreshold = false; // To prevent multiple simultaneous fetches

    public NoiseSettingsFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        espConfigClient = new EspConfigClient(); // Initialize your network client
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_noise_settings, container, false);

        editTextEspThreshold = view.findViewById(R.id.editTextEspThreshold);
        textInputLayoutEspThreshold = view.findViewById(R.id.textInputLayoutEspThreshold); // Get the layout
        buttonSetEspThreshold = view.findViewById(R.id.buttonSetEspThreshold);
        buttonRefreshEspThreshold = view.findViewById(R.id.buttonRefreshEspThreshold);
        textViewCurrentEspThresholdValue = view.findViewById(R.id.textViewCurrentEspThresholdValue);
        textViewNoiseSettingsStatus = view.findViewById(R.id.textViewNoiseSettingsStatus);

        buttonSetEspThreshold.setOnClickListener(v -> setEspThresholdValue());
        buttonRefreshEspThreshold.setOnClickListener(v -> {
            if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
                fetchCurrentThresholdFromEsp(currentActiveEspIpForFragment);
            } else {
                Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        appViewModel.getActiveEspAddressLiveData().observe(getViewLifecycleOwner(), address -> {
            currentActiveEspIpForFragment = address;
            updateUiBasedOnActiveEsp();
            if (address != null && !address.isEmpty()) {
                // Automatically fetch when the fragment becomes visible with an active ESP
                fetchCurrentThresholdFromEsp(address);
            } else {
                 textViewCurrentEspThresholdValue.setText(R.string.status_not_available_no_esp);
                 editTextEspThreshold.setText("");
                 textViewNoiseSettingsStatus.setText("");
            }
        });
    }

    private void updateUiBasedOnActiveEsp() {
        boolean isActiveEspSelected = (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty());
        textInputLayoutEspThreshold.setEnabled(isActiveEspSelected);
        editTextEspThreshold.setEnabled(isActiveEspSelected);
        buttonSetEspThreshold.setEnabled(isActiveEspSelected);
        buttonRefreshEspThreshold.setEnabled(isActiveEspSelected);

        if (!isActiveEspSelected) {
            textViewCurrentEspThresholdValue.setText(R.string.status_not_available_no_esp);
            editTextEspThreshold.setText("");
            textViewNoiseSettingsStatus.setText(R.string.select_active_esp_prompt);
        }
    }

    private void fetchCurrentThresholdFromEsp(String espIp) {
        if (espIp == null || espIp.isEmpty() || isFetchingThreshold) {
            if(isFetchingThreshold) Log.d(TAG, "Already fetching threshold, skipping duplicate request.");
            return;
        }
        isFetchingThreshold = true;
        textViewNoiseSettingsStatus.setText(R.string.status_fetching_config);
        textViewCurrentEspThresholdValue.setText(R.string.status_fetching_ellipsis);

        // EspConfigClient expects full base URL, AppViewModel stores normalized address
        String baseUrl = "http://" + espIp;

        espConfigClient.getConfig(baseUrl, new EspConfigClient.ConfigCallback() {
            @Override
            public void onSuccess(String responseBody) {
                if (getActivity() == null) return; // Fragment not attached
                getActivity().runOnUiThread(() -> {
                    isFetchingThreshold = false;
                    try {
                        JSONObject config = new JSONObject(responseBody);
                        if (config.has("threshold_db_calibrated")) {
                            double threshold = config.getDouble("threshold_db_calibrated");
                            textViewCurrentEspThresholdValue.setText(getString(R.string.decibel_format_precise, threshold));
                            editTextEspThreshold.setText(String.format(Locale.US, "%.1f", threshold)); // US Locale for dot decimal
                            textViewNoiseSettingsStatus.setText(R.string.status_fetched_successfully);
                        } else {
                            textViewCurrentEspThresholdValue.setText(R.string.status_key_not_found);
                            textViewNoiseSettingsStatus.setText(R.string.status_config_key_missing_threshold);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing threshold from ESP config: " + responseBody, e);
                        textViewCurrentEspThresholdValue.setText(R.string.status_parse_error);
                        textViewNoiseSettingsStatus.setText(R.string.status_config_parse_error);
                    }
                });
            }

            @Override
            public void onFailure(IOException e) {
                handleFetchOrSetError(getString(R.string.status_fetch_failed_network, e.getMessage()), true);
            }

            @Override
            public void onError(String message, int code) {
                 handleFetchOrSetError(getString(R.string.status_fetch_failed_server, code, message), true);
            }
        });
    }

    private void setEspThresholdValue() {
        if (currentActiveEspIpForFragment == null || currentActiveEspIpForFragment.isEmpty()) {
            Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            return;
        }
        String thresholdStr = editTextEspThreshold.getText() != null ? editTextEspThreshold.getText().toString().trim() : "";
        if (TextUtils.isEmpty(thresholdStr)) {
            Toast.makeText(getContext(), R.string.enter_threshold_value_prompt, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            float thresholdValue = Float.parseFloat(thresholdStr);
            textViewNoiseSettingsStatus.setText(R.string.status_setting_threshold);

            // EspConfigClient expects full base URL
            String baseUrl = "http://" + currentActiveEspIpForFragment;

            espConfigClient.setThreshold(baseUrl, thresholdValue, new EspConfigClient.ConfigCallback() {
                @Override
                public void onSuccess(String responseBody) {
                    handleFetchOrSetError(getString(R.string.status_threshold_set_success), false);
                    // Re-fetch to confirm the change and update the displayed "current" value
                    fetchCurrentThresholdFromEsp(currentActiveEspIpForFragment);
                }

                @Override
                public void onFailure(IOException e) {
                    handleFetchOrSetError(getString(R.string.status_set_failed_network, e.getMessage()), true);
                }

                @Override
                public void onError(String message, int code) {
                    handleFetchOrSetError(getString(R.string.status_set_failed_server, code, message), true);
                }
            });
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), R.string.invalid_threshold_format_prompt, Toast.LENGTH_SHORT).show();
            textViewNoiseSettingsStatus.setText(R.string.status_invalid_format);
        }
    }

    private void handleFetchOrSetError(String message, boolean isFetchError) {
        if (getActivity() == null) return; // Fragment not attached
        getActivity().runOnUiThread(() -> {
            if(isFetchError) { // Only set isFetchingThreshold to false if it was a fetch operation
                isFetchingThreshold = false;
                textViewCurrentEspThresholdValue.setText(R.string.status_fetch_error_short);
            }
            textViewNoiseSettingsStatus.setText(message.substring(0, Math.min(message.length(),150))); // Show truncated message
            Log.e(TAG, "ESP operation error: " + message);
            Toast.makeText(getContext(), message.substring(0, Math.min(message.length(),100)), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh data if an ESP is active when fragment becomes visible
        if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
            fetchCurrentThresholdFromEsp(currentActiveEspIpForFragment);
        }
        updateUiBasedOnActiveEsp(); // Ensure UI state is correct
    }
}