package com.befeast.tx10clock;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.io.IOException;
import java.time.ZoneId;

/**
 * Single full-screen Activity that hosts the {@link ClockView}. Built on the
 * plain framework {@link Activity} (no AndroidX) to keep the APK minimal and
 * free of extra native dependencies.
 *
 * <p>The shell is immersive and keeps the screen on, but is otherwise an
 * ordinary activity: Home and Back behave normally so the clock is never a kiosk
 * and can always be exited. On every resume it reloads the app-owned external
 * config (last-known-good on rejection) and re-publishes the verifier-safe
 * {@code status.json}.
 */
public final class MainActivity extends Activity {

    /** Set true on the launch intent when started by {@link BootReceiver}. */
    public static final String EXTRA_FROM_BOOT = "com.befeast.tx10clock.FROM_BOOT";

    private static final String TAG = "Tx10Clock";

    private ClockView clockView;
    private ConfigStore configStore;
    private boolean launchedFromBoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        launchedFromBoot = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_FROM_BOOT, false);

        // Record that delivery has now opened the app at least once; this is the
        // gate the boot receiver checks before auto-starting on later boots.
        AppState.markLaunchedOnce(this);

        configStore = new ConfigStore(ConfigDir.resolve(this));

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

        // Reload after any atomic config replacement that happened while paused,
        // then apply the behavioural settings and re-publish status.
        ExternalConfig config = configStore.reload();
        clockView.apply(effectiveClockConfig(config), timeSourceFor(config));
        clockView.start();
        publishStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockView.stop();
    }

    /**
     * Map the renderer-agnostic external config onto the renderer's own
     * {@link ClockConfig}: the elegant theme's colours/geometry are untouched;
     * only the behavioural 12/24-hour and seconds toggles are threaded through.
     */
    private static ClockConfig effectiveClockConfig(ExternalConfig config) {
        return ClockConfig.defaultConfig().toBuilder()
                .use24Hour(config.use24Hour)
                .showSeconds(config.showSeconds)
                .build();
    }

    private static TimeSource timeSourceFor(ExternalConfig config) {
        ZoneId zone = config.resolveZone(ZoneId.systemDefault());
        return TimeSource.systemInZone(zone);
    }

    private void publishStatus() {
        try {
            configStore.writeStatus(launchedFromBoot);
        } catch (IOException e) {
            // status.json is diagnostic only; never let a write failure disturb
            // the clock itself.
            Log.w(TAG, "could not write status.json");
        }
    }
}
