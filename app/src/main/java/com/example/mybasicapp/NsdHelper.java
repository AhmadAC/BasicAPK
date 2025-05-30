package com.example.mybasicapp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NsdHelper {

    private static final String TAG = "NsdHelper";

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private boolean discoveryActive = false;
    private String serviceNameFilter; // Specific name like "mrcoopersesp"
    private String currentServiceTypeToDiscover; // e.g. "_myespwebsocket._tcp."

    public interface NsdHelperListener {
        // Called when a service matching the type and name (if specified) is initially found, before IP resolution
        void onNsdServiceCandidateFound(NsdServiceInfo serviceInfo);
        // Called when a service has been successfully resolved (IP and port obtained)
        void onNsdServiceResolved(DiscoveredService discoveredService);
        // Called when a previously resolved service is lost
        void onNsdServiceLost(DiscoveredService discoveredService); // Use DiscoveredService for consistency
        // Called when discovery fails to start
        void onNsdDiscoveryFailed(String serviceType, int errorCode);
        // Called when resolving a specific service fails
        void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        // Called when discovery starts or stops
        void onNsdDiscoveryLifecycleChange(boolean active, String serviceType);
    }

    private NsdHelperListener listener;
    // Queue for services pending resolution. ConcurrentLinkedQueue is thread-safe.
    private ConcurrentLinkedQueue<NsdServiceInfo> resolveQueue = new ConcurrentLinkedQueue<>();
    private boolean isCurrentlyResolving = false; // Flag to prevent multiple concurrent resolve calls on NsdManager

    public NsdHelper(Context context, NsdHelperListener listener) {
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
        initializeDiscoveryListener(); // Initialize listener once
    }

    private void initializeDiscoveryListener() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.i(TAG, "NSD Discovery STARTED for type: " + regType);
                discoveryActive = true;
                if (listener != null) listener.onNsdDiscoveryLifecycleChange(true, regType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.i(TAG, "NSD Candidate Found (Raw): Name='" + service.getServiceName() + "', Type='" + service.getServiceType() + "'");

                if (currentServiceTypeToDiscover == null || currentServiceTypeToDiscover.isEmpty()) {
                    Log.e(TAG, "currentServiceTypeToDiscover is null or empty. Cannot filter by type.");
                    return;
                }
                // Normalize both types for comparison (remove trailing dot if present)
                String foundServiceTypeNormalized = service.getServiceType().replaceFirst("\\.$", "");
                String expectedServiceTypeNormalized = currentServiceTypeToDiscover.replaceFirst("\\.$", "");

                if (foundServiceTypeNormalized.equalsIgnoreCase(expectedServiceTypeNormalized)) {
                    // If a specific service name filter is set, ensure the found service name matches it.
                    if (serviceNameFilter != null && !serviceNameFilter.isEmpty() &&
                            !service.getServiceName().equalsIgnoreCase(serviceNameFilter)) {
                        Log.d(TAG, "Service candidate '" + service.getServiceName() + "' matches type but NOT name filter '" + serviceNameFilter + "'. Ignoring for resolution queue.");
                        return;
                    }
                    Log.i(TAG, "Matching service candidate '" + service.getServiceName() + "' (Type: " + service.getServiceType() + "). Adding to resolve queue.");
                    if (listener != null) listener.onNsdServiceCandidateFound(service);
                    addToResolveQueue(service);
                } else {
                    Log.d(TAG, "Service '" + service.getServiceName() + "' type '" + foundServiceTypeNormalized + "' does not match expected type '" + expectedServiceTypeNormalized + "'.");
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.w(TAG, "NSD Service LOST (Raw): Name='" + service.getServiceName() + "', Type='" + service.getServiceType() + "'");
                if (listener != null) listener.onNsdServiceLost(new DiscoveredService(service));
                // Remove from resolve queue if it was pending
                resolveQueue.remove(service);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "NSD Discovery STOPPED for type: " + serviceType);
                discoveryActive = false;
                resolveQueue.clear(); // Clear queue when discovery stops
                isCurrentlyResolving = false; // Reset resolving flag
                if (listener != null) listener.onNsdDiscoveryLifecycleChange(false, serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "NSD Discovery START FAILED for type '" + serviceType + "', Error code: " + errorCode);
                discoveryActive = false;
                if (listener != null) listener.onNsdDiscoveryFailed(serviceType, errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "NSD Discovery STOP FAILED for type '" + serviceType + "', Error code: " + errorCode);
                // Even if stop fails, assume it's no longer reliably active from app's perspective
                discoveryActive = false;
                resolveQueue.clear();
                isCurrentlyResolving = false;
                if (listener != null) listener.onNsdDiscoveryLifecycleChange(false, serviceType);
            }
        };
    }

    private void addToResolveQueue(NsdServiceInfo serviceInfo) {
        // Avoid adding duplicates to the queue if a simple check is sufficient
        // For NsdServiceInfo, reference equality or checking name/type might be needed if re-found
        if (!resolveQueue.contains(serviceInfo)) {
            resolveQueue.offer(serviceInfo); // Add to the end of the queue
            processNextInResolveQueue();
        } else {
            Log.d(TAG, "Service " + serviceInfo.getServiceName() + " already in resolve queue.");
        }
    }

    private void processNextInResolveQueue() {
        synchronized (this) { // Synchronize access to isCurrentlyResolving and queue polling
            if (isCurrentlyResolving || resolveQueue.isEmpty()) {
                return; // Either already resolving or queue is empty
            }
            isCurrentlyResolving = true;
        }

        NsdServiceInfo serviceToResolve = resolveQueue.poll(); // Retrieves and removes the head
        if (serviceToResolve == null) { // Should not happen if queue wasn't empty
             synchronized (this) { isCurrentlyResolving = false; }
            return;
        }

        Log.d(TAG, "Attempting to resolve: " + serviceToResolve.getServiceName() + " from queue.");
        nsdManager.resolveService(serviceToResolve, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "NSD Resolve FAILED for service: '" + serviceInfo.getServiceName() + "', Error code: " + errorCode);
                if (listener != null) listener.onNsdResolveFailed(serviceInfo, errorCode);
                finishResolvingAndProcessNext();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "NSD Service RESOLVED: Name='" + serviceInfo.getServiceName() +
                        "', Host='" + (serviceInfo.getHost() != null ? serviceInfo.getHost().getHostAddress() : "N/A") +
                        "', Port='" + serviceInfo.getPort() + "'");
                if (listener != null) listener.onNsdServiceResolved(new DiscoveredService(serviceInfo));
                finishResolvingAndProcessNext();
            }
        });
    }

    private void finishResolvingAndProcessNext() {
        synchronized (this) {
            isCurrentlyResolving = false; // Allow next resolve to start
        }
        processNextInResolveQueue(); // Check if more items are in the queue
    }

    public void discoverServices(String targetServiceNameFilter, String serviceTypeToScan) {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is not initialized. Cannot discover services.");
            if (listener != null) listener.onNsdDiscoveryFailed(serviceTypeToScan, -1); // Custom error
            return;
        }
        if (serviceTypeToScan == null || serviceTypeToScan.isEmpty()) {
            Log.e(TAG, "Service type to scan cannot be null or empty.");
            if (listener != null) listener.onNsdDiscoveryFailed("", NsdManager.FAILURE_BAD_PARAMETERS);
            return;
        }

        if (discoveryActive) {
            Log.d(TAG, "Discovery already active for '" + currentServiceTypeToDiscover + "'. Stopping first.");
            // Stop first, the listener callback onDiscoveryStopped will allow starting new one if needed
            // or UI can re-trigger. For now, let's assume stop is effective quickly.
            nsdManager.stopServiceDiscovery(discoveryListener); // Call directly, rely on callbacks
            // discoveryActive will be set to false in onDiscoveryStopped
        }

        this.serviceNameFilter = targetServiceNameFilter;
        // NsdManager.discoverServices expects the type to end with a dot for some protocols.
        this.currentServiceTypeToDiscover = serviceTypeToScan.endsWith(".") ? serviceTypeToScan : serviceTypeToScan + ".";

        Log.i(TAG, "Requesting NSD service discovery for type: '" + currentServiceTypeToDiscover +
                (serviceNameFilter != null && !serviceNameFilter.isEmpty() ? "' with name filter: '" + serviceNameFilter + "'" : "'"));
        try {
            // discoveryListener should have been initialized in constructor.
            nsdManager.discoverServices(currentServiceTypeToDiscover, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) { // Catch any unexpected errors from discoverServices
            Log.e(TAG, "Exception during nsdManager.discoverServices call: " + e.getMessage(), e);
            discoveryActive = false; // Ensure state is correct
            if (listener != null) listener.onNsdDiscoveryFailed(currentServiceTypeToDiscover, NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    public void stopDiscovery() {
        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is not initialized. Cannot stop discovery.");
            return;
        }
        if (discoveryListener != null && discoveryActive) {
            try {
                Log.i(TAG, "Requesting to stop NSD service discovery for type: " + currentServiceTypeToDiscover);
                nsdManager.stopServiceDiscovery(discoveryListener);
                // discoveryActive will be set to false in the onDiscoveryStopped callback.
            } catch (IllegalArgumentException e) { // If listener not registered
                Log.w(TAG, "Error stopping discovery (IllegalArgumentException): " + e.getMessage() + ". Already stopped or listener invalid?");
                discoveryActive = false; // Force state update
                resolveQueue.clear();
                isCurrentlyResolving = false;
                if (listener != null && currentServiceTypeToDiscover != null) {
                    listener.onNsdDiscoveryLifecycleChange(false, currentServiceTypeToDiscover);
                }
            }
        } else {
            Log.d(TAG, "No active NSD discovery to stop, or listener is null.");
            if(discoveryActive) { // Correct the state if it was wrongly true
                discoveryActive = false;
                if (listener != null && currentServiceTypeToDiscover != null) {
                    listener.onNsdDiscoveryLifecycleChange(false, currentServiceTypeToDiscover);
                }
            }
        }
    }

    public void tearDown() {
        Log.d(TAG, "Tearing down NsdHelper.");
        if (nsdManager != null) {
            stopDiscovery(); // Ensure discovery is stopped.
        }
        this.listener = null; // Remove reference to listener
        // NsdManager is a system service, no explicit close method for manager itself.
        // discoveryListener will be garbage collected if no longer referenced.
        Log.d(TAG, "NsdHelper torn down.");
    }

    public boolean isDiscoveryActive() {
        return discoveryActive;
    }
}