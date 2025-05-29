package com.example.mybasicapp;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Basic layout with a TextView (activity_main.xml would be needed)
        // For simplicity, creating a TextView programmatically:
        TextView textView = new TextView(this);
        textView.setText("Hello from APK built by GitHub Actions!");
        textView.setTextSize(20);
        textView.setGravity(android.view.Gravity.CENTER);
        setContentView(textView);

        // If using app/src/main/res/layout/activity_main.xml:
        // setContentView(R.layout.activity_main);
    }
}
