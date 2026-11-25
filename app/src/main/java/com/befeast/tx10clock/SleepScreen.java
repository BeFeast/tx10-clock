package com.befeast.tx10clock;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The sleep-plan layer over the clock: a two-line strip under the calendar, the plan panel driven
 * by the remote, and the protected state of an active sleep session. During a session the clock
 * face, ticks and cards are not drawn; only the explicit "I'm awake / Show clock" action ends it.
 */
final class SleepScreen {

    static final int REQUEST_DREMLA = 4201;
    private static final int ACCENT = 0xFFFF9F0A;
    private static final int TEXT = 0xFFF2F2F7;
    private static final int MUTED = 0xFF8E8E93;
    /** The strip's status line: readable from the bed (about 12:1 on the black face). */
    private static final int STATUS = 0xFFC7C7CC;
    private static final int WARN = 0xFFFF6961;
    private static final int STEP_MINUTES = 15;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Injectable for tests; production uses the system clock. */
    static Clock clock = Clock.systemUTC();

    /** The calendar fetch runs off the UI thread, one at a time. */
    private static final ExecutorService CALENDAR_IO = Executors.newSingleThreadExecutor();
    private static final long CHECK_EVERY_MS = 5 * 60_000L;

    private final Activity activity;
    private final ClockView clockView;
    private final PlanStore store;
    private final CalendarRepository calendar;
    private CalendarRepository.Snapshot snapshot;
    private boolean fetching;
    private ZoneId zone = ZoneId.systemDefault();

    private final LinearLayout strip;
    private final TextView stripSource;
    private final TextView stripPlan;
    private final FrameLayout panel;
    private final TextView panelSource;
    private final TextView panelEvent;
    private final TextView panelWindow;
    private final TextView panelReasons;
    private final TextView panelMessage;
    private final Button confirmButton;
    private final Button musicButton;
    private final Button startButton;
    private final Button testButton;
    private final Button takeSuggestionButton;
    /** Labelled test events exist only in debuggable builds; release always reads the real source. */
    private final boolean debuggable;
    private final FrameLayout protectedLayer;
    private final TextView wakePrompt;

    private SleepPlanner.Plan suggestion;
    private ZonedDateTime draftStart;
    private ZonedDateTime draftEnd;

