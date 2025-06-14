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
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

public class EspConfigFragment extends Fragment {
    private static final String TAG = "EspConfigFragment_DBG";

    private AppViewModel appViewModel;
    private EspConfigClient espConfigClient;

    private TextInputEditText editTextCalibrationOffset, editTextWifiSsid, editTextWifiPassword;
    private TextInputLayout textInputLayoutCalibration, textInputLayoutSsid, textInputLayoutPassword;
    private Button buttonFetchEspConfig, buttonSetCalibration, buttonSetWifi;
    private TextView textViewEspConfigStatus;

    private String currentActiveEspIpForFragment = null;
    private boolean isOperating = false; // To prevent multiple simultaneous operations

    // To store fetched config to avoid re-parsing unless fetched again
    private JSONObject currentEspConfig = null;

    public EspConfigFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        espConfigClient = new EspConfigClient();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_esp_config, container, false);

        editTextCalibrationOffset = view.findViewById(R.id.editTextCalibrationOffset);
        editTextWifiSsid = view.findViewById(R.id.editTextWifiSsid);
        editTextWifiPassword = view.findViewById(R.id.editTextWifiPassword);

        textInputLayoutCalibration = view.findViewById(R.id.textInputLayoutCalibrationOffset);
        textInputLayoutSsid = view.findViewById(R.id.textInputLayoutWifiSsid);
        textInputLayoutPassword = view.findViewById(R.id.textInputLayoutWifiPassword);

        buttonFetchEspConfig = view.findViewById(R.id.buttonFetchEspConfig);
        buttonSetCalibration = view.findViewById(R.id.buttonSetCalibration);
        buttonSetWifi = view.findViewById(R.id.buttonSetWifi);
        textViewEspConfigStatus = view.findViewById(R.id.textViewEspConfigStatus);

        buttonFetchEspConfig.setOnClickListener(v -> {
            if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
                fetchFullEspConfig(currentActiveEspIpForFragment);
            } else {
                Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            }
        });
        buttonSetCalibration.setOnClickListener(v -> setEspCalibrationOffset());
        buttonSetWifi.setOnClickListener(v -> setEspWifiConfig());

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        appViewModel.getActiveEspAddressLiveData().observe(getViewLifecycleOwner(), address -> {
            currentActiveEspIpForFragment = address;
            updateUiBasedOnActiveEsp();
            if (address != null && !address.isEmpty()) {
                fetchFullEspConfig(address); // Auto-fetch on active ESP change or view creation
            } else {
                clearConfigFields();
                textViewEspConfigStatus.setText(R.string.select_active_esp_prompt);
            }
        });
    }

    private void updateUiBasedOnActiveEsp() {
        boolean isActiveEspSelected = (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty());
        buttonFetchEspConfig.setEnabled(isActiveEspSelected);

        textInputLayoutCalibration.setEnabled(isActiveEspSelected);
        editTextCalibrationOffset.setEnabled(isActiveEspSelected);
        buttonSetCalibration.setEnabled(isActiveEspSelected);

        textInputLayoutSsid.setEnabled(isActiveEspSelected);
        editTextWifiSsid.setEnabled(isActiveEspSelected);
        textInputLayoutPassword.setEnabled(isActiveEspSelected);
        editTextWifiPassword.setEnabled(isActiveEspSelected);
        buttonSetWifi.setEnabled(isActiveEspSelected);

        if (!isActiveEspSelected) {
            clearConfigFields();
            textViewEspConfigStatus.setText(R.string.select_active_esp_prompt);
            currentEspConfig = null; // Clear cached config
        }
    }

    private void clearConfigFields() {
        editTextCalibrationOffset.setText("");
        editTextWifiSsid.setText("");
        editTextWifiPassword.setText("");
    }

    private void populateFieldsFromConfig(JSONObject config) {
        if (config == null || getContext() == null) {
            clearConfigFields();
            return;
        }
        currentEspConfig = config; // Cache the fetched config

        editTextCalibrationOffset.setText(String.format(Locale.US, "%.1f", config.optDouble("calibration_offset", 0.0)));
        editTextWifiSsid.setText(config.optString("wifi_ssid", ""));
        // For security, don't pre-fill password from config unless explicitly desired for "show current"
        // editTextWifiPassword.setText(config.optString("wifi_password", ""));
        // Better to leave password blank for user to input if changing.
        editTextWifiPassword.setText(""); // Keep password field blank for new input
        // If you *want* to show the password (not recommended for actual passwords):
        // String storedPassword = config.optString("wifi_password", "");
        // editTextWifiPassword.setText(storedPassword.equals(DEFAULT_WIFI_PASSWORD_PLACEHOLDER) ? "" : storedPassword);
        // Where DEFAULT_WIFI_PASSWORD_PLACEHOLDER might be "YOUR_PASSWORD_HERE" if your ESP uses that.

        textViewEspConfigStatus.setText(getString(R.string.status_config_loaded_at, System.currentTimeMillis()));
    }

    private void fetchFullEspConfig(String espIp) {
        if (espIp == null || espIp.isEmpty() || isOperating) {
            if(isOperating) Log.d(TAG, "Operation in progress, skipping fetch request.");
            return;
        }
        isOperating = true;
        textViewEspConfigStatus.setText(R.string.status_fetching_config);
        clearConfigFields(); // Clear fields while fetching

        String baseUrl = "http://" + espIp; // EspConfigClient needs full URL
        espConfigClient.getConfig(baseUrl, new EspConfigClient.ConfigCallback() {
            @Override
            public void onSuccess(String responseBody) {
                if (getActivity() == null) { isOperating = false; return; }
                getActivity().runOnUiThread(() -> {
                    isOperating = false;
                    try {
                        JSONObject config = new JSONObject(responseBody);
                        populateFieldsFromConfig(config);
                        textViewEspConfigStatus.setText(R.string.status_config_fetched_ok);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing full ESP config JSON: " + responseBody, e);
                        textViewEspConfigStatus.setText(R.string.status_config_parse_error);
                        currentEspConfig = null;
                    }
                });
            }
            @Override
            public void onFailure(IOException e) {
                handleOperationError(getString(R.string.status_fetch_failed_network, e.getMessage()));
            }
            @Override
            public void onError(String message, int code) {
                handleOperationError(getString(R.string.status_fetch_failed_server, code, message));
            }
        });
    }

    private void setEspCalibrationOffset() {
        if (currentActiveEspIpForFragment == null || currentActiveEspIpForFragment.isEmpty() || isOperating) {
            if (isOperating) Toast.makeText(getContext(), R.string.operation_in_progress, Toast.LENGTH_SHORT).show();
            else Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            return;
        }
        String offsetStr = editTextCalibrationOffset.getText() != null ? editTextCalibrationOffset.getText().toString().trim() : "";
        if (TextUtils.isEmpty(offsetStr)) {
            Toast.makeText(getContext(), R.string.enter_calibration_offset_prompt, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            float offsetValue = Float.parseFloat(offsetStr);
            isOperating = true;
            textViewEspConfigStatus.setText(R.string.status_setting_calibration);
            String baseUrl = "http://" + currentActiveEspIpForFragment;
            espConfigClient.setCalibration(baseUrl, offsetValue, new EspConfigClient.ConfigCallback() {
                @Override public void onSuccess(String responseBody) {
                    handleOperationError(getString(R.string.status_calibration_set_success));
                    fetchFullEspConfig(currentActiveEspIpForFragment); // Re-fetch to confirm
                }
                @Override public void onFailure(IOException e) { handleOperationError(getString(R.string.status_set_failed_network, e.getMessage()));}
                @Override public void onError(String message, int code) { handleOperationError(getString(R.string.status_set_failed_server, code, message));}
            });
        } catch (NumberFormatException e) {
            Toast.makeText(getContext(), R.string.invalid_offset_format_prompt, Toast.LENGTH_SHORT).show();
            textViewEspConfigStatus.setText(R.string.status_invalid_format);
        }
    }

    private void setEspWifiConfig() {
        if (currentActiveEspIpForFragment == null || currentActiveEspIpForFragment.isEmpty() || isOperating) {
             if (isOperating) Toast.makeText(getContext(), R.string.operation_in_progress, Toast.LENGTH_SHORT).show();
            else Toast.makeText(getContext(), R.string.no_active_esp_for_action, Toast.LENGTH_SHORT).show();
            return;
        }
        String ssid = editTextWifiSsid.getText() != null ? editTextWifiSsid.getText().toString().trim() : "";
        String password = editTextWifiPassword.getText() != null ? editTextWifiPassword.getText().toString() : ""; // Password can be empty

        if (TextUtils.isEmpty(ssid)) {
            // Some ESPs might treat empty SSID as "disconnect" or "use saved default".
            // For explicit setting, usually requires a non-empty SSID.
            Toast.makeText(getContext(), R.string.enter_wifi_ssid_prompt, Toast.LENGTH_SHORT).show();
            return;
        }
        isOperating = true;
        textViewEspConfigStatus.setText(R.string.status_setting_wifi);
        String baseUrl = "http://" + currentActiveEspIpForFragment;
        espConfigClient.setWifiConfig(baseUrl, ssid, password, new EspConfigClient.ConfigCallback() {
            @Override public void onSuccess(String responseBody) {
                handleOperationError(getString(R.string.status_wifi_set_success));
                Toast.makeText(getContext(), R.string.esp_reboot_may_be_needed_wifi, Toast.LENGTH_LONG).show();
                // Don't auto-fetch immediately as ESP might be rebooting or changing IP
                // User should re-scan or re-select if ESP address changes.
                // Or, if IP is static, can try fetching after a delay.
                // For now, just inform user.
            }
            @Override public void onFailure(IOException e) { handleOperationError(getString(R.string.status_set_failed_network, e.getMessage()));}
            @Override public void onError(String message, int code) { handleOperationError(getString(R.string.status_set_failed_server, code, message));}
        });
    }


    private void handleOperationError(String message) {
        if (getActivity() == null) { isOperating = false; return;}
        getActivity().runOnUiThread(() -> {
            isOperating = false;
            textViewEspConfigStatus.setText(message.substring(0, Math.min(message.length(),150)));
            Log.d(TAG, "ESP Config operation status: " + message);
            Toast.makeText(getContext(), message.substring(0, Math.min(message.length(),100)), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh data if an ESP is active when fragment becomes visible
        if (currentActiveEspIpForFragment != null && !currentActiveEspIpForFragment.isEmpty()) {
            fetchFullEspConfig(currentActiveEspIpForFragment);
        }
        updateUiBasedOnActiveEsp();
    }
}