package com.befeast.tx10clock;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/**
 * Single full-screen Activity that hosts the {@link ClockView}. Built on the
 * plain framework {@link Activity} (no AndroidX) to keep the APK minimal and
 * free of extra native dependencies.
 */
public final class MainActivity extends Activity {

    private ClockView clockView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A clock should never let the TV blank out.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        clockView = new ClockView(this);
        clockView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(clockView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        clockView.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockView.stop();
    }
}
