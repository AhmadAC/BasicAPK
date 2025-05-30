package com.example.mybasicapp;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.concurrent.ConcurrentLinkedQueue;

public class NsdHelper {

    private static final String TAG = "NsdHelper_DEBUG"; // Enhanced Tag

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;

    private boolean discoveryActive = false;
    private String serviceNameFilter;
    private String currentServiceTypeToDiscover;

    public interface NsdHelperListener {
        void onNsdServiceCandidateFound(NsdServiceInfo serviceInfo);
        void onNsdServiceResolved(DiscoveredService discoveredService);
        void onNsdServiceLost(DiscoveredService discoveredService);
        void onNsdDiscoveryFailed(String serviceType, int errorCode);
        void onNsdResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onNsdDiscoveryLifecycleChange(boolean active, String serviceType);
    }

    private NsdHelperListener listener;
    private ConcurrentLinkedQueue<NsdServiceInfo> resolveQueue = new ConcurrentLinkedQueue<>();
    private boolean isCurrentlyResolving = false;

    public NsdHelper(Context context, NsdHelperListener listener) {
        Log.d(TAG, "NsdHelper Constructor called");
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
        initializeDiscoveryListener();
    }

    private void initializeDiscoveryListener() {
        Log.d(TAG, "initializeDiscoveryListener()");
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.i(TAG, "onDiscoveryStarted: type=" + regType);
                discoveryActive = true;
                if (listener != null) listener.onNsdDiscoveryLifecycleChange(true, regType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.i(TAG, "onServiceFound: RAW - Name='" + service.getServiceName() + "', Type='" + service.getServiceType() + "', Port='" + service.getPort() + "'");
                Log.d(TAG, "onServiceFound: Current Filter - expectedType='" + currentServiceTypeToDiscover + "', expectedName='" + serviceNameFilter + "'");

                if (currentServiceTypeToDiscover == null || currentServiceTypeToDiscover.isEmpty()) {
                    Log.e(TAG, "onServiceFound: currentServiceTypeToDiscover is null/empty. Cannot filter.");
                    return;
                }
                String foundServiceTypeNormalized = service.getServiceType().replaceFirst("\\.$", "");
                String expectedServiceTypeNormalized = currentServiceTypeToDiscover.replaceFirst("\\.$", "");
                Log.d(TAG, "onServiceFound: Normalized types - Found='" + foundServiceTypeNormalized + "', Expected='" + expectedServiceTypeNormalized + "'");

                if (foundServiceTypeNormalized.equalsIgnoreCase(expectedServiceTypeNormalized)) {
                    Log.d(TAG, "onServiceFound: Type MATCHED: " + foundServiceTypeNormalized);
                    if (serviceNameFilter != null && !serviceNameFilter.isEmpty() &&
                            !service.getServiceName().equalsIgnoreCase(serviceNameFilter)) {
                        Log.d(TAG, "onServiceFound: Type matched, but Name MISMATCH. FoundName='" + service.getServiceName() + "', ExpectedName='" + serviceNameFilter + "'. Ignoring for resolve queue.");
                        return;
                    }
                    Log.i(TAG, "onServiceFound: MATCH! Name='" + service.getServiceName() + "'. Adding to resolve queue.");
                    if (listener != null) listener.onNsdServiceCandidateFound(service);
                    addToResolveQueue(service);
                } else {
                    Log.d(TAG, "onServiceFound: Type MISMATCH. FoundType='" + foundServiceTypeNormalized + "', ExpectedType='" + expectedServiceTypeNormalized + "'.");
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.w(TAG, "onServiceLost: Name='" + service.getServiceName() + "', Type='" + service.getServiceType() + "'");
                if (listener != null) listener.onNsdServiceLost(new DiscoveredService(service));
                resolveQueue.remove(service);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "onDiscoveryStopped: type=" + serviceType);
                discoveryActive = false;
                resolveQueue.clear();
                isCurrentlyResolving = false;
                if (listener != null) listener.onNsdDiscoveryLifecycleChange(false, serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "onStartDiscoveryFailed: type=" + serviceType + ", errorCode=" + errorCode);
                discoveryActive = false;
                if (listener != null) listener.onNsdDiscoveryFailed(serviceType, errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "onStopDiscoveryFailed: type=" + serviceType + ", errorCode=" + errorCode);
                discoveryActive = false;
                resolveQueue.clear();
                isCurrentlyResolving = false;
                if (listener != null) listener.onNsdDiscoveryLifecycleChange(false, serviceType);
            }
        };
    }

    private void addToResolveQueue(NsdServiceInfo serviceInfo) {
        Log.d(TAG, "addToResolveQueue: Attempting to add '" + serviceInfo.getServiceName() + "'");
        if (!resolveQueue.contains(serviceInfo)) { // Simple contains check, NsdServiceInfo might need proper equals
            resolveQueue.offer(serviceInfo);
            Log.d(TAG, "addToResolveQueue: Added '" + serviceInfo.getServiceName() + "'. Queue size: " + resolveQueue.size());
            processNextInResolveQueue();
        } else {
            Log.d(TAG, "addToResolveQueue: Service '" + serviceInfo.getServiceName() + "' already in queue.");
        }
    }

    private void processNextInResolveQueue() {
        synchronized (this) {
            if (isCurrentlyResolving) {
                Log.d(TAG, "processNextInResolveQueue: Already resolving. Queue size: " + resolveQueue.size());
                return;
            }
            if (resolveQueue.isEmpty()) {
                Log.d(TAG, "processNextInResolveQueue: Queue is empty.");
                return;
            }
            isCurrentlyResolving = true;
        }

        NsdServiceInfo serviceToResolve = resolveQueue.poll();
        if (serviceToResolve == null) {
            Log.w(TAG, "processNextInResolveQueue: Polled null from non-empty queue (race condition?)");
            synchronized (this) { isCurrentlyResolving = false; }
            return;
        }

        Log.i(TAG, "processNextInResolveQueue: Attempting to resolve '" + serviceToResolve.getServiceName() + "'. Remaining in queue: " + resolveQueue.size());
        if (nsdManager == null) {
            Log.e(TAG, "processNextInResolveQueue: NsdManager is null! Cannot resolve.");
            finishResolvingAndProcessNext();
            return;
        }
        nsdManager.resolveService(serviceToResolve, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "onResolveFailed: Service='" + serviceInfo.getServiceName() + "', ErrorCode=" + errorCode);
                if (listener != null) listener.onNsdResolveFailed(serviceInfo, errorCode);
                finishResolvingAndProcessNext();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                String hostAddress = (serviceInfo.getHost() != null) ? serviceInfo.getHost().getHostAddress() : "N/A";
                Log.i(TAG, "onServiceResolved: Name='" + serviceInfo.getServiceName() + "', Host='" + hostAddress + "', Port='" + serviceInfo.getPort() + "'");
                if (listener != null) listener.onNsdServiceResolved(new DiscoveredService(serviceInfo));
                finishResolvingAndProcessNext();
            }
        });
    }

    private void finishResolvingAndProcessNext() {
        Log.d(TAG, "finishResolvingAndProcessNext()");
        synchronized (this) {
            isCurrentlyResolving = false;
        }
        processNextInResolveQueue();
    }

    public void discoverServices(String targetServiceNameFilter, String serviceTypeToScan) {
        Log.i(TAG, "discoverServices: Requested. FilterName='" + targetServiceNameFilter + "', Type='" + serviceTypeToScan + "'");
        if (nsdManager == null) {
            Log.e(TAG, "discoverServices: NsdManager is null!");
            if (listener != null) listener.onNsdDiscoveryFailed(serviceTypeToScan, -100); // Custom error
            return;
        }
        if (serviceTypeToScan == null || serviceTypeToScan.isEmpty()) {
            Log.e(TAG, "discoverServices: Service type to scan cannot be null or empty.");
            if (listener != null) listener.onNsdDiscoveryFailed("", NsdManager.FAILURE_BAD_PARAMETERS);
            return;
        }

        if (discoveryActive) {
            Log.d(TAG, "discoverServices: Discovery already active for '" + currentServiceTypeToDiscover + "'. Stopping it first.");
            // This stop is asynchronous. The new discovery will be attempted immediately after.
            // This might lead to onDiscoveryStopped being called after the new one has started if not careful.
            // However, NsdManager should handle multiple calls.
            nsdManager.stopServiceDiscovery(discoveryListener);
            // discoveryActive will be set false in its callback
        }

        this.serviceNameFilter = targetServiceNameFilter;
        this.currentServiceTypeToDiscover = serviceTypeToScan.endsWith(".") ? serviceTypeToScan : serviceTypeToScan + ".";
        Log.d(TAG, "discoverServices: Setting scan parameters - Type='" + currentServiceTypeToDiscover + "', NameFilter='" + this.serviceNameFilter + "'");

        try {
            Log.d(TAG, "discoverServices: Calling nsdManager.discoverServices()...");
            nsdManager.discoverServices(currentServiceTypeToDiscover, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "discoverServices: Exception during nsdManager.discoverServices call: " + e.getMessage(), e);
            discoveryActive = false;
            if (listener != null) listener.onNsdDiscoveryFailed(currentServiceTypeToDiscover, NsdManager.FAILURE_INTERNAL_ERROR);
        }
    }

    public void stopDiscovery() {
        Log.i(TAG, "stopDiscovery: Requested.");
        if (nsdManager == null) {
            Log.e(TAG, "stopDiscovery: NsdManager is null!");
            return;
        }
        if (discoveryListener != null && discoveryActive) {
            try {
                Log.d(TAG, "stopDiscovery: Calling nsdManager.stopServiceDiscovery() for type: " + currentServiceTypeToDiscover);
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "stopDiscovery: IllegalArgumentException: " + e.getMessage() + ". Already stopped or listener invalid?");
                discoveryActive = false; // Force state update
                resolveQueue.clear();
                isCurrentlyResolving = false;
                if (listener != null && currentServiceTypeToDiscover != null) {
                    listener.onNsdDiscoveryLifecycleChange(false, currentServiceTypeToDiscover);
                }
            }
        } else {
            Log.d(TAG, "stopDiscovery: No active discovery to stop, or listener is null, or discoveryActive is false. Current discoveryActive=" + discoveryActive);
            if(discoveryActive) { // If flag was somehow stuck true
                discoveryActive = false;
                if (listener != null && currentServiceTypeToDiscover != null) {
                    listener.onNsdDiscoveryLifecycleChange(false, currentServiceTypeToDiscover);
                }
            }
        }
    }

    public void tearDown() {
        Log.i(TAG, "tearDown: Called.");
        if (nsdManager != null) {
            stopDiscovery();
        }
        this.listener = null;
        // this.nsdManager = null; // Let it be GC'd if context is gone. System service.
        Log.d(TAG, "NsdHelper torn down.");
    }

    public boolean isDiscoveryActive() {
        return discoveryActive;
    }
}