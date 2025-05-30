package com.example.mybasicapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class DiscoveredServicesAdapter extends RecyclerView.Adapter<DiscoveredServicesAdapter.ViewHolder> {

    private List<DiscoveredService> discoveredServices = new ArrayList<>();
    private OnServiceClickListener listener;

    public interface OnServiceClickListener {
        void onServiceClick(DiscoveredService service);
    }

    public DiscoveredServicesAdapter(OnServiceClickListener listener) {
        this.listener = listener;
    }

    public void setServices(List<DiscoveredService> services) {
        this.discoveredServices.clear();
        if (services != null) {
            this.discoveredServices.addAll(services);
        }
        notifyDataSetChanged(); // Use DiffUtil for better performance in complex apps
    }

    public void addService(DiscoveredService service) {
        if (!discoveredServices.contains(service)) { // Avoid duplicates
            discoveredServices.add(service);
            notifyItemInserted(discoveredServices.size() - 1);
        } else { // If it exists, maybe update it (e.g. IP resolved)
           int index = discoveredServices.indexOf(service);
           if (index != -1) {
               discoveredServices.set(index, service);
               notifyItemChanged(index);
           }
        }
    }

    public void clearServices() {
        discoveredServices.clear();
        notifyDataSetChanged();
    }


    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_discovered_service, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DiscoveredService service = discoveredServices.get(position);
        holder.bind(service, listener);
    }

    @Override
    public int getItemCount() {
        return discoveredServices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textViewServiceName;
        TextView textViewServiceAddress;

        ViewHolder(View itemView) {
            super(itemView);
            textViewServiceName = itemView.findViewById(R.id.textViewServiceName);
            textViewServiceAddress = itemView.findViewById(R.id.textViewServiceAddress);
        }

        void bind(final DiscoveredService service, final OnServiceClickListener listener) {
            textViewServiceName.setText(service.getServiceName());
            if (service.isValid()) {
                textViewServiceAddress.setText(service.getHostAddress() + ":" + service.getPort());
            } else {
                textViewServiceAddress.setText("Resolving or invalid...");
            }
            itemView.setOnClickListener(v -> listener.onServiceClick(service));
        }
    }
}