package com.befeast.tx10clock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * The user's confirmed sleep plan and the active sleep session, kept across restarts. A calendar
 * refresh never writes here: only the user's confirm, start and wake actions do. The session is
 * committed synchronously before Dremla is started, so a crash or reboot comes back protected.
 */
public final class PlanStore {

    private static final String PREFS = "sleep_plan";
    private static final String CONFIRMED_START = "confirmed_start";
    private static final String CONFIRMED_END = "confirmed_end";
    private static final String CONFIRMED_SUGGESTION = "confirmed_suggestion";
    private static final String MUSIC_MINUTES = "music_minutes";
    private static final String TEST_EVENTS = "test_events";
    private static final String SESSION_ID = "session_id";
    private static final String SESSION_STARTED = "session_started";
    private static final String SESSION_WINDOW_END = "session_window_end";
    private static final String SESSION_MUSIC = "session_music";
    private static final String SESSION_MODE = "session_mode";

    /** A confirmed plan: epoch milliseconds; {@code suggestionKey} identifies the suggestion it came from. */
    public static final class Confirmed {
        public final long startMs;
        public final long endMs;
        public final String suggestionKey;

        Confirmed(long startMs, long endMs, String suggestionKey) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.suggestionKey = suggestionKey;
        }
    }

    public static final class Session {
        public final String id;
        public final long startedMs;
        public final long windowEndMs;
        public final int musicMinutes;
        public final String mode;

        Session(String id, long startedMs, long windowEndMs, int musicMinutes, String mode) {
            this.id = id;
            this.startedMs = startedMs;
            this.windowEndMs = windowEndMs;
            this.musicMinutes = musicMinutes;
            this.mode = mode;
        }
    }

    private final SharedPreferences prefs;

    public PlanStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Confirmed confirmed() {
        long start = prefs.getLong(CONFIRMED_START, 0);
        long end = prefs.getLong(CONFIRMED_END, 0);
        if (start <= 0 || end <= start) {
            return null;
        }
        return new Confirmed(start, end, prefs.getString(CONFIRMED_SUGGESTION, ""));
    }

    public void confirm(long startMs, long endMs, String suggestionKey) {
        prefs.edit().putLong(CONFIRMED_START, startMs).putLong(CONFIRMED_END, endMs)
                .putString(CONFIRMED_SUGGESTION, suggestionKey).commit();
    }

    public void clearConfirmed() {
        prefs.edit().remove(CONFIRMED_START).remove(CONFIRMED_END).remove(CONFIRMED_SUGGESTION).commit();
    }

    public int musicMinutes() {
        return DremlaLink.sanitizeMusic(prefs.getInt(MUSIC_MINUTES, DremlaLink.DEFAULT_MUSIC_MINUTES));
    }

    public void setMusicMinutes(int minutes) {
        prefs.edit().putInt(MUSIC_MINUTES, DremlaLink.sanitizeMusic(minutes)).apply();
    }

    public boolean testEvents() {
        return prefs.getBoolean(TEST_EVENTS, false);
    }

    public void setTestEvents(boolean enabled) {
        prefs.edit().putBoolean(TEST_EVENTS, enabled).apply();
    }

    public Session session() {
        String id = prefs.getString(SESSION_ID, null);
        if (id == null) {
            return null;
        }
        return new Session(id, prefs.getLong(SESSION_STARTED, 0), prefs.getLong(SESSION_WINDOW_END, 0),
                prefs.getInt(SESSION_MUSIC, 0), prefs.getString(SESSION_MODE, ""));
    }

    /** Records a new session before Dremla starts; returns it, or null when one is already active. */
    public Session beginSession(long nowMs, long windowEndMs, int musicMinutes, String mode) {
        if (session() != null) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        boolean saved = prefs.edit().putString(SESSION_ID, id).putLong(SESSION_STARTED, nowMs)
                .putLong(SESSION_WINDOW_END, windowEndMs).putInt(SESSION_MUSIC, musicMinutes)
                .putString(SESSION_MODE, mode).commit();
        return saved ? session() : null;
    }

    /** Only the explicit "I'm awake / Show clock" action (or a refused start) ends a session. */
    public void endSession() {
        prefs.edit().remove(SESSION_ID).remove(SESSION_STARTED).remove(SESSION_WINDOW_END)
                .remove(SESSION_MUSIC).remove(SESSION_MODE).commit();
    }
}
