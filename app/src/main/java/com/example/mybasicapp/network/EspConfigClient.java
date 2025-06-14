package com.example.mybasicapp.network; // Ensure this package declaration is correct

import android.util.Log;
import androidx.annotation.NonNull;

import org.json.JSONObject; // For the potential updateFullConfig method

import java.io.IOException;
import java.net.URLEncoder; // For encoding form data values
import java.nio.charset.StandardCharsets;
import java.util.Locale; // ADDED IMPORT
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody; // For explicit handling of response body

public class EspConfigClient {
    private static final String TAG = "EspConfigClient_DBG";
    private final OkHttpClient client;
    public static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    // Callback interface for asynchronous operations
    public interface ConfigCallback {
        void onSuccess(String responseBody); // Called with the response body string on success
        void onFailure(IOException e);       // Called on network failure
        void onError(String message, int code); // Called on HTTP error responses (e.g., 404, 500)
    }

    public EspConfigClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS) // Connection timeout
                .readTimeout(15, TimeUnit.SECONDS)    // Read timeout
                .writeTimeout(15, TimeUnit.SECONDS)   // Write timeout
                .build();
    }

    /**
     * Helper to construct the full URL, ensuring it starts with http:// if no schema is present.
     * @param espBaseAddress The base address from AppViewModel (e.g., "192.168.1.100" or "esp.local")
     * @param endpoint The specific endpoint (e.g., "/get_config")
     * @return The fully formed URL string.
     */
    private String formFullUrl(String espBaseAddress, String endpoint) {
        String baseUrl = espBaseAddress;
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            Log.e(TAG, "formFullUrl: espBaseAddress is null or empty!");
            return null; // Or throw an IllegalArgumentException
        }
        // Ensure the base URL starts with http:// if no schema is present
        if (!baseUrl.toLowerCase().startsWith("http://") && !baseUrl.toLowerCase().startsWith("https://")) {
            baseUrl = "http://" + baseUrl;
        }
        // Remove trailing slash from base URL if present, to avoid double slashes
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // Ensure endpoint starts with a slash
        String formattedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return baseUrl + formattedEndpoint;
    }

    /**
     * Fetches the current configuration from the ESP's /get_config endpoint.
     * @param espAddress The normalized address of the ESP (e.g., "192.168.1.100").
     * @param callback The callback to handle the response.
     */
    public void getConfig(String espAddress, ConfigCallback callback) {
        String url = formFullUrl(espAddress, "/get_config");
        if (url == null) {
            if (callback != null) callback.onFailure(new IOException("Invalid ESP address for getConfig."));
            return;
        }
        Log.d(TAG, "Requesting config from ESP: " + url);
        Request request = new Request.Builder().url(url).get().build();
        executeCall(request, callback);
    }

    /**
     * Sets the noise threshold on the ESP.
     * @param espAddress The normalized address of the ESP.
     * @param threshold The new threshold_db_calibrated value.
     * @param callback The callback to handle the response.
     */
    public void setThreshold(String espAddress, float threshold, ConfigCallback callback) {
        String url = formFullUrl(espAddress, "/set_threshold");
        if (url == null) {
            if (callback != null) callback.onFailure(new IOException("Invalid ESP address for setThreshold."));
            return;
        }
        RequestBody body = new FormBody.Builder()
                .add("threshold_db_calibrated", String.format(Locale.US, "%.1f", threshold))
                .build();
        Request request = new Request.Builder().url(url).post(body).build();
        Log.d(TAG, "Setting threshold on " + url + " to " + threshold);
        executeCall(request, callback);
    }

    /**
     * Sets the calibration offset on the ESP.
     * @param espAddress The normalized address of the ESP.
     * @param offset The new calibration_offset value.
     * @param callback The callback to handle the response.
     */
    public void setCalibration(String espAddress, float offset, ConfigCallback callback) {
        String url = formFullUrl(espAddress, "/set_calibration");
         if (url == null) {
            if (callback != null) callback.onFailure(new IOException("Invalid ESP address for setCalibration."));
            return;
        }
        RequestBody body = new FormBody.Builder()
                .add("calibration_offset", String.format(Locale.US, "%.1f", offset))
                .build();
        Request request = new Request.Builder().url(url).post(body).build();
        Log.d(TAG, "Setting calibration offset on " + url + " to " + offset);
        executeCall(request, callback);
    }

    /**
     * Sets the WiFi configuration (SSID and Password) on the ESP.
     * Note: The ESP needs a /set_wifi_config endpoint that accepts these parameters.
     * @param espAddress The normalized address of the ESP.
     * @param ssid The new WiFi SSID.
     * @param password The new WiFi password.
     * @param callback The callback to handle the response.
     */
    public void setWifiConfig(String espAddress, @NonNull String ssid, @NonNull String password, ConfigCallback callback) {
        String url = formFullUrl(espAddress, "/set_wifi_config");
        if (url == null) {
            if (callback != null) callback.onFailure(new IOException("Invalid ESP address for setWifiConfig."));
            return;
        }

        // Values for SSID and password should be URL encoded when sent as form data
        // OkHttp's FormBody.Builder typically handles this encoding for standard characters,
        // but for robustness, especially if SSID/password can contain special chars, explicit encoding is safer.
        // However, FormBody.Builder is generally good. Let's rely on it for now.
        // If issues with special characters, one might need to pre-encode:
        // String encodedSsid = URLEncoder.encode(ssid, StandardCharsets.UTF_8.name());
        // String encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8.name());
        
        RequestBody body = new FormBody.Builder()
                .add("wifi_ssid", ssid)       // FormBody.Builder handles URL encoding of values
                .add("wifi_password", password)
                .build();
        Request request = new Request.Builder().url(url).post(body).build();
        Log.d(TAG, "Setting WiFi config on " + url + " - SSID: " + ssid); // Avoid logging password directly
        executeCall(request, callback);
    }
    
    /**
     * Example: Sends a full configuration JSON object to the ESP.
     * This assumes your ESP has an endpoint like "/update_config" that accepts JSON.
     * This is NOT used by the current fragments but shows how to POST JSON.
     * @param espAddress The normalized address of the ESP.
     * @param configJson The JSONObject containing the full configuration.
     * @param callback The callback to handle the response.
     */
    public void updateFullConfigViaJson(String espAddress, JSONObject configJson, ConfigCallback callback) {
        String url = formFullUrl(espAddress, "/update_config_json"); // Example endpoint
        if (url == null) {
            if (callback != null) callback.onFailure(new IOException("Invalid ESP address for updateFullConfigViaJson."));
            return;
        }
        RequestBody body = RequestBody.create(configJson.toString(), JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).post(body).build();
        Log.d(TAG, "Updating full config via JSON on " + url);
        executeCall(request, callback);
    }


    /**
     * Executes the OkHttp call asynchronously.
     * @param request The OkHttp Request object.
     * @param callback The callback to handle success or failure.
     */
    private void executeCall(Request request, final ConfigCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "HTTP request failed: " + request.url(), e);
                if (callback != null) {
                    callback.onFailure(e);
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                // ResponseBody must be closed to prevent resource leaks.
                // Use try-with-resources or ensure it's closed in all paths.
                try (ResponseBody responseBody = response.body()) {
                    String responseBodyString = "";
                    if (responseBody != null) {
                        responseBodyString = responseBody.string(); // .string() consumes and closes the body
                    }

                    if (response.isSuccessful()) {
                        Log.d(TAG, "HTTP request successful (" + response.code() + ") to " + request.url() +
                                   "\nResponse snippet: " + responseBodyString.substring(0, Math.min(200, responseBodyString.length())));
                        if (callback != null) {
                            callback.onSuccess(responseBodyString);
                        }
                    } else {
                        String errorMessage = "HTTP error " + response.code() + " " + response.message() +
                                            " for URL: " + request.url() +
                                            "\nResponse body: " + responseBodyString;
                        Log.e(TAG, errorMessage);
                        if (callback != null) {
                            // Pass a more concise message if responseBodyString is too long for UI
                            String uiMessage = "Error " + response.code() + ": " + response.message();
                            if (!responseBodyString.isEmpty() && responseBodyString.length() < 100) { // Add body if short
                                uiMessage += " - " + responseBodyString;
                            }
                            callback.onError(uiMessage, response.code());
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "IOException while reading response body for " + request.url(), e);
                    if (callback != null) {
                        callback.onFailure(e); // Report as a network failure
                    }
                }
            }
        });
    }
}