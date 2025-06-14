package com.example.mybasicapp.adapters;

import android.content.Context;
import android.graphics.Color; // For highlighting active item
import android.graphics.Typeface; // For styling text
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton; // For edit/delete buttons
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat; // For colors
import androidx.recyclerview.widget.RecyclerView;

import com.example.mybasicapp.R;
import com.example.mybasicapp.model.EspDevice;
import com.example.mybasicapp.viewmodels.AppViewModel; // To observe active ESP for highlighting

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EspDeviceAdapter extends RecyclerView.Adapter<EspDeviceAdapter.EspDeviceViewHolder> {

    private static final String TAG = "EspDeviceAdapter_DBG";
    private List<EspDevice> espDeviceList;
    private final OnEspDeviceInteractionListener listener;
    private String activeEspAddress; // To know which item to highlight
    private Context context; // To get resources like colors

    public interface OnEspDeviceInteractionListener {
        void onSetActive(EspDevice device);
        void onEditDevice(EspDevice device, int position);
        void onDeleteDevice(EspDevice device, int position);
    }

    public EspDeviceAdapter(List<EspDevice> deviceList, OnEspDeviceInteractionListener listener, AppViewModel appViewModel) {
        this.espDeviceList = new ArrayList<>(deviceList); // Work with a copy
        this.listener = listener;
        if (appViewModel != null && appViewModel.getActiveEspAddressLiveData().getValue() != null) {
            this.activeEspAddress = appViewModel.getActiveEspAddressLiveData().getValue();
        }
    }
    
    // Constructor for the discovered services list which might not need all interactions
    public EspDeviceAdapter(List<EspDevice> deviceList, OnEspDeviceInteractionListener listener) {
        this.espDeviceList = new ArrayList<>(deviceList);
        this.listener = listener;
        this.activeEspAddress = null; // No active highlighting by default for discovered list
    }


    @NonNull
    @Override
    public EspDeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        this.context = parent.getContext(); // Get context here
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_esp_device, parent, false); // We'll create this layout
        return new EspDeviceViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull EspDeviceViewHolder holder, int position) {
        EspDevice device = espDeviceList.get(position);
        holder.textViewDeviceName.setText(device.getName());
        holder.textViewDeviceAddress.setText(device.getAddress());

        // Highlight if this device is the active one
        if (activeEspAddress != null && device.getAddress().equalsIgnoreCase(activeEspAddress)) {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.active_esp_background)); // Define this color
            holder.textViewDeviceName.setTypeface(null, Typeface.BOLD_ITALIC);
            holder.textViewDeviceAddress.setTypeface(null, Typeface.BOLD_ITALIC);
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT); // Default background
            holder.textViewDeviceName.setTypeface(null, Typeface.NORMAL);
            holder.textViewDeviceAddress.setTypeface(null, Typeface.NORMAL);
        }

        // Set click listener for the whole item to set active
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSetActive(device);
            }
        });

        // Set click listeners for edit and delete buttons
        // These buttons might not exist in the layout used for discovered services,
        // so check for null if you use a different layout for that.
        if (holder.buttonEditDevice != null) {
            holder.buttonEditDevice.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEditDevice(device, holder.getAdapterPosition());
                }
            });
        }

        if (holder.buttonDeleteDevice != null) {
            holder.buttonDeleteDevice.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteDevice(device, holder.getAdapterPosition());
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return espDeviceList.size();
    }

    public void updateDevices(List<EspDevice> newDeviceList) {
        this.espDeviceList.clear();
        if (newDeviceList != null) {
            this.espDeviceList.addAll(newDeviceList);
        }
        Log.d(TAG, "Adapter device list updated. New count: " + this.espDeviceList.size());
        notifyDataSetChanged(); // Simple refresh; for performance, use DiffUtil
    }

    public void addDevice(EspDevice device) {
        if (!espDeviceList.contains(device)) { // EspDevice.equals checks address
            espDeviceList.add(device);
            notifyItemInserted(espDeviceList.size() - 1);
        } else {
            // Optionally update if it exists (e.g., name changed during discovery)
            int index = espDeviceList.indexOf(device);
            if (index != -1) {
                 EspDevice existing = espDeviceList.get(index);
                 if (!Objects.equals(existing.getName(), device.getName())) { // if name is different
                     existing.setName(device.getName());
                     notifyItemChanged(index);
                 }
            }
        }
    }
    
    public void removeDevice(EspDevice device) {
        int index = espDeviceList.indexOf(device); // Uses EspDevice.equals()
        if (index != -1) {
            espDeviceList.remove(index);
            notifyItemRemoved(index);
        }
    }


    public void clearDevices() {
        espDeviceList.clear();
        notifyDataSetChanged();
    }

    public void setActiveEspAddress(String address) {
        if (!Objects.equals(this.activeEspAddress, address)) {
            this.activeEspAddress = address;
            notifyDataSetChanged(); // Re-bind all items to update highlighting
        }
    }


    static class EspDeviceViewHolder extends RecyclerView.ViewHolder {
        TextView textViewDeviceName;
        TextView textViewDeviceAddress;
        ImageButton buttonEditDevice;   // Optional: For managed list
        ImageButton buttonDeleteDevice; // Optional: For managed list

        EspDeviceViewHolder(View itemView) {
            super(itemView);
            textViewDeviceName = itemView.findViewById(R.id.textViewItemEspName);
            textViewDeviceAddress = itemView.findViewById(R.id.textViewItemEspAddress);
            // These buttons might not be in every layout that uses this adapter (e.g., discovered services list)
            buttonEditDevice = itemView.findViewById(R.id.buttonItemEditEsp);
            buttonDeleteDevice = itemView.findViewById(R.id.buttonItemDeleteEsp);
        }
    }
}