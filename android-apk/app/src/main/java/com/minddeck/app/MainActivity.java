package com.minddeck.app;

import android.net.Uri;
import android.os.Bundle;

import com.google.androidbrowserhelper.trusted.LauncherActivity;

public class MainActivity extends LauncherActivity {
    private static final String INSTALL_STATE = "minddeck_install_state";
    private static final String HAS_LAUNCHED = "has_launched";
    private static final String FRESH_INSTALL_URL =
        "https://minddeck-ai-flashcards.vercel.app/api/auth/app/fresh-install";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean firstLaunch = !getSharedPreferences(INSTALL_STATE, MODE_PRIVATE)
            .getBoolean(HAS_LAUNCHED, false);
        if (firstLaunch) {
            getIntent().setData(Uri.parse(FRESH_INSTALL_URL));
            getSharedPreferences(INSTALL_STATE, MODE_PRIVATE)
                .edit()
                .putBoolean(HAS_LAUNCHED, true)
                .apply();
        }
        super.onCreate(savedInstanceState);
    }
}
