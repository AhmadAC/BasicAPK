package com.example.mybasicapp;

import android.net.nsd.NsdServiceInfo;

public class DiscoveredService {
    private String serviceName;
    private String hostAddress;
    private int port;
    private String type; // e.g., _myespwebsocket._tcp

    public DiscoveredService(NsdServiceInfo nsdServiceInfo) {
        this.serviceName = nsdServiceInfo.getServiceName();
        this.hostAddress = nsdServiceInfo.getHost() != null ? nsdServiceInfo.getHost().getHostAddress() : null;
        this.port = nsdServiceInfo.getPort();
        this.type = nsdServiceInfo.getServiceType();
    }

    // Minimal constructor for manual entries or future use
    public DiscoveredService(String serviceName, String hostAddress, int port, String type) {
        this.serviceName = serviceName;
        this.hostAddress = hostAddress;
        this.port = port;
        this.type = type;
    }


    public String getServiceName() {
        return serviceName;
    }

    public String getHostAddress() {
        return hostAddress;
    }

    public int getPort() {
        return port;
    }

    public String getType() { return type; }

    public boolean isValid() {
        return hostAddress != null && !hostAddress.isEmpty() && port > 0;
    }

    @Override
    public String toString() {
        return serviceName + " (" + (hostAddress != null ? hostAddress : "Resolving...") + ":" + port + ")";
    }

    // Implement equals and hashCode to avoid duplicates in a list if necessary
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscoveredService that = (DiscoveredService) o;
        return port == that.port &&
                java.util.Objects.equals(serviceName, that.serviceName) &&
                java.util.Objects.equals(hostAddress, that.hostAddress) &&
                java.util.Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(serviceName, hostAddress, port, type);
    }
}