package com.example.mybasicapp.model;

import androidx.annotation.NonNull; // Import for @NonNull annotation

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Objects;

public class EspDevice {
    private String name; // User-defined name for easier identification
    private String address; // IP address or hostname (e.g., "192.168.1.100", "mrcoopersesp.local")

    // Constructor taking only address, name defaults to address
    public EspDevice(@NonNull String address) {
        Objects.requireNonNull(address, "Address cannot be null");
        this.address = address.replaceFirst("^(http://|https://)", ""); // Normalize
        this.name = this.address; // Default name to normalized address
    }

    // Constructor taking both name and address
    public EspDevice(@NonNull String name, @NonNull String address) {
        Objects.requireNonNull(name, "Name cannot be null");
        Objects.requireNonNull(address, "Address cannot be null");
        this.name = name;
        this.address = address.replaceFirst("^(http://|https://)", ""); // Normalize
    }

    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        Objects.requireNonNull(name, "Name cannot be null");
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    /**
     * Sets the address for the ESP device.
     * The address will be normalized by removing "http://" or "https://" prefixes.
     * @param address The IP address or hostname. Cannot be null.
     */
    public void setAddress(@NonNull String address) {
        Objects.requireNonNull(address, "Address cannot be null");
        this.address = address.replaceFirst("^(http://|https://)", ""); // Normalize
    }

    /**
     * Converts this EspDevice object to a JSONObject.
     * @return JSONObject representation.
     */
    public JSONObject toJson() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("name", name);
            jsonObject.put("address", address);
        } catch (JSONException e) {
            // This should ideally not happen with fixed keys
            // Log.e("EspDevice", "Error creating JSON for EspDevice", e);
            // Consider re-throwing or returning null if critical
        }
        return jsonObject;
    }

    /**
     * Creates an EspDevice object from a JSONObject.
     * @param jsonObject The JSONObject containing "name" (optional) and "address" (required).
     * @return A new EspDevice instance.
     * @throws JSONException if "address" is missing or other parsing errors occur.
     */
    public static EspDevice fromJson(@NonNull JSONObject jsonObject) throws JSONException {
        Objects.requireNonNull(jsonObject, "Input JSONObject cannot be null");
        String address = jsonObject.getString("address"); // Address is mandatory
        String name = jsonObject.optString("name", address); // Name defaults to address if not present
        return new EspDevice(name, address);
    }

    /**
     * Returns the full HTTP base URL (e.g., "http://192.168.1.100").
     * Assumes HTTP protocol.
     * @return The full base URL.
     */
    public String getHttpBaseUrl() {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        // Address is already normalized (no http/https prefix)
        return "http://" + address;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EspDevice espDevice = (EspDevice) o;
        // Two EspDevice objects are considered equal if their addresses are the same (case-insensitive).
        // Name is for display and user convenience, address is the unique identifier.
        return address.equalsIgnoreCase(espDevice.address);
    }

    @Override
    public int hashCode() {
        // Hash code based on the address (case-insensitive).
        return Objects.hash(address.toLowerCase());
    }

    @NonNull
    @Override
    public String toString() {
        // Provides a user-friendly string representation, useful for Spinners or logs.
        if (name.equalsIgnoreCase(address)) {
            return address; // If name is same as address, just show address
        }
        return name + " (" + address + ")";
    }
}