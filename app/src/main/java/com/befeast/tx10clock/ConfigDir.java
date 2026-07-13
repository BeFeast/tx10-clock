package com.befeast.tx10clock;

import android.content.Context;

import java.io.File;

/**
 * Resolves the app-owned directory that holds {@code config.json} and
 * {@code status.json}.
 *
 * <p>The transport is {@code getExternalFilesDir(null)} — on the TX10 the
 * operator alias {@code /sdcard/Android/data/com.befeast.tx10clock/files} —
 * which is app-scoped external storage and therefore needs <b>no storage
 * permission</b>. If external storage is unavailable (e.g. unmounted) the app
 * falls back to its always-present internal files dir so it can never crash for
 * lack of a config location.
 */
final class ConfigDir {

    private ConfigDir() {
    }

    static File resolve(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }
}
