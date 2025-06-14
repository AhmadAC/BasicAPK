package com.example.mybasicapp.fragments;

import android.content.Context;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.mybasicapp.R;
import com.example.mybasicapp.adapters.EspDeviceAdapter; // We'll create this adapter
import com.example.mybasicapp.DiscoveredService; // Already exists - CORRECTED IMPORT
import com.example.mybasicapp.model.EspDevice;    // Already exists
import com.example.mybasicapp.NsdHelper;  // Already exists - CORRECTED IMPORT
import com.example.mybasicapp.viewmodels.AppViewModel;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class DeviceManagementFragment extends Fragment implements NsdHelper.NsdHelperListener, EspDeviceAdapter.OnEspDeviceInteractionListener {
    private static final String TAG = "DeviceMgmtFrag_DBG";

    private AppViewModel appViewModel;
    private EspDeviceAdapter espDeviceAdapter;
    private RecyclerView recyclerViewManagedEsps;
    private Button buttonScanNetwork, buttonAddEspManual, buttonSaveDynamicList;
    private TextInputEditText editTextNewEspAddressManual; // For single manual add
    private TextView textViewCurrentActiveEspDisplay, textViewNsdStatus;

    // For dynamic number of ESP inputs
    private Spinner spinnerNumEsps;
    private LinearLayout layoutEspAddressInputsContainer; // Container for dynamic EditTexts
    private List<TextInputEditText> dynamicEspAddressInputs = new ArrayList<>();


    // NSD (Network Service Discovery)
    private NsdHelper nsdHelper;
    private EspDeviceAdapter discoveredServicesAdapter; // Separate adapter for discovered services
    private RecyclerView recyclerViewDiscoveredNsdServices;
    private static final String ESP_HTTP_SERVICE_TYPE = "_http._tcp"; // Or your specific ESP service type
    private static final String ESP_SERVICE_NAME_FILTER = "mrcoopersesp"; // Optional filter
    private static final long NSD_DISCOVERY_TIMEOUT_MS = 15000; // 15 seconds
    private Handler discoveryTimeoutHandler = new Handler(Looper.getMainLooper());


    public DeviceManagementFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appViewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        nsdHelper = new NsdHelper(requireContext(), this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_device_management, container, false);

        // Single Manual Add UI
        editTextNewEspAddressManual = view.findViewById(R.id.editTextNewEspAddressManual);
        buttonAddEspManual = view.findViewById(R.id.buttonAddEspManual);

        // RecyclerView for Managed ESPs
        recyclerViewManagedEsps = view.findViewById(R.id.recyclerViewManagedEsps);
        recyclerViewManagedEsps.setLayoutManager(new LinearLayoutManager(getContext()));
        espDeviceAdapter = new EspDeviceAdapter(new ArrayList<>(), this, appViewModel);
        recyclerViewManagedEsps.setAdapter(espDeviceAdapter);

        textViewCurrentActiveEspDisplay = view.findViewById(R.id.textViewCurrentActiveEspDisplay);

        // NSD UI
        buttonScanNetwork = view.findViewById(R.id.buttonScanNetwork);
        textViewNsdStatus = view.findViewById(R.id.textViewNsdStatus);
        recyclerViewDiscoveredNsdServices = view.findViewById(R.id.recyclerViewDiscoveredNsdServices);
        recyclerViewDiscoveredNsdServices.setLayoutManager(new LinearLayoutManager(getContext()));
        // Discovered services adapter needs a different listener logic or a way to add to managed list
        discoveredServicesAdapter = new EspDeviceAdapter(new ArrayList<>(), new EspDeviceAdapter.OnEspDeviceInteractionListener() {
            @Override
            public void onSetActive(EspDevice device) { // Tapping a discovered device adds it to managed list and sets active
                appViewModel.addEspDevice(new EspDevice(device.getName(), device.getAddress())); // Use name from discovery
                appViewModel.setActiveEspAddress(device.getAddress());
                Toast.makeText(getContext(), device.getName() + " added and set active.", Toast.LENGTH_SHORT).show();
                if (nsdHelper.isDiscoveryActive()) nsdHelper.stopDiscovery(); // Stop discovery after selection
                discoveredServicesAdapter.clearDevices(); // Clear discovered list after selection
            }
            @Override public void onEditDevice(EspDevice device, int position) { /* Not used for discovered list */ }
            @Override public void onDeleteDevice(EspDevice device, int position) { /* Not used for discovered list */ }
        }, appViewModel); // Pass ViewModel to highlight active one if it matches
        recyclerViewDiscoveredNsdServices.setAdapter(discoveredServicesAdapter);

        // Dynamic ESP Inputs UI
        spinnerNumEsps = view.findViewById(R.id.spinnerNumEsps);
        layoutEspAddressInputsContainer = view.findViewById(R.id.layoutEspAddressInputsContainer);
        buttonSaveDynamicList = view.findViewById(R.id.buttonSaveDynamicList);
        setupSpinnerNumEsps();


        buttonAddEspManual.setOnClickListener(v -> addManualEspDevice());
        buttonScanNetwork.setOnClickListener(v -> toggleNsdDiscovery());
        buttonSaveDynamicList.setOnClickListener(v -> saveDynamicEspList());

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        appViewModel.getEspDevicesLiveData().observe(getViewLifecycleOwner(), devices -> {
            Log.d(TAG, "Managed ESPs list updated. Count: " + (devices != null ? devices.size() : 0));
            espDeviceAdapter.updateDevices(devices != null ? new ArrayList<>(devices) : new ArrayList<>());
            // Update dynamic inputs if the number of devices from ViewModel matches spinner and inputs are empty
            // This is to prefill if app restarts and data was loaded
             if (devices != null && spinnerNumEsps.getSelectedItemPosition() == devices.size() && allDynamicInputsEmpty()) {
                populateDynamicInputsFromList(devices);
            }
        });

        appViewModel.getActiveEspAddressLiveData().observe(getViewLifecycleOwner(), activeAddress -> {
            Log.d(TAG, "Active ESP address updated to: " + activeAddress);
            if (activeAddress != null && !activeAddress.isEmpty()) {
                textViewCurrentActiveEspDisplay.setText(getString(R.string.currently_active_esp_display, activeAddress));
            } else {
                textViewCurrentActiveEspDisplay.setText(R.string.no_active_esp_selected_display);
            }
            espDeviceAdapter.setActiveEspAddress(activeAddress); // Notify adapter to re-bind for highlight
            discoveredServicesAdapter.setActiveEspAddress(activeAddress); // Also for discovered list
        });
    }
    
    private boolean allDynamicInputsEmpty() {
        for (TextInputEditText editText : dynamicEspAddressInputs) {
            if (editText.getText() != null && !editText.getText().toString().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void populateDynamicInputsFromList(List<EspDevice> devices) {
        if (devices.size() == dynamicEspAddressInputs.size()) {
            for (int i = 0; i < devices.size(); i++) {
                dynamicEspAddressInputs.get(i).setText(devices.get(i).getAddress());
            }
        }
    }


    private void setupSpinnerNumEsps() {
        Integer[] numbers = new Integer[11]; // 0 to 10 ESPs
        for (int i = 0; i <= 10; i++) {
            numbers[i] = i;
        }
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, numbers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerNumEsps.setAdapter(adapter);

        // Load initial number of ESPs based on current ViewModel list size, if not 0
        List<EspDevice> currentDevices = appViewModel.getEspDevicesLiveData().getValue();
        if (currentDevices != null && !currentDevices.isEmpty() && currentDevices.size() <= 10) {
            spinnerNumEsps.setSelection(currentDevices.size());
            generateEspAddressInputs(currentDevices.size()); // Generate inputs
            populateDynamicInputsFromList(currentDevices); // Pre-fill them
        } else {
            spinnerNumEsps.setSelection(0); // Default to 0
            generateEspAddressInputs(0);
        }


        spinnerNumEsps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int numEsps = (Integer) parent.getItemAtPosition(position);
                Log.d(TAG, "Spinner selected: " + numEsps + " ESPs");
                generateEspAddressInputs(numEsps);
                // Try to prefill if the number matches the current device list from ViewModel
                List<EspDevice> devices = appViewModel.getEspDevicesLiveData().getValue();
                if (devices != null && devices.size() == numEsps) {
                    populateDynamicInputsFromList(devices);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void generateEspAddressInputs(int numEsps) {
        layoutEspAddressInputsContainer.removeAllViews();
        dynamicEspAddressInputs.clear();
        LayoutInflater inflater = LayoutInflater.from(getContext());

        for (int i = 0; i < numEsps; i++) {
            // Inflate a standard TextInputLayout with TextInputEditText
            // Or define a custom layout row: R.layout.list_item_esp_input_field
            TextInputLayout textInputLayout = (TextInputLayout) inflater.inflate(R.layout.dynamic_esp_input_item, layoutEspAddressInputsContainer, false);
            TextInputEditText editText = textInputLayout.findViewById(R.id.editTextDynamicEspAddress); // Ensure this ID exists in your item layout

            textInputLayout.setHint(getString(R.string.esp_address_input_hint_dynamic, i + 1));
            dynamicEspAddressInputs.add(editText);
            layoutEspAddressInputsContainer.addView(textInputLayout);
        }
        buttonSaveDynamicList.setVisibility(numEsps > 0 ? View.VISIBLE : View.GONE);
    }

    private void saveDynamicEspList() {
        List<EspDevice> newDeviceList = new ArrayList<>();
        boolean allValid = true;
        for (int i = 0; i < dynamicEspAddressInputs.size(); i++) {
            TextInputEditText editText = dynamicEspAddressInputs.get(i);
            String address = editText.getText() != null ? editText.getText().toString().trim() : "";
            if (TextUtils.isEmpty(address)) {
                // Allow empty fields if user wants fewer than selected in spinner initially
                // Or enforce:
                // editText.setError("Address cannot be empty");
                // allValid = false;
                // break;
                Log.d(TAG, "Dynamic input " + (i+1) + " is empty, skipping.");
                continue;
            }
            // Basic validation (e.g. not just spaces, could add regex for IP/hostname)
            if (address.matches("\\s+")) { // Contains only whitespace
                editText.setError("Invalid address format");
                allValid = false;
                break;
            }
            newDeviceList.add(new EspDevice(address)); // Name defaults to address
        }

        if (allValid) {
            appViewModel.setEspDevicesList(newDeviceList); // This will update LiveData and save
            Toast.makeText(getContext(), "ESP list updated from dynamic inputs.", Toast.LENGTH_SHORT).show();
            // Optionally, if the list is not empty and no active ESP, set the first one active
            if (!newDeviceList.isEmpty() && (appViewModel.getActiveEspAddressLiveData().getValue() == null || appViewModel.getActiveEspAddressLiveData().getValue().isEmpty())) {
                appViewModel.setActiveEspAddress(newDeviceList.get(0).getAddress());
            }
        } else {
            Toast.makeText(getContext(), "Please correct the errors in ESP addresses.", Toast.LENGTH_LONG).show();
        }
    }


    private void addManualEspDevice() {
        String address = editTextNewEspAddressManual.getText() != null ? editTextNewEspAddressManual.getText().toString().trim() : "";
        if (TextUtils.isEmpty(address)) {
            editTextNewEspAddressManual.setError(getString(R.string.esp_address_empty_error));
            return;
        }
        // Basic validation - could be more complex (regex for IP/hostname)
        if (address.contains(" ") || !address.matches("^[\\w.-]+$")) { // Simple check for invalid chars
            editTextNewEspAddressManual.setError(getString(R.string.esp_address_invalid_format_error));
            return;
        }

        EspDevice newDevice = new EspDevice(address); // Name defaults to address
        appViewModel.addEspDevice(newDevice); // ViewModel handles duplicates and saving
        editTextNewEspAddressManual.setText(""); // Clear input field
        Toast.makeText(getContext(), getString(R.string.device_added_toast, address), Toast.LENGTH_SHORT).show();
    }

    private void toggleNsdDiscovery() {
        if (nsdHelper.isDiscoveryActive()) {
            nsdHelper.stopDiscovery();
            // UI update (button text, status) is handled by onNsdDiscoveryLifecycleChange
        } else {
            discoveredServicesAdapter.clearDevices(); // Clear previous scan results
            textViewNsdStatus.setText(R.string.nsd_status_scanning);
            nsdHelper.discoverServices(ESP_SERVICE_NAME_FILTER, ESP_HTTP_SERVICE_TYPE);
            buttonScanNetwork.setText(R.string.stop_network_scan_button);
            // Start timeout for discovery
            discoveryTimeoutHandler.postDelayed(discoveryTimeoutRunnable, NSD_DISCOVERY_TIMEOUT_MS);
        }
    }

    private final Runnable discoveryTimeoutRunnable = () -> {
        if (nsdHelper.isDiscoveryActive()) {
            Log.w(TAG, "NSD Discovery timed out.");
            nsdHelper.stopDiscovery(); // This will trigger onNsdDiscoveryLifecycleChange
            Toast.makeText(getContext(), R.string.nsd_scan_timed_out, Toast.LENGTH_SHORT).show();
        }
    };

    // --- EspDeviceAdapter.OnEspDeviceInteractionListener Callbacks ---
    @Override
    public void onSetActive(EspDevice device) {
        appViewModel.setActiveEspAddress(device.getAddress());
        Toast.makeText(getContext(), getString(R.string.device_set_active_toast, device.getName()), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEditDevice(EspDevice device, int position) {
        if (getContext() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.edit_device_dialog_title, device.getName()));

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_esp_device, null);
        EditText editTextName = dialogView.findViewById(R.id.editTextDialogEspName);
        EditText editTextAddress = dialogView.findViewById(R.id.editTextDialogEspAddress);

        editTextName.setText(device.getName());
        editTextAddress.setText(device.getAddress());
        builder.setView(dialogView);

        builder.setPositiveButton(R.string.save_button_label, (dialog, which) -> {
            String newName = editTextName.getText().toString().trim();
            String newAddress = editTextAddress.getText().toString().trim();
            if (TextUtils.isEmpty(newName) || TextUtils.isEmpty(newAddress)) {
                Toast.makeText(getContext(), R.string.name_address_cannot_be_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            EspDevice updatedDevice = new EspDevice(newName, newAddress);
            appViewModel.updateEspDevice(position, updatedDevice); // ViewModel handles saving
        });
        builder.setNegativeButton(R.string.cancel_button_label, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    @Override
    public void onDeleteDevice(EspDevice device, int position) {
        appViewModel.removeEspDevice(device); // ViewModel handles saving
        Toast.makeText(getContext(), getString(R.string.device_deleted_toast, device.getName()), Toast.LENGTH_SHORT).show();
    }


    // --- NsdHelper.NsdHelperListener Callbacks ---
    @Override
    public void onNsdServiceCandidateFound(NsdServiceInfo serviceInfo) {
        // Not directly used if resolve queue handles it, but good for logging
        Log.i(TAG, "NSD Candidate Found: " + serviceInfo.getServiceName());
    }

    @Override
    public void onNsdServiceResolved(DiscoveredService service) {
        // NsdServiceInfo comes from NSD, DiscoveredService is our model wrapper
        // The NsdHelper has already converted NsdServiceInfo to DiscoveredService
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Log.i(TAG, "NSD Service Resolved: Name='" + service.getServiceName() + "', Host='" + service.getHostAddress() + ":" + service.getPort() + "'");
            // Add to the discovered services RecyclerView
            // The EspDevice constructor will use service.getHostAddress() as both name and address if only address is needed
            // If service.getServiceName() is more user-friendly, use that for name.
            EspDevice discoveredEsp = new EspDevice(service.getServiceName(), service.getHostAddress()); // Assume port 80 or client handles port
            discoveredServicesAdapter.addDevice(discoveredEsp);
            textViewNsdStatus.setText(getString(R.string.nsd_status_resolved_found, discoveredServicesAdapter.getItemCount()));
        });
    }

    @Override
    public void onNsdServiceLost(DiscoveredService service) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Log.w(TAG, "NSD Service Lost: " + service.getServiceName());
            EspDevice lostEsp = new EspDevice(service.getServiceName(), service.getHostAddress());
            discoveredServicesAdapter.removeDevice(lostEsp);
            Toast.makeText(getContext(), getString(R.string.nsd_service_lost_toast, service.getServiceName()), Toast.LENGTH_SHORT).show();
            if (discoveredServicesAdapter.getItemCount() == 0 && !nsdHelper.isDiscoveryActive()) {
                 textViewNsdStatus.setText(R.string.nsd_status_idle_no_services);
            }
        });
    }

    @Override
    public void onNsdDiscoveryFailed(String serviceType, int errorCode) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Log.e(TAG, "NSD Discovery Failed: type=" + serviceType + ", errorCode=" + errorCode);
            textViewNsdStatus.setText(getString(R.string.nsd_status_discovery_failed, errorCode));
            buttonScanNetwork.setText(R.string.start_network_scan_button);
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable); // Stop timeout
            Toast.makeText(getContext(), getString(R.string.nsd_discovery_failed_toast, errorCode), Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Log.e(TAG, "NSD Resolve Failed: Service='" + serviceInfo.getServiceName() + "', errorCode=" + errorCode);
            textViewNsdStatus.setText(getString(R.string.nsd_status_resolve_failed, serviceInfo.getServiceName()));
            // No Toast here as it can be noisy if many services fail to resolve quickly.
        });
    }

    @Override
    public void onNsdDiscoveryLifecycleChange(boolean active, String serviceType) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Log.i(TAG, "NSD Discovery Lifecycle: " + (active ? "STARTED" : "STOPPED") + " for type " + serviceType);
            if (active) {
                textViewNsdStatus.setText(R.string.nsd_status_scanning);
                buttonScanNetwork.setText(R.string.stop_network_scan_button);
            } else {
                buttonScanNetwork.setText(R.string.start_network_scan_button);
                discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable); // Stop timeout
                if (discoveredServicesAdapter.getItemCount() == 0) {
                    textViewNsdStatus.setText(R.string.nsd_status_stopped_no_services);
                } else {
                    textViewNsdStatus.setText(getString(R.string.nsd_status_stopped_found, discoveredServicesAdapter.getItemCount()));
                }
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Stopping NSD discovery if active.");
        if (nsdHelper.isDiscoveryActive()) {
            nsdHelper.stopDiscovery();
        }
        discoveryTimeoutHandler.removeCallbacks(discoveryTimeoutRunnable); // Clear timeout
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: Tearing down NsdHelper.");
        if (nsdHelper != null) {
            nsdHelper.tearDown(); // Properly release NSD resources
        }
        discoveryTimeoutHandler.removeCallbacksAndMessages(null); // Clean up handler
    }
}