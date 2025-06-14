package com.example.mybasicapp.viewmodels;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.mybasicapp.model.EspDevice; // We will create this POJO next

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AppViewModel extends AndroidViewModel {
    private static final String TAG = "AppViewModel_DBG";

    // SharedPreferences for AppViewModel specific data (distinct from service or fragment prefs if needed)
    private static final String PREFS_APP_VIEW_MODEL = "AppViewModelPrefs";
    private static final String PREF_ESP_DEVICES_LIST = "esp_devices_list_v2"; // Changed key to avoid conflict with old format
    private static final String PREF_ACTIVE_ESP_ADDRESS = "active_esp_address_v2";
    private static final String PREF_LAST_SERVICE_STATUS = "last_service_status";
    private static final String PREF_LAST_SENSOR_JSON_DATA = "last_sensor_json_data";


    private final MutableLiveData<List<EspDevice>> espDevicesLiveData = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> activeEspAddressLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> lastServiceStatusLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> lastSensorJsonDataLiveData = new MutableLiveData<>(); // To hold raw JSON from ESP

    private final SharedPreferences sharedPreferences;

    public AppViewModel(@NonNull Application application) {
        super(application);
        sharedPreferences = application.getSharedPreferences(PREFS_APP_VIEW_MODEL, Context.MODE_PRIVATE);
        loadEspDevicesFromPrefs();
        loadActiveEspAddressFromPrefs();
        loadLastServiceStatusFromPrefs();
        loadLastSensorDataFromPrefs();
    }

    // --- ESP Devices List ---
    public LiveData<List<EspDevice>> getEspDevicesLiveData() {
        return espDevicesLiveData;
    }

    public void addEspDevice(EspDevice device) {
        List<EspDevice> currentList = espDevicesLiveData.getValue();
        if (currentList == null) {
            currentList = new ArrayList<>();
        }
        // Avoid duplicates based on address
        boolean exists = false;
        for (EspDevice existingDevice : currentList) {
            if (existingDevice.getAddress().equalsIgnoreCase(device.getAddress())) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            currentList.add(device);
            espDevicesLiveData.setValue(new ArrayList<>(currentList)); // Post a new list to trigger observers
            saveEspDevicesToPrefs();
            // If this is the first device added and no active ESP, make it active
            if (currentList.size() == 1 && (activeEspAddressLiveData.getValue() == null || activeEspAddressLiveData.getValue().isEmpty())) {
                setActiveEspAddress(device.getAddress());
            }
        } else {
            Log.d(TAG, "Device with address " + device.getAddress() + " already exists.");
        }
    }

    public void updateEspDevice(int index, EspDevice device) {
        List<EspDevice> currentList = espDevicesLiveData.getValue();
        if (currentList != null && index >= 0 && index < currentList.size()) {
            // Check if new address conflicts with an existing different device
            for (int i=0; i < currentList.size(); i++) {
                if (i != index && currentList.get(i).getAddress().equalsIgnoreCase(device.getAddress())) {
                    Log.w(TAG, "Cannot update device at index " + index + ": new address " + device.getAddress() + " conflicts with existing device.");
                    // Optionally notify UI about the conflict
                    return;
                }
            }
            currentList.set(index, device);
            espDevicesLiveData.setValue(new ArrayList<>(currentList));
            saveEspDevicesToPrefs();
        }
    }


    public void removeEspDevice(EspDevice deviceToRemove) {
        List<EspDevice> currentList = espDevicesLiveData.getValue();
        if (currentList != null) {
            boolean removed = currentList.removeIf(espDevice -> espDevice.getAddress().equalsIgnoreCase(deviceToRemove.getAddress()));
            if (removed) {
                espDevicesLiveData.setValue(new ArrayList<>(currentList));
                saveEspDevicesToPrefs();
                // If the removed device was the active one, clear active or select another
                if (Objects.equals(activeEspAddressLiveData.getValue(), deviceToRemove.getAddress())) {
                    setActiveEspAddress(currentList.isEmpty() ? null : currentList.get(0).getAddress());
                }
            }
        }
    }
    
    public void setEspDevicesList(List<EspDevice> newDevicesList) {
        // Ensure no duplicate addresses in the new list
        List<EspDevice> uniqueList = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        for (EspDevice device : newDevicesList) {
            if (!addresses.contains(device.getAddress().toLowerCase())) {
                uniqueList.add(device);
                addresses.add(device.getAddress().toLowerCase());
            }
        }
        espDevicesLiveData.setValue(uniqueList);
        saveEspDevicesToPrefs();

        // Check if current active ESP is still in the new list
        String currentActive = activeEspAddressLiveData.getValue();
        if (currentActive != null) {
            boolean activeFound = false;
            for (EspDevice device : uniqueList) {
                if (device.getAddress().equalsIgnoreCase(currentActive)) {
                    activeFound = true;
                    break;
                }
            }
            if (!activeFound) {
                setActiveEspAddress(uniqueList.isEmpty() ? null : uniqueList.get(0).getAddress());
            }
        } else if (!uniqueList.isEmpty()) {
            setActiveEspAddress(uniqueList.get(0).getAddress());
        }
    }


    private void saveEspDevicesToPrefs() {
        List<EspDevice> currentList = espDevicesLiveData.getValue();
        if (currentList == null) return;
        JSONArray jsonArray = new JSONArray();
        for (EspDevice device : currentList) {
            jsonArray.put(device.toJson());
        }
        sharedPreferences.edit().putString(PREF_ESP_DEVICES_LIST, jsonArray.toString()).apply();
        Log.d(TAG, "Saved ESP devices to prefs: " + jsonArray.toString());
    }

    private void loadEspDevicesFromPrefs() {
        String jsonString = sharedPreferences.getString(PREF_ESP_DEVICES_LIST, "[]");
        List<EspDevice> loadedList = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(jsonString);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                loadedList.add(EspDevice.fromJson(jsonObject));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error loading ESP devices from prefs: " + e.getMessage(), e);
        }
        espDevicesLiveData.setValue(loadedList);
        Log.d(TAG, "Loaded ESP devices from prefs. Count: " + loadedList.size());
    }

    // --- Active ESP Address ---
    public LiveData<String> getActiveEspAddressLiveData() {
        return activeEspAddressLiveData;
    }

    public void setActiveEspAddress(String address) {
        // Normalize address: remove http/https schema for consistency if present
        String normalizedAddress = address;
        if (normalizedAddress != null) {
            normalizedAddress = normalizedAddress.replaceFirst("^(http://|https://)", "");
        }

        if (!Objects.equals(activeEspAddressLiveData.getValue(), normalizedAddress)) {
            activeEspAddressLiveData.setValue(normalizedAddress);
            saveActiveEspAddressToPrefs(normalizedAddress);
            Log.i(TAG, "Active ESP address changed to: " + normalizedAddress);
        }
    }

    private void saveActiveEspAddressToPrefs(String address) {
        sharedPreferences.edit().putString(PREF_ACTIVE_ESP_ADDRESS, address).apply();
    }

    private void loadActiveEspAddressFromPrefs() {
        activeEspAddressLiveData.setValue(sharedPreferences.getString(PREF_ACTIVE_ESP_ADDRESS, null));
        Log.d(TAG, "Loaded active ESP address from prefs: " + activeEspAddressLiveData.getValue());
    }

    // --- Last Service Status ---
    public LiveData<String> getLastServiceStatusLiveData() {
        return lastServiceStatusLiveData;
    }

    public void setLastServiceStatus(String status) {
        lastServiceStatusLiveData.setValue(status);
        saveLastServiceStatusToPrefs(status);
    }

    private void saveLastServiceStatusToPrefs(String status) {
        sharedPreferences.edit().putString(PREF_LAST_SERVICE_STATUS, status).apply();
    }

    private void loadLastServiceStatusFromPrefs() {
        lastServiceStatusLiveData.setValue(sharedPreferences.getString(PREF_LAST_SERVICE_STATUS, "Service status unknown."));
    }

    // --- Last Sensor JSON Data ---
    public LiveData<String> getLastSensorJsonDataLiveData() {
        return lastSensorJsonDataLiveData;
    }

    public void setLastSensorJsonData(String jsonData) {
        lastSensorJsonDataLiveData.setValue(jsonData);
        saveLastSensorDataToPrefs(jsonData);
    }
    private void saveLastSensorDataToPrefs(String jsonData) {
        sharedPreferences.edit().putString(PREF_LAST_SENSOR_JSON_DATA, jsonData).apply();
    }
    private void loadLastSensorDataFromPrefs() {
        lastSensorJsonDataLiveData.setValue(sharedPreferences.getString(PREF_LAST_SENSOR_JSON_DATA, null));
    }
}