    SleepScreen(Activity activity, FrameLayout root, ClockView clockView) {
        this.activity = activity;
        this.clockView = clockView;
        this.store = new PlanStore(activity);
        this.debuggable = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        this.calendar = new CalendarRepository(ConfigDir.resolve(activity), activity.getFilesDir(),
                new CalendarRepository.HttpsFetcher(), new LiveClock());

        strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.VERTICAL);
        // At 1280x720 (960x540 dp) the plan stays on one line and the status line reads from the bed (#52).
        stripSource = text(15, STATUS, false);
        // Up to three lines, so a stale line with a check and a long event title keeps the event's time.
        stripSource.setMaxLines(3);
        stripSource.setEllipsize(TextUtils.TruncateAt.END);
        stripPlan = text(16, TEXT, true);
        stripPlan.setSingleLine(true);
        stripPlan.setEllipsize(TextUtils.TruncateAt.END);
        strip.addView(stripSource);
        strip.addView(stripPlan);
        FrameLayout.LayoutParams stripParams = new FrameLayout.LayoutParams(dp(420), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        stripParams.setMargins(0, 0, dp(45), dp(18));
        root.addView(strip, stripParams);

        panel = new FrameLayout(activity);
        // A night UI: none of the sleep layer's own views plays Android's click or navigation sounds.
        panel.setSoundEffectsEnabled(false);
        panel.setBackgroundColor(0xE6000000);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(20), dp(28), dp(20));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF17120A);
        cardBg.setStroke(dp(1), 0xFF3A2A10);
        cardBg.setCornerRadius(dp(14));
        card.setBackground(cardBg);
        TextView title = text(20, TEXT, true);
        title.setText(R.string.sleep_plan_title);
        panelSource = text(12, MUTED, false);
        panelEvent = text(14, TEXT, false);
        panelWindow = text(26, ACCENT, true);
        panelReasons = text(12, MUTED, false);
        panelMessage = text(13, WARN, true);
        card.addView(title);
        card.addView(panelSource);
        card.addView(space(8));
        card.addView(panelEvent);
        card.addView(panelWindow);
        card.addView(panelReasons);
        card.addView(space(10));

        LinearLayout edit = row();
        edit.addView(button(R.string.sleep_start_earlier, v -> nudge(-STEP_MINUTES, 0)));
        edit.addView(button(R.string.sleep_start_later, v -> nudge(STEP_MINUTES, 0)));
        edit.addView(button(R.string.sleep_end_earlier, v -> nudge(0, -STEP_MINUTES)));
        edit.addView(button(R.string.sleep_end_later, v -> nudge(0, STEP_MINUTES)));
        card.addView(edit);
        LinearLayout act = row();
        confirmButton = button(R.string.sleep_confirm, v -> confirm());
        musicButton = button(R.string.sleep_music, v -> cycleMusic());
        startButton = button(R.string.sleep_start_dremla, v -> startDremla());
        act.addView(confirmButton);
        act.addView(musicButton);
        act.addView(startButton);
        card.addView(act);
        LinearLayout extra = row();
        takeSuggestionButton = button(R.string.sleep_take_suggestion, v -> takeSuggestion());
        extra.addView(takeSuggestionButton);
        testButton = button(R.string.sleep_test_events, v -> toggleTestEvents());
        testButton.setVisibility(debuggable ? View.VISIBLE : View.GONE);
        extra.addView(testButton);
        extra.addView(button(R.string.sleep_close, v -> closePanel()));
        card.addView(extra);
        card.addView(panelMessage);
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(dp(800), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        panel.addView(card, cardParams);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        protectedLayer = new FrameLayout(activity);
        protectedLayer.setSoundEffectsEnabled(false);
        protectedLayer.setBackgroundColor(Color.BLACK);
        wakePrompt = text(16, 0xFF6E6E73, false);
        wakePrompt.setGravity(Gravity.CENTER);
        wakePrompt.setVisibility(View.GONE);
        protectedLayer.addView(wakePrompt, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        protectedLayer.setVisibility(View.GONE);
        root.addView(protectedLayer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    void setZone(ZoneId zone) {
        this.zone = zone;
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(clock).withZoneSameInstant(zone);
    }

    boolean sessionActive() {
        return store.session() != null;
    }

    /** While Clock is in front: re-read the calendar every few minutes; a fetch runs only when due. */
    void start() {
        strip.removeCallbacks(periodic);
        strip.postDelayed(periodic, CHECK_EVERY_MS);
    }

    void stop() {
        strip.removeCallbacks(periodic);
    }

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            refresh();
            strip.postDelayed(this, CHECK_EVERY_MS);
        }
    };

    private void maybeFetch() {
        if (fetching || testData() || !calendar.refreshDue()) {
            return;
        }
        fetching = true;
        CALENDAR_IO.execute(() -> {
            calendar.refresh();
            activity.runOnUiThread(() -> {
                fetching = false;
                refresh();
            });
        });
    }

    /** Recomputes the suggestion and redraws; never changes the confirmed plan or a session. */
    void refresh() {
        ZonedDateTime now = now();
        snapshot = calendar.snapshot(now);
        maybeFetch();
        List<SleepEvent> events = events(now);
        suggestion = SleepPlanner.suggest(now, events, SleepPlanner.Buffers.defaults(), 8 * 60);
        if (draftStart == null) {
            resetDraft();
        }
        boolean session = sessionActive();
        protectedLayer.setVisibility(session ? View.VISIBLE : View.GONE);
        clockView.setVisibility(session ? View.INVISIBLE : View.VISIBLE);
        strip.setVisibility(session ? View.GONE : View.VISIBLE);
        if (session) {
            panel.setVisibility(View.GONE);
            clockView.stop();
        }
        drawStrip(now, events);
        drawPanel(now, events);
    }

    private boolean testData() {
        return debuggable && store.testEvents();
    }

    private List<SleepEvent> events(ZonedDateTime now) {
        return testData() ? TestEvents.nightShifts(now) : snapshot.events;
    }

    /** The confirmed plan while its window has not ended; an expired plan no longer counts. */
    private PlanStore.Confirmed confirmedNow() {
        PlanStore.Confirmed confirmed = store.confirmed();
        return confirmed != null && confirmed.endMs > now().toInstant().toEpochMilli() ? confirmed : null;
    }

    private boolean draftIs(long startMs, long endMs) {
        return draftStart != null && draftStart.toInstant().toEpochMilli() == startMs
                && draftEnd.toInstant().toEpochMilli() == endMs;
    }

    private boolean draftMatchesConfirmed() {
        PlanStore.Confirmed confirmed = confirmedNow();
        return confirmed != null && draftIs(confirmed.startMs, confirmed.endMs);
    }

    private void resetDraft() {
        PlanStore.Confirmed confirmed = confirmedNow();
        if (confirmed != null) {
            draftStart = Instant.ofEpochMilli(confirmed.startMs).atZone(zone);
            draftEnd = Instant.ofEpochMilli(confirmed.endMs).atZone(zone);
        } else if (suggestion != null && suggestion.start != null) {
            draftStart = suggestion.start;
            draftEnd = suggestion.end;
        } else {
            draftStart = null;
            draftEnd = null;
        }
    }

    private void drawStrip(ZonedDateTime now, List<SleepEvent> events) {
        stripSource.setText(sourceLine(now) + nextEvent(now, events));
        stripSource.setTextColor(!testData() && snapshot.configured && snapshot.stale ? WARN : STATUS);
        PlanStore.Confirmed confirmed = confirmedNow();
        if (confirmed != null) {
            stripPlan.setText(activity.getString(R.string.sleep_strip_confirmed,
                    range(now, confirmed.startMs, confirmed.endMs)));
        } else if (suggestion.start != null) {
            stripPlan.setText(activity.getString(R.string.sleep_strip_suggested,
                    stripSpan(now, suggestion.start, suggestion.end)));
        } else if (suggestion.status == SleepPlanner.Status.NEEDS_REVIEW) {
            stripPlan.setText(R.string.sleep_needs_review);
        } else {
            stripPlan.setText(R.string.sleep_strip_open_plan);
        }
    }

    /** Where the events come from and how fresh they are; stale and errors are said, not hidden. */
    private String sourceLine(ZonedDateTime now) {
        if (testData()) {
            return activity.getString(R.string.sleep_source_test);
        }
        if (!snapshot.configured) {
            return activity.getString(snapshot.configError != null ? R.string.sleep_source_broken
                    : R.string.sleep_source_not_connected);
        }
        String line;
        if (snapshot.fetchedAtMs == 0) {
            line = snapshot.lastError == null ? activity.getString(R.string.sleep_source_loading)
                    : activity.getString(R.string.sleep_source_never, snapshot.lastError);
        } else {
            String at = SleepPlanner.at(now, Instant.ofEpochMilli(snapshot.fetchedAtMs).atZone(zone));
            line = snapshot.stale
                    ? activity.getString(R.string.sleep_source_stale, at,
                            snapshot.lastError == null ? activity.getString(R.string.sleep_source_old) : snapshot.lastError)
                    : activity.getString(R.string.sleep_source_ok, at);
        }
        if (!snapshot.review.isEmpty()) {
            line += activity.getString(R.string.sleep_review_count, snapshot.review.size());
        }
        return line;
    }

    private String nextEvent(ZonedDateTime now, List<SleepEvent> events) {
        for (SleepEvent e : events) {
            if (e.end.isAfter(now)) {
                return " · " + e.title + " " + SleepPlanner.at(now, e.start) + "–" + TIME.format(e.end.withZoneSameInstant(zone));
            }
        }
        return "";
    }

    private void drawPanel(ZonedDateTime now, List<SleepEvent> events) {
        if (testData()) {
            panelSource.setText(R.string.sleep_panel_source_test);
        } else if (snapshot.configured) {
            panelSource.setText(sourceLine(now) + "\n" + activity.getString(R.string.sleep_shift_rule));
        } else {
            panelSource.setText(snapshot.configError != null
                    ? activity.getString(R.string.sleep_source_config_error, snapshot.configError)
                    : activity.getString(debuggable ? R.string.sleep_panel_source_not_connected
                            : R.string.sleep_panel_source_not_connected_release));
        }
        String event = nextEvent(now, events);
        panelEvent.setText(event.isEmpty() ? activity.getString(R.string.sleep_no_events) : event.substring(3));
        PlanStore.Confirmed confirmed = confirmedNow();
        StringBuilder reasons = new StringBuilder();
        for (String r : suggestion.reasons) {
            reasons.append(r).append('\n');
        }
        if (draftStart != null) {
            String state;
            if (confirmed == null) {
                state = activity.getString(R.string.sleep_state_draft);
            } else if (!draftIs(confirmed.startMs, confirmed.endMs)) {
                state = activity.getString(R.string.sleep_state_changed);
            } else if (!confirmed.suggestionKey.equals(key(suggestion))) {
                state = activity.getString(R.string.sleep_state_review);
            } else {
                state = activity.getString(R.string.sleep_state_confirmed);
            }
            panelWindow.setText(span(now, draftStart, draftEnd) + " · " + state);
        } else {
            panelWindow.setText(suggestion.status == SleepPlanner.Status.NEEDS_REVIEW
                    ? activity.getString(R.string.sleep_needs_review) : activity.getString(R.string.sleep_no_window));
        }
        if (!testData()) {
            for (int i = 0; i < Math.min(3, snapshot.review.size()); i++) {
                reasons.append(activity.getString(R.string.sleep_review_item, snapshot.review.get(i))).append('\n');
            }
        }
        if (confirmed != null && !confirmed.suggestionKey.equals(key(suggestion)) && suggestion.start != null) {
            reasons.append(activity.getString(R.string.sleep_new_suggestion, span(now, suggestion.start, suggestion.end)));
        }
        panelReasons.setText(reasons.toString().trim());
        musicButton.setText(activity.getString(R.string.sleep_music, store.musicMinutes()));
        testButton.setText(store.testEvents() ? R.string.sleep_test_events_on : R.string.sleep_test_events_off);
        startButton.setEnabled(draftMatchesConfirmed());
        confirmButton.setEnabled(draftStart != null);
        takeSuggestionButton.setEnabled(suggestion.start != null
                && !draftIs(suggestion.start.toInstant().toEpochMilli(), suggestion.end.toInstant().toEpochMilli()));
    }

    // ---- remote ----

    /** Returns true when the key was consumed by the sleep layer. */
    boolean onKeyDown(int keyCode) {
        if (sessionActive()) {
            if (wakePrompt.getVisibility() == View.VISIBLE && isOk(keyCode)) {
                wake();
            } else if (keyCode == KeyEvent.KEYCODE_BACK) {
                wakePrompt.setVisibility(View.GONE);
            } else {
                showWakePrompt();
            }
            return true;
        }
        if (panel.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closePanel();
                return true;
            }
            return false;
        }
        if (isOk(keyCode) || keyCode == KeyEvent.KEYCODE_MENU) {
            openPanel();
            return true;
        }
        return false;
    }

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    void openPanel() {
        resetDraft();
        panelMessage.setText("");
        refresh();
        panel.setVisibility(View.VISIBLE);
        confirmButton.requestFocus();
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        resetDraft();
        refresh();
    }

    private void nudge(int startMinutes, int endMinutes) {
        if (draftStart == null) {
            return;
        }
        ZonedDateTime start = draftStart.plusMinutes(startMinutes);
        ZonedDateTime end = draftEnd.plusMinutes(endMinutes);
        if (!end.isAfter(start.plusMinutes(STEP_MINUTES))) {
            return;
        }
        draftStart = start;
        draftEnd = end;
        panelMessage.setText(R.string.sleep_edited);
        drawPanel(now(), events(now()));
    }

    private void confirm() {
        if (draftStart == null) {
            return;
        }
        store.confirm(draftStart.toInstant().toEpochMilli(), draftEnd.toInstant().toEpochMilli(), key(suggestion));
        panelMessage.setText("");
        refresh();
        startButton.requestFocus();
    }

    /** Explicitly adopts the current suggestion as the draft; it still needs a confirm. */
    private void takeSuggestion() {
        if (suggestion.start == null) {
            return;
        }
        draftStart = suggestion.start;
        draftEnd = suggestion.end;
        panelMessage.setText(R.string.sleep_edited);
        drawPanel(now(), events(now()));
    }

    private void cycleMusic() {
        store.setMusicMinutes(DremlaLink.nextMusic(store.musicMinutes()));
        drawPanel(now(), events(now()));
    }

    private void toggleTestEvents() {
        store.setTestEvents(!store.testEvents());
        draftStart = null;
        refresh();
    }

    private void startDremla() {
        ZonedDateTime now = now();
        long nowMs = now.toInstant().toEpochMilli();
        PlanStore.Confirmed confirmed = confirmedNow();
        if (confirmed == null) {
            panelMessage.setText(R.string.sleep_confirm_first);
            return;
        }
        if (!draftMatchesConfirmed()) {
            panelMessage.setText(R.string.sleep_confirm_changes);
            return;
        }
        String mode = DremlaLink.modeFor(Instant.ofEpochMilli(confirmed.startMs).atZone(zone));
        int music = store.musicMinutes();
        String refusal = DremlaLink.refusal(mode, music, confirmed.endMs, "pending", nowMs);
        if (refusal != null) {
            panelMessage.setText(activity.getString(R.string.sleep_refused, refusal));
            return;
        }
        Intent probe = DremlaLink.startIntent(mode, music, confirmed.endMs, "probe", nowMs);
        if (probe == null || probe.resolveActivity(activity.getPackageManager()) == null) {
            panelMessage.setText(R.string.sleep_dremla_missing);
            return;
        }
        PlanStore.Session session = store.beginSession(nowMs, confirmed.endMs, music, mode);
        if (session == null) {
            panelMessage.setText(R.string.sleep_session_exists);
            return;
        }
        Intent intent = DremlaLink.startIntent(mode, music, confirmed.endMs, session.id, nowMs);
        try {
            activity.startActivityForResult(intent, REQUEST_DREMLA);
        } catch (RuntimeException e) {
            store.endSession();
            panelMessage.setText(R.string.sleep_dremla_missing);
            return;
        }
        refresh();
    }

    /** Dremla's answer to the start request; only a refusal ends the session here. */
    void onDremlaResult(int resultCode, Intent data) {
        PlanStore.Session session = store.session();
        String refusal = data == null ? null : data.getStringExtra("com.befeast.sleepmusic.extra.REFUSAL");
        if (session != null && refusal != null) {
            store.endSession();
            refresh();
            openPanel();
            panelMessage.setText(activity.getString(R.string.sleep_refused, refusal));
            return;
        }
        refresh();
    }

    private void showWakePrompt() {
        PlanStore.Session session = store.session();
        String until = session == null ? "" : SleepPlanner.at(now(), Instant.ofEpochMilli(session.windowEndMs).atZone(zone));
        wakePrompt.setText(activity.getString(R.string.sleep_wake_prompt, until));
        wakePrompt.setVisibility(View.VISIBLE);
        wakePrompt.removeCallbacks(hidePrompt);
        wakePrompt.postDelayed(hidePrompt, 15_000);
    }

    private final Runnable hidePrompt = new Runnable() {
        @Override
        public void run() {
            wakePrompt.setVisibility(View.GONE);
        }
    };

    /** The explicit "I'm awake / Show clock": ends the session; nothing starts playing. */
    private void wake() {
        PlanStore.Session session = store.session();
        if (session != null) {
            long musicEnd = session.startedMs + session.musicMinutes * 60_000L;
            if (now().toInstant().toEpochMilli() < musicEnd) {
                activity.sendBroadcast(DremlaLink.stopIntent(session.id));
            }
        }
        store.endSession();
        wakePrompt.setVisibility(View.GONE);
        clockView.setVisibility(View.VISIBLE);
        clockView.start();
        refresh();
    }

    /** Follows {@link #clock}, which tests replace after construction. */
    private static final class LiveClock extends Clock {
        @Override
        public ZoneId getZone() {
            return clock.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return clock.withZone(zone);
        }

        @Override
        public Instant instant() {
            return clock.instant();
        }
    }

    // ---- formatting and widgets ----

    static String key(SleepPlanner.Plan plan) {
        return plan.status + ":" + (plan.start == null ? "" : plan.start.toInstant() + "/" + plan.end.toInstant());
    }

    private String range(ZonedDateTime now, long startMs, long endMs) {
        return stripSpan(now, Instant.ofEpochMilli(startMs).atZone(zone), Instant.ofEpochMilli(endMs).atZone(zone));
    }

    /** The strip's short form, one date at most: "22.10 07:00–15:00 · 8 h". The panel keeps the full span. */
    private static String stripSpan(ZonedDateTime now, ZonedDateTime start, ZonedDateTime end) {
        long minutes = java.time.Duration.between(start, end).toMinutes();
        return SleepPlanner.at(now, start) + "–" + TIME.format(end.withZoneSameInstant(now.getZone()))
                + " · " + SleepPlanner.hours(minutes);
    }

    private static String span(ZonedDateTime now, ZonedDateTime start, ZonedDateTime end) {
        long minutes = java.time.Duration.between(start, end).toMinutes();
        return SleepPlanner.at(now, start) + " – " + SleepPlanner.at(now, end) + " · " + SleepPlanner.hours(minutes);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private View space(int heightDp) {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));
        return r;
    }

    private Button button(int label, View.OnClickListener listener) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(TEXT);
        b.setFocusable(true);
        // Click and D-pad focus sounds are played per view; the sleep plan stays silent at night.
        b.setSoundEffectsEnabled(false);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, pill(ACCENT, 0x55FF9F0A));
        bg.addState(new int[]{-android.R.attr.state_enabled}, pill(0xFF2C2C2E, 0xFF111111));
        bg.addState(new int[]{}, pill(0xFF3A3A3C, 0xFF222224));
        b.setBackground(bg);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(0, 0, dp(10), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable pill(int stroke, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(2), stroke);
        d.setCornerRadius(dp(20));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
