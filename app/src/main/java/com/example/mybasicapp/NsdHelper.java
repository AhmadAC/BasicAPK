package com.example.mybasicapp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

public class NsdHelper {

    private static final String TAG = "NsdHelper";
    // This MUST match the service type your ESP32 advertises.
    // ESP32 CircuitPython code used: MDNS_WEBSOCKET_SERVICE_TYPE = "_myespwebsocket" and protocol = "_tcp"
    // So, for NsdManager, it's "_myespwebsocket._tcp"
    public static final String SERVICE_TYPE = "_myespwebsocket._tcp";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private NsdManager.ResolveListener currentResolveListener;

    private boolean discoveryActive = false;
    private String serviceNameFilter; // To store the specific service name we are looking for

    // Callbacks for the client (e.g., MainActivity)
    public interface NsdHelperListener {
        void onNsdServiceResolved(NsdServiceInfo serviceInfo); // Called when the *target* service is resolved
        void onNsdServiceLost(NsdServiceInfo serviceInfo);
        void onNsdDiscoveryFailed(String serviceType, int errorCode);
        void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onNsdDiscoveryStatusChanged(String statusMessage); // General status updates
    }

    private NsdHelperListener listener;

    public NsdHelper(Context context, NsdHelperListener listener) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    private void initializeDiscoveryListener() {
        if (discoveryListener != null) {
            Log.d(TAG, "Discovery listener already initialized.");
            return;
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.i(TAG, "NSD Service discovery started for type: " + regType);
                discoveryActive = true;
                if (listener != null) listener.onNsdDiscoveryStatusChanged("Discovery started for " + regType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "NSD Service candidate found: Name='" + service.getServiceName() + "', Type='" + service.getServiceType() + "'");
                // The serviceType from NsdServiceInfo can sometimes include the domain, e.g., "_myespwebsocket._tcp.local."
                // Using startsWith is more robust.
                if (service.getServiceType() != null && service.getServiceType().startsWith(SERVICE_TYPE.substring(0, SERVICE_TYPE.indexOf(".")))) {
                    // If we have a specific service name filter, check it now before resolving
                    if (serviceNameFilter != null && !service.getServiceName().equalsIgnoreCase(serviceNameFilter)) {
                        Log.d(TAG, "Service candidate '" + service.getServiceName() + "' does not match filter '" + serviceNameFilter + "'. Ignoring.");
                        return;
                    }
                    Log.i(TAG, "Matching service type (and name if filtered) found: '" + service.getServiceName() + "'. Attempting to resolve.");
                    if (listener != null) listener.onNsdDiscoveryStatusChanged("Found ESP32 candidate: " + service.getServiceName() + ". Resolving...");

                    // Resolve the service. Use a new ResolveListener for each attempt.
                    // Teardown any previous resolve listener
                    if (currentResolveListener != null) {
                        Log.d(TAG, "Cancelling previous resolve operation.");
                        // NsdManager doesn't have a direct cancel for resolve.
                        // We just replace the listener for the new resolve call.
                    }
                    currentResolveListener = new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.e(TAG, "NSD Resolve FAILED for service: '" + serviceInfo.getServiceName() + "', Error code: " + errorCode);
                            if (listener != null) listener.onNsdResolveFailed(serviceInfo, errorCode);
                            currentResolveListener = null; // Clear listener
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            Log.i(TAG, "NSD Service RESOLVED: Name='" + serviceInfo.getServiceName() + "', Host='" + serviceInfo.getHost() + "', Port='" + serviceInfo.getPort() + "'");
                            if (listener != null) listener.onNsdServiceResolved(serviceInfo);
                            currentResolveListener = null; // Clear listener
                        }
                    };
                    nsdManager.resolveService(service, currentResolveListener);
                } else {
                     Log.d(TAG, "Service '" + service.getServiceName() + "' with type '" + service.getServiceType() + "' does not match expected type '" + SERVICE_TYPE + "'. Ignoring.");
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
                discoveryActive = false;
                if (listener != null) listener.onNsdDiscoveryStatusChanged("Discovery stopped.");
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
                // This usually means the listener wasn't registered or an issue with NsdManager.
            }
        };
    }

    public void discoverServices(String targetServiceName) {
        this.serviceNameFilter = targetServiceName;
        stopDiscovery(); // Stop any previous discovery first

        if (discoveryListener == null) {
            initializeDiscoveryListener();
        }
        if (discoveryListener == null) { // Check again
             Log.e(TAG, "Cannot start discovery: DiscoveryListener is null.");
             if (listener != null) listener.onNsdDiscoveryFailed(SERVICE_TYPE, -1); // Custom error
             return;
        }

        Log.d(TAG, "Attempting to start NSD service discovery for type: " + SERVICE_TYPE + " (will filter for name: " + targetServiceName + ")");
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "Exception during nsdManager.discoverServices call: " + e.getMessage(), e);
            if (listener != null) listener.onNsdDiscoveryFailed(SERVICE_TYPE, NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    public void stopDiscovery() {
        if (discoveryListener != null && discoveryActive) {
            try {
                Log.d(TAG, "Attempting to stop NSD service discovery.");
                nsdManager.stopServiceDiscovery(discoveryListener);
                // discoveryListener itself is not nulled here to allow reuse
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Error stopping discovery (IllegalArgumentException): " + e.getMessage() + ". Likely already stopped or not started.");
            } catch (Exception e) {
                Log.e(TAG, "Exception during nsdManager.stopServiceDiscovery: " + e.getMessage(), e);
            }
        } else {
            Log.d(TAG, "No active NSD discovery listener to stop or listener is null.");
        }
        discoveryActive = false; // Ensure flag is reset
    }

    public void tearDown() {
        Log.d(TAG, "Tearing down NsdHelper.");
        stopDiscovery();
        this.listener = null; // Clear listener to prevent leaks
        this.discoveryListener = null; // Clear discovery listener
        this.currentResolveListener = null; // Clear resolve listener
        this.nsdManager = null; // Allow NsdManager to be GC'd if context is gone
    }

    public boolean isDiscoveryActive() {
        return discoveryActive;
    }
}