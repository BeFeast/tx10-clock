package com.befeast.tx10clock;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tiny persisted app state, backed by private {@link SharedPreferences} (internal
 * storage — no permission required).
 *
 * <p>The only fact tracked here is whether the app has been launched at least
 * once. The {@link BootReceiver} consults it so an auto-start on boot only ever
 * happens <em>after</em> delivery has actually opened the app once — it never
 * hijacks the very first boot following an install.
 */
final class AppState {

    private static final String PREFS = "tx10clock_state";
    private static final String KEY_LAUNCHED_ONCE = "launchedOnce";

    private AppState() {
    }

    static boolean hasLaunchedOnce(Context context) {
        return prefs(context).getBoolean(KEY_LAUNCHED_ONCE, false);
    }

    static void markLaunchedOnce(Context context) {
        prefs(context).edit().putBoolean(KEY_LAUNCHED_ONCE, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
