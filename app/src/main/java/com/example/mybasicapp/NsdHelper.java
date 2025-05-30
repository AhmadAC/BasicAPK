package com.example.mybasicapp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

public class NsdHelper {

    private static final String TAG = "NsdHelper";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager.ResolveListener currentResolveListener;

    private boolean discoveryActive = false;
    private String serviceNameFilter; // To store the specific service name we are looking for
    private String currentServiceTypeToDiscover; // To store the type of service we are currently looking for

    // Callbacks for the client (e.g., MainActivity)
    public interface NsdHelperListener {
        void onNsdServiceResolved(NsdServiceInfo serviceInfo, String serviceTypeDiscovered); // Pass back type
        void onNsdServiceLost(NsdServiceInfo serviceInfo);
        void onNsdDiscoveryFailed(String serviceType, int errorCode);
        void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onNsdServiceFound(NsdServiceInfo serviceInfo); // Candidate found
        void onNsdDiscoveryStarted(String serviceType); // Pass the type discovery started for
        void onNsdDiscoveryStopped(String serviceType); // Pass the type discovery stopped for
    }

    private NsdHelperListener listener;

    public NsdHelper(Context context, NsdHelperListener listener) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    private void initializeDiscoveryListener() {
        // This listener is re-used, so it's initialized once.
        // The filtering logic inside onServiceFound will use currentServiceTypeToDiscover.
        if (discoveryListener != null) {
            Log.d(TAG, "Discovery listener already initialized.");
            return;
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.i(TAG, "NSD Service discovery started for type: " + regType);
                discoveryActive = true;
                if (listener != null) listener.onNsdDiscoveryStarted(regType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "NSD Raw Service candidate found: Name='" + service.getServiceName() + "', Type='" + service.getServiceType() + "'");
                if (listener != null) {
                    listener.onNsdServiceFound(service); // Notify MainActivity about any found service
                }

                // Ensure currentServiceTypeToDiscover is not null before using substring
                if (currentServiceTypeToDiscover == null || currentServiceTypeToDiscover.isEmpty()) {
                    Log.e(TAG, "currentServiceTypeToDiscover is null or empty. Cannot filter by type.");
                    return;
                }
                // The service type from NsdServiceInfo might include ".local." at the end.
                // The currentServiceTypeToDiscover we pass might or might not have the trailing dot.
                // NsdManager expects types like "_http._tcp" or "_myespwebsocket._tcp" (without final dot for registration, with for discovery)
                // For comparison, it's safer to check if the found service type *starts with* our base type.
                String baseServiceTypeToDiscover = currentServiceTypeToDiscover.endsWith(".") ?
                                                   currentServiceTypeToDiscover.substring(0, currentServiceTypeToDiscover.length() -1) :
                                                   currentServiceTypeToDiscover;


                if (service.getServiceType() != null && service.getServiceType().toLowerCase().startsWith(baseServiceTypeToDiscover.toLowerCase())) {
                    // If a serviceNameFilter is set, ensure the found service name matches it.
                    if (serviceNameFilter != null && !serviceNameFilter.isEmpty() &&
                        !service.getServiceName().equalsIgnoreCase(serviceNameFilter)) {
                        Log.d(TAG, "Service candidate '" + service.getServiceName() + "' matches type but not name filter '" + serviceNameFilter + "'. Ignoring for resolution.");
                        return;
                    }

                    Log.i(TAG, "Matching service type (and name if filtered specifically) found: '" + service.getServiceName() + "'. Attempting to resolve.");

                    if (currentResolveListener != null) {
                        // While NsdManager doesn't have a direct "cancel resolve",
                        // creating a new listener for a new resolve request effectively supersedes the old one.
                        Log.d(TAG, "Previous resolve listener existed. A new resolve will use a new listener.");
                    }
                    currentResolveListener = new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.e(TAG, "NSD Resolve FAILED for service: '" + serviceInfo.getServiceName() + "', Error code: " + errorCode);
                            if (listener != null) listener.onNsdResolveFailed(serviceInfo, errorCode);
                            currentResolveListener = null;
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            Log.i(TAG, "NSD Service RESOLVED: Name='" + serviceInfo.getServiceName() + "', Host='" + serviceInfo.getHost() + "', Port='" + serviceInfo.getPort() + "'");
                            if (listener != null) listener.onNsdServiceResolved(serviceInfo, currentServiceTypeToDiscover); // Pass back the originally requested type
                            currentResolveListener = null;
                        }
                    };
                    nsdManager.resolveService(service, currentResolveListener);
                } else {
                     Log.d(TAG, "Service '" + service.getServiceName() + "' with type '" + service.getServiceType() + "' does not match expected type prefix '" + baseServiceTypeToDiscover + "'. Ignoring resolution.");
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.w(TAG, "NSD Service lost: Name='" + service.getServiceName() + "', Type='" + service.getServiceType() + "'");
                if (listener != null) listener.onNsdServiceLost(service);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "NSD Service discovery stopped for type: " + serviceType);
                discoveryActive = false; // This callback confirms discovery has stopped
                if (listener != null) listener.onNsdDiscoveryStopped(serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "NSD Service discovery start FAILED for type '" + serviceType + "', Error code: " + errorCode);
                discoveryActive = false;
                if (listener != null) listener.onNsdDiscoveryFailed(serviceType, errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "NSD Service discovery stop FAILED for type '" + serviceType + "', Error code: " + errorCode);
                // This can happen if stopServiceDiscovery is called when discovery was not running
                // or if the listener wasn't the one registered.
                // We should ensure our 'discoveryActive' flag is consistent.
                discoveryActive = false; // Assume it's stopped if stop failed.
                 if (listener != null) {
                    // Optionally, notify listener or just log.
                    // listener.onNsdDiscoveryStopped(serviceType); // Or a specific error callback for this
                 }
            }
        };
    }

    public void discoverServices(String targetServiceNameFilter, String serviceTypeToScan) {
        if (serviceTypeToScan == null || serviceTypeToScan.isEmpty()) {
            Log.e(TAG, "Service type to scan cannot be null or empty.");
            if (listener != null) listener.onNsdDiscoveryFailed("", NsdManager.FAILURE_BAD_PARAMETERS);
            return;
        }

        if (discoveryActive) {
            Log.d(TAG, "Discovery already active for " + currentServiceTypeToDiscover + ". Stopping first.");
            stopDiscovery(); // Stop previous before starting new, to ensure clean state.
            // Consider a small delay or a callback mechanism if stopDiscovery is asynchronous and you need to wait.
            // For now, we'll proceed assuming stopDiscovery acts relatively quickly or next discoverServices call handles it.
        }

        this.serviceNameFilter = targetServiceNameFilter;
        this.currentServiceTypeToDiscover = serviceTypeToScan.endsWith(".") ? serviceTypeToScan : serviceTypeToScan + "."; // Ensure trailing dot for discovery type

        if (discoveryListener == null) { // Initialize if not already
            initializeDiscoveryListener();
        }
         if (discoveryListener == null) { // Should not happen if initializeDiscoveryListener works
             Log.e(TAG, "Cannot start discovery: DiscoveryListener is null post-init.");
             if (listener != null) listener.onNsdDiscoveryFailed(currentServiceTypeToDiscover, -1 /* custom error */);
             return;
        }

        Log.d(TAG, "Attempting to start NSD service discovery for type: " + currentServiceTypeToDiscover +
                (serviceNameFilter != null && !serviceNameFilter.isEmpty() ? " with name filter: " + serviceNameFilter : ""));
        try {
            nsdManager.discoverServices(currentServiceTypeToDiscover, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            // onDiscoveryStarted callback will set discoveryActive = true;
        } catch (Exception e) {
            Log.e(TAG, "Exception during nsdManager.discoverServices call: " + e.getMessage(), e);
            discoveryActive = false; // Ensure it's false if call fails
            if (listener != null) listener.onNsdDiscoveryFailed(currentServiceTypeToDiscover, NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    public void stopDiscovery() {
        if (discoveryListener != null && discoveryActive) {
            try {
                Log.d(TAG, "Attempting to stop NSD service discovery for type: " + currentServiceTypeToDiscover);
                nsdManager.stopServiceDiscovery(discoveryListener);
                // onDiscoveryStopped callback will set discoveryActive = false;
            } catch (IllegalArgumentException e) {
                // This can happen if the listener was already unregistered or never registered.
                Log.w(TAG, "Error stopping discovery (IllegalArgumentException): " + e.getMessage() + ". Already stopped or not started correctly?");
                discoveryActive = false; // Force reset if error
            } catch (Exception e) {
                Log.e(TAG, "Exception during nsdManager.stopServiceDiscovery: " + e.getMessage(), e);
                discoveryActive = false; // Force reset if error
            }
        } else {
            Log.d(TAG, "No active NSD discovery to stop, or listener is null, or discoveryActive is false.");
            discoveryActive = false; // Ensure flag is reset
        }
    }

    public void tearDown() {
        Log.d(TAG, "Tearing down NsdHelper.");
        stopDiscovery(); // Ensure discovery is stopped
        this.listener = null;
        this.discoveryListener = null; // Dereference listener
        this.currentResolveListener = null; // Dereference listener
        // NsdManager itself doesn't have a close/unregister method.
        // It's a system service, managed by Android.
        this.nsdManager = null;
        discoveryActive = false;
    }

    public boolean isDiscoveryActive() {
        return discoveryActive;
    }
}