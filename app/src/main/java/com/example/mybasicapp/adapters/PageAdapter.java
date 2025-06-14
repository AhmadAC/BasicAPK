package com.example.mybasicapp.adapters;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.example.mybasicapp.fragments.DeviceManagementFragment;
import com.example.mybasicapp.fragments.EspConfigFragment;
import com.example.mybasicapp.fragments.HomeFragment;
import com.example.mybasicapp.fragments.NoiseSettingsFragment;

// We will create these fragment classes in subsequent steps.
// For now, ensure the import statements are correct based on your package structure.

public class PageAdapter extends FragmentStateAdapter {

    // Define the number of pages/tabs
    private static final int NUM_PAGES = 4;

    public PageAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // Return a NEW fragment instance for the given page.
        switch (position) {
            case 0:
                // Home page for mic monitoring and app-side alerts
                return new HomeFragment();
            case 1:
                // Page to adjust ESP's noise threshold for its internal alert
                return new NoiseSettingsFragment();
            case 2:
                // Page to adjust general ESP config (calibration, WiFi)
                return new EspConfigFragment();
            case 3:
                // Page to manage list of ESPs, select active, and discover
                return new DeviceManagementFragment();
            default:
                // Fallback, should not happen if NUM_PAGES is correct
                return new HomeFragment();
        }
    }

    @Override
    public int getItemCount() {
        // Return the total number of pages.
        return NUM_PAGES;
    }
}