package com.befeast.tx10clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Auto-starts the clock after the device finishes booting.
 *
 * <p>The receiver is deliberately minimal and returns quickly: it reads only the
 * internally accepted {@link ExternalConfig} (a strictly-validated model, or the
 * built-in defaults where no accepted config exists — {@code bootStart} defaults
 * true), and launches {@link MainActivity} only when both
 * <ol>
 *   <li>delivery has already launched the app at least once
 *       ({@link AppState#hasLaunchedOnce}), and</li>
 *   <li>the accepted config requests boot start.</li>
 * </ol>
 *
 * <p>It adds no root, overlay, launcher, kiosk, {@code DreamService}, or firmware
 * workaround. Reliable foreground start on the TX10's API 29 OEM firmware is a
 * later live gate; this class only performs a standard, permitted activity
 * launch.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        // Never hijack the first boot after install: only auto-start once the
        // app has actually been opened at least once by delivery.
        if (!AppState.hasLaunchedOnce(context)) {
            return;
        }
        ExternalConfig config = new ConfigStore(ConfigDir.resolve(context)).reload();
        if (!config.bootStart) {
            return;
        }
        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_FROM_BOOT, true);
        context.startActivity(launch);
    }
}
