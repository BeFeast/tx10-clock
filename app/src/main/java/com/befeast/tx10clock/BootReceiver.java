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
 * <p>This class only performs a standard activity launch and adds no root,
 * launcher, kiosk, {@code DreamService}, or firmware workaround. It is not,
 * however, sufficient on its own: on the TX10 Pro (Android 10 / API 29) the
 * background-activity-launch restriction rejects an activity start from a
 * {@code BOOT_COMPLETED} receiver unless the app is exempt. Delivery grants
 * that exemption out of band by setting the {@code SYSTEM_ALERT_WINDOW}
 * app-op during provisioning (see {@code docs/external-config.md} and
 * {@code delivery/README.md}); the receiver itself requests no overlay window
 * and never draws one.
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
