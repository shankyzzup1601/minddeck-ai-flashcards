package com.minddeck.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.google.androidbrowserhelper.trusted.LauncherActivity;

public class MainActivity extends LauncherActivity {
    private static final String INSTALL_STATE = "minddeck_install_state_v3";
    private static final String HAS_LAUNCHED = "has_launched";
    private static final String APP_URL = "https://minddeck-ai-flashcards.vercel.app/?source=android-app";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Uri uri = getIntent().getData();
        if (uri != null && "minddeck".equals(uri.getScheme()) && "timer".equals(uri.getHost())) {
            forwardTimerAction(uri);
            getIntent().setData(Uri.parse(APP_URL));
        } else {
            boolean firstLaunch = !getSharedPreferences(INSTALL_STATE, MODE_PRIVATE)
                .getBoolean(HAS_LAUNCHED, false);
            if (firstLaunch) {
                getIntent().setData(Uri.parse(APP_URL + "&fresh-install=android-v3"));
                getSharedPreferences(INSTALL_STATE, MODE_PRIVATE)
                    .edit().putBoolean(HAS_LAUNCHED, true).apply();
            }
        }
        super.onCreate(savedInstanceState);
        requestNotificationPermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Uri uri = intent.getData();
        if (uri != null && "minddeck".equals(uri.getScheme()) && "timer".equals(uri.getHost())) {
            forwardTimerAction(uri);
            intent.setData(Uri.parse(APP_URL));
        }
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void forwardTimerAction(Uri uri) {
        String action = uri.getLastPathSegment();
        Intent serviceIntent = new Intent(this, FocusTimerService.class);
        long seconds = 0;
        try {
            seconds = Long.parseLong(uri.getQueryParameter("seconds"));
        } catch (Exception ignored) {
            // Invalid values are safely ignored by the service.
        }
        serviceIntent.putExtra(FocusTimerService.EXTRA_SECONDS, seconds);
        if ("start".equals(action)) serviceIntent.setAction(FocusTimerService.ACTION_START);
        else if ("pause".equals(action)) serviceIntent.setAction(FocusTimerService.ACTION_PAUSE);
        else if ("complete".equals(action)) serviceIntent.setAction(FocusTimerService.ACTION_COMPLETE);
        else serviceIntent.setAction(FocusTimerService.ACTION_RESET);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && "start".equals(action)) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1203);
        }
    }
}
