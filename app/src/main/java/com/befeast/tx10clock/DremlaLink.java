package com.befeast.tx10clock;

import android.content.ComponentName;
import android.content.Intent;

import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * The one way Clock starts Dremla: an explicit Intent to Dremla's session entry carrying only the
 * session mode, the music duration and the end of the sleep window. No calendar titles, events or
 * credentials ever cross this boundary. Dremla validates the same fields again on its side.
 */
public final class DremlaLink {

    public static final String PACKAGE = "com.befeast.sleepmusic";
    public static final String ENTRY = "com.befeast.sleepmusic.SessionEntryActivity";
    public static final String ACTION_START = "com.befeast.sleepmusic.action.START_SESSION";
    public static final String ACTION_STOP = "com.befeast.sleepmusic.action.STOP_SESSION";
    public static final String RECEIVER = "com.befeast.sleepmusic.SessionCommandReceiver";
    public static final String EXTRA_MODE = "com.befeast.sleepmusic.extra.MODE";
    public static final String EXTRA_MUSIC_MINUTES = "com.befeast.sleepmusic.extra.MUSIC_MINUTES";
    public static final String EXTRA_WINDOW_END = "com.befeast.sleepmusic.extra.WINDOW_END";
    public static final String EXTRA_REQUEST_ID = "com.befeast.sleepmusic.extra.REQUEST_ID";

    public static final String MODE_NIGHT = "night";
    public static final String MODE_DAY = "day";

    /** Dremla's timer choices; music duration is independent of the sleep window. */
    public static final int[] MUSIC_CHOICES = {30, 45, 60, 90, 120, 180};
    public static final int DEFAULT_MUSIC_MINUTES = 45;

    private DremlaLink() {
    }

    public static int sanitizeMusic(int minutes) {
        for (int choice : MUSIC_CHOICES) {
            if (choice == minutes) {
                return minutes;
            }
        }
        return DEFAULT_MUSIC_MINUTES;
    }

    public static int nextMusic(int minutes) {
        for (int i = 0; i < MUSIC_CHOICES.length; i++) {
            if (MUSIC_CHOICES[i] == minutes) {
                return MUSIC_CHOICES[(i + 1) % MUSIC_CHOICES.length];
            }
        }
        return DEFAULT_MUSIC_MINUTES;
    }

    /** A window that starts between 06:00 and 18:00 local is a daytime session. */
    public static String modeFor(ZonedDateTime windowStart) {
        LocalTime t = windowStart.toLocalTime();
        return !t.isBefore(LocalTime.of(6, 0)) && t.isBefore(LocalTime.of(18, 0)) ? MODE_DAY : MODE_NIGHT;
    }

    /** Null when the request must not be sent: the reason is in {@link #refusal}. */
    public static Intent startIntent(String mode, int musicMinutes, long windowEndMs, String requestId, long nowMs) {
        if (refusal(mode, musicMinutes, windowEndMs, requestId, nowMs) != null) {
            return null;
        }
        return new Intent(ACTION_START)
                .setComponent(new ComponentName(PACKAGE, ENTRY))
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_MUSIC_MINUTES, musicMinutes)
                .putExtra(EXTRA_WINDOW_END, windowEndMs)
                .putExtra(EXTRA_REQUEST_ID, requestId);
    }

    public static Intent stopIntent(String requestId) {
        return new Intent(ACTION_STOP)
                .setComponent(new ComponentName(PACKAGE, RECEIVER))
                .putExtra(EXTRA_REQUEST_ID, requestId);
    }

    /** Why a start request is invalid, or null. */
    public static String refusal(String mode, int musicMinutes, long windowEndMs, String requestId, long nowMs) {
        if (!MODE_NIGHT.equals(mode) && !MODE_DAY.equals(mode)) {
            return "unknown mode";
        }
        if (sanitizeMusic(musicMinutes) != musicMinutes) {
            return "invalid music duration";
        }
        if (windowEndMs <= nowMs) {
            return "sleep window already ended";
        }
        if (windowEndMs > nowMs + 24L * 60 * 60 * 1000) {
            return "sleep window more than a day away";
        }
        if (requestId == null || requestId.isEmpty()) {
            return "missing request id";
        }
        return null;
    }
}
