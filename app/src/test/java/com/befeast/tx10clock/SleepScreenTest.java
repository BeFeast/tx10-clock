package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Layout;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.TimeZone;

/**
 * Drives the real MainActivity with the remote on a fixed clock: plan, confirm, start Dremla,
 * protected session, explicit wake. Saves a screenshot of each state for review.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SleepScreenTest {

    private static final ZoneId TZ = ZoneId.of("Asia/Jerusalem");
    private TimeZone defaultZone;

    @Before
    public void setUp() {
        defaultZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(TZ));
        at("2026-10-21T06:30");
        debuggable(true);
    }

    private static void debuggable(boolean on) {
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        info.flags = on ? info.flags | ApplicationInfo.FLAG_DEBUGGABLE : info.flags & ~ApplicationInfo.FLAG_DEBUGGABLE;
    }

    @After
    public void tearDown() {
        SleepScreen.clock = Clock.systemUTC();
        TimeZone.setDefault(defaultZone);
    }

    private static void at(String local) {
        SleepScreen.clock = Clock.fixed(LocalDateTime.parse(local).atZone(TZ).toInstant(), TZ);
    }

    private static long epoch(String local) {
        return LocalDateTime.parse(local).atZone(TZ).toInstant().toEpochMilli();
    }

    @Test
    public void planConfirmStartDremlaStayProtectedThenWakeWithoutAutoplay() throws IOException {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertTrue(visibleText(activity).contains("Calendar not connected"));
        shot(activity, "1-clock-calendar-not-connected");

        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        click(activity, "Test event: off");
        String plan = visibleText(activity);
        assertTrue(plan, plan.contains("TEST DATA"));
        assertTrue(plan, plan.contains("07:00 – 15:00 · 8 h"));
        assertTrue(plan, plan.contains("8 h · not confirmed"));
        shot(activity, "2-plan-test-event");
        assertNoSoundEffects(activity.getWindow().getDecorView());

        click(activity, "Confirm plan");
        assertTrue(visibleText(activity).contains("8 h · confirmed"));

        // (Robolectric resolves any explicit component, so the "Dremla not installed" path is device-only.)
        packages(activity).addActivityIfNotPresent(
                new ComponentName(DremlaLink.PACKAGE, DremlaLink.ENTRY));
        click(activity, "Start Dremla");
        ShadowActivity.IntentForResult started = shadow(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        Intent sent = started.intent;
        // Robolectric records the same start in its plain queue too: consume it so only later starts remain.
        assertEquals(sent, shadow(activity).getNextStartedActivity());
        assertEquals(DremlaLink.ACTION_START, sent.getAction());
        assertEquals(new ComponentName(DremlaLink.PACKAGE, DremlaLink.ENTRY), sent.getComponent());
        assertEquals(new HashSet<>(Arrays.asList(DremlaLink.EXTRA_MODE, DremlaLink.EXTRA_MUSIC_MINUTES,
                DremlaLink.EXTRA_WINDOW_END, DremlaLink.EXTRA_REQUEST_ID)), sent.getExtras().keySet());
        assertEquals(DremlaLink.MODE_DAY, sent.getStringExtra(DremlaLink.EXTRA_MODE));
        assertEquals(45, sent.getIntExtra(DremlaLink.EXTRA_MUSIC_MINUTES, 0));
        assertEquals(epoch("2026-10-21T15:00"), sent.getLongExtra(DremlaLink.EXTRA_WINDOW_END, 0));
        PlanStore.Session session = new PlanStore(activity).session();
        assertNotNull(session);
        assertEquals(session.id, sent.getStringExtra(DremlaLink.EXTRA_REQUEST_ID));
        assertProtected(activity);
        shot(activity, "3-session-protected");

        // Dremla accepted; music ends at 07:45 and its night closes: Clock is still protected.
        shadow(activity).receiveResult(sent, Activity.RESULT_OK, new Intent());
        at("2026-10-21T07:50");
        controller.pause().resume();
        assertProtected(activity);
        // The window ends at 15:00: no automatic wake either.
        at("2026-10-21T15:30");
        controller.pause().resume();
        assertProtected(activity);

        press(activity, KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(visibleText(activity).contains("I’m awake / Show the clock"));
        shot(activity, "4-wake-prompt");
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertNull(new PlanStore(activity).session());
        assertEquals(View.VISIBLE, clockView(activity).getVisibility());
        assertNull("waking starts nothing", shadow(activity).getNextStartedActivity());
        assertTrue("music already ended: no stop broadcast", app(activity).getBroadcastIntents().isEmpty());
        shot(activity, "5-clock-after-wake");
    }

    @Test
    public void aRefusalFromDremlaEndsTheSessionAndSaysWhy() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        packages(activity).addActivityIfNotPresent(
                new ComponentName(DremlaLink.PACKAGE, DremlaLink.ENTRY));
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        click(activity, "Test event: off");
        click(activity, "Confirm plan");
        click(activity, "Start Dremla");
        Intent sent = shadow(activity).getNextStartedActivityForResult().intent;
        shadow(activity).receiveResult(sent, Activity.RESULT_CANCELED,
                new Intent().putExtra("com.befeast.sleepmusic.extra.REFUSAL", "repeated request"));
        assertNull(new PlanStore(activity).session());
        assertTrue(visibleText(activity).contains("Dremla did not start: repeated request"));
    }

    @Test
    public void aCalendarChangeNeverRewritesTheConfirmedPlan() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        click(activity, "Test event: off");
        click(activity, "End −15");
        click(activity, "Confirm plan");
        PlanStore.Confirmed confirmed = new PlanStore(activity).confirmed();
        assertEquals(epoch("2026-10-21T14:45"), confirmed.endMs);
        // The source changes (test events off): the suggestion goes, the confirmed plan stays.
        click(activity, "Test event: on");
        PlanStore.Confirmed after = new PlanStore(activity).confirmed();
        assertEquals(confirmed.startMs, after.startMs);
        assertEquals(confirmed.endMs, after.endMs);
        assertTrue(visibleText(activity).contains("calendar changed"));
    }

    @Test
    public void editsAfterConfirmMustBeConfirmedBeforeDremlaStarts() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        packages(activity).addActivityIfNotPresent(new ComponentName(DremlaLink.PACKAGE, DremlaLink.ENTRY));
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        click(activity, "Test event: off");
        click(activity, "Confirm plan");
        click(activity, "End +15");
        assertTrue(visibleText(activity).contains("changes not confirmed"));
        click(activity, "Start Dremla");
        assertTrue(visibleText(activity).contains("Confirm the changes first"));
        assertNull(shadow(activity).getNextStartedActivityForResult());
        assertNull(new PlanStore(activity).session());
        click(activity, "Confirm plan");
        click(activity, "Start Dremla");
        Intent sent = shadow(activity).getNextStartedActivityForResult().intent;
        assertEquals("the confirmed edit is what Dremla receives",
                epoch("2026-10-21T15:15"), sent.getLongExtra(DremlaLink.EXTRA_WINDOW_END, 0));
    }

    @Test
    public void anExpiredPlanNoLongerCountsAndANewSuggestionIsTakenExplicitly() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        new PlanStore(activity).confirm(epoch("2026-10-20T07:00"), epoch("2026-10-20T15:00"), "old");
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        click(activity, "Test event: off");
        String text = visibleText(activity);
        assertTrue(text, text.contains("07:00 – 15:00 · 8 h · not confirmed"));
        // A newer plan confirmed today, then the source moves: the confirmed plan stays until taken.
        click(activity, "Start +15");
        click(activity, "Confirm plan");
        click(activity, "Take suggestion");
        assertTrue(visibleText(activity).contains("changes not confirmed"));
        assertEquals(epoch("2026-10-21T07:15"), new PlanStore(activity).confirmed().startMs);
        click(activity, "Confirm plan");
        assertEquals(epoch("2026-10-21T07:00"), new PlanStore(activity).confirmed().startMs);
    }

    @Test
    public void aReleaseBuildHasNoTestDataAndSaysTheCalendarIsNotConnected() {
        debuggable(false);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        new PlanStore(activity).setTestEvents(true);
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        String text = visibleText(activity);
        assertFalse(text, text.toLowerCase(java.util.Locale.ROOT).contains("test event"));
        assertFalse(text, text.contains("TEST"));
        assertTrue(text, text.contains("Calendar not connected"));
    }

    private static final String FEED = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//synthetic//EN\r\n"
            + "BEGIN:VEVENT\r\nUID:n1@synthetic\r\nSUMMARY:Ночная смена\r\n"
            + "DTSTART;TZID=Asia/Jerusalem:20261020T220000\r\nDTEND;TZID=Asia/Jerusalem:20261021T060000\r\n"
            + "RRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nUID:m1@synthetic\r\nSUMMARY:Встреча с врачом\r\n"
            + "DTSTART;TZID=Asia/Jerusalem:20261021T140000\r\nDTEND;TZID=Asia/Jerusalem:20261021T150000\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nUID:a1@synthetic\r\nSUMMARY:Отпуск\r\nDTSTART;VALUE=DATE:20261023\r\n"
            + "DTEND;VALUE=DATE:20261024\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

    /** A source and a cached feed as a successful fetch leaves them; no network is used. */
    private static void cachedFeed(String fetchedAt, String attemptAt, String error) throws IOException {
        cachedFeed(FEED, fetchedAt, attemptAt, error);
    }

    private static void cachedFeed(String feed, String fetchedAt, String attemptAt, String error) throws IOException {
        android.content.Context app = RuntimeEnvironment.getApplication();
        String sourceJson = "{\"schemaVersion\":1,\"icsUrl\":\"https://calendar.example/ical/synthetic/basic.ics\","
                + "\"shiftKeywords\":[\"смена\"]}";
        java.nio.file.Files.write(new File(ConfigDir.resolve(app), CalendarSource.FILE).toPath(),
                sourceJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fingerprint;
        try {
            fingerprint = CalendarSource.parse(sourceJson).fingerprint();
        } catch (ConfigException e) {
            throw new AssertionError(e);
        }
        java.nio.file.Files.write(new File(app.getFilesDir(), "calendar-cache.ics").toPath(),
                feed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String meta = "source=" + fingerprint + "\nfetchedAt=" + epoch(fetchedAt) + "\nattemptAt=" + epoch(attemptAt) + "\n"
                + (error == null ? "" : "lastError=" + error + "\n");
        java.nio.file.Files.write(new File(app.getFilesDir(), "calendar-cache.properties").toPath(),
                meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void aCalendarFeedDrivesThePlanOnTheRealScreen() throws IOException {
        debuggable(false);
        cachedFeed("2026-10-21T06:20", "2026-10-21T06:20", null);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        String strip = visibleText(activity);
        assertTrue(strip, strip.contains("Calendar updated 06:20 · 1 to review"));
        assertTrue(strip, strip.contains("Встреча с врачом 14:00–15:00") || strip.contains("Ночная смена"));
        shot(activity, "6-calendar-clock");
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        String plan = visibleText(activity);
        assertTrue(plan, plan.contains("07:00 – 13:30 · 6 h 30 min"));
        assertTrue(plan, plan.contains("Meeting at 14:00 − preparation 30 min → wake up by 13:30"));
        assertTrue(plan, plan.contains("short by 1 h 30 min"));
        assertTrue(plan, plan.contains("Review — All day: Отпуск"));
        assertFalse(plan, plan.contains("TEST"));
        shot(activity, "7-calendar-plan");
    }

    @Test
    public void aFailedRefreshKeepsThePlanAndSaysTheCalendarIsStale() throws IOException {
        debuggable(false);
        cachedFeed("2026-10-21T04:00", "2026-10-21T06:25", "http-503");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        String plan = visibleText(activity);
        assertTrue(plan, plan.contains("Calendar out of date: updated 04:00 · http-503"));
        assertTrue("the last good copy still plans", plan.contains("07:00 – 13:30"));
        shot(activity, "8-calendar-stale");
    }

    @Test
    public void tomorrowsPlanFitsOneLineAndTheStatusLineIsReadableAt1280x720() throws IOException {
        // The #52 case seen on the TX10: in the evening the plan is for tomorrow. 8 h 30 min is the longest
        // common plan line. 960x540 dp is the TX10's 1280x720 at tvdpi; shot() lays the window out at it.
        at("2026-10-21T22:24");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        click(activity, "Test event: off");
        click(activity, "Close");
        shot(activity, "9-strip-tomorrow-suggested");
        TextView suggested = shownTextStartingWith(activity, "Sleep ");
        assertEquals("Sleep 22.10 07:00–15:00 · 8 h · suggested", suggested.getText().toString());
        assertFits(suggested, 1);

        press(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("the panel keeps the full span", visibleText(activity).contains("22.10 07:00 – 22.10 15:00 · 8 h"));
        click(activity, "Start −15");
        click(activity, "Start −15");
        click(activity, "Confirm plan");
        click(activity, "Close");
        shot(activity, "10-strip-tomorrow-confirmed");
        TextView plan = shownTextStartingWith(activity, "Sleep ");
        assertEquals("Sleep 22.10 06:30–15:00 · 8 h 30 min · confirmed", plan.getText().toString());
        assertFits(plan, 1);
        TextView status = shownTextStartingWith(activity, "TEST DATA");
        assertFits(status, 1);
        assertReadable(activity, status);
    }

    @Test
    public void theRealCalendarStatusLineIsReadableAndShownWhole() throws IOException {
        debuggable(false);
        cachedFeed("2026-10-21T06:20", "2026-10-21T06:20", null);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(activity, "11-strip-calendar-status");
        TextView status = shownTextStartingWith(activity, "Calendar updated 06:20");
        assertFits(status, 2);
        assertReadable(activity, status);
    }

    @Test
    public void aStaleStatusWithACheckAndALongMeetingTitleKeepsTheMeetingTime() throws IOException {
        // The longest realistic status: stale since yesterday, an error, a check item, tomorrow's meeting.
        debuggable(false);
        String feed = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//synthetic//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:k1@synthetic\r\nSUMMARY:Плановый осмотр у кардиолога\r\n"
                + "DTSTART;TZID=Asia/Jerusalem:20261022T140000\r\nDTEND;TZID=Asia/Jerusalem:20261022T150000\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:a1@synthetic\r\nSUMMARY:Отпуск\r\nDTSTART;VALUE=DATE:20261023\r\n"
                + "DTEND;VALUE=DATE:20261024\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        cachedFeed(feed, "2026-10-20T04:00", "2026-10-21T06:25", "http-503");
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(activity, "12-strip-stale-long-status");
        TextView status = shownTextStartingWith(activity, "Calendar out of date: updated 20.10 04:00 · http-503");
        assertTrue(status.getText().toString(), status.getText().toString().endsWith("Плановый осмотр у кардиолога 22.10 14:00–15:00"));
        assertFits(status, 3);
        assertReadable(activity, status);
    }

    @Test
    public void aRestartDuringASessionComesBackProtected() {
        MainActivity first = Robolectric.buildActivity(MainActivity.class).setup().get();
        new PlanStore(first).beginSession(epoch("2026-10-21T07:00"), epoch("2026-10-21T15:00"), 45, "day");
        MainActivity second = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertProtected(second);
        assertFalse(visibleText(second).contains("Sleep plan"));
    }

    // ---- helpers ----

    // Shadow.extract instead of Shadows.shadowOf: compileSdk 29 cannot resolve its newer overloads.
    private static ShadowActivity shadow(Activity activity) {
        return Shadow.extract(activity);
    }

    private static ShadowPackageManager packages(Activity activity) {
        return Shadow.extract(activity.getPackageManager());
    }

    private static ShadowApplication app(Activity activity) {
        return Shadow.extract(activity.getApplication());
    }

    private static void assertProtected(MainActivity activity) {
        assertNotNull(new PlanStore(activity).session());
        assertEquals(View.INVISIBLE, clockView(activity).getVisibility());
        String text = visibleText(activity);
        assertFalse(text, text.contains("Sleep "));
        assertFalse(text, text.contains("Sleep plan"));
    }

    /** Night UI (#50): no button of the sleep plan plays Android's click or navigation sounds. */
    private static void assertNoSoundEffects(View view) {
        if (view instanceof Button) {
            assertFalse("sound effects on " + ((Button) view).getText(), view.isSoundEffectsEnabled());
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                assertNoSoundEffects(((ViewGroup) view).getChildAt(i));
            }
        }
    }

    private static void press(Activity activity, int keyCode) {
        activity.onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
    }

    private static void click(Activity activity, String label) {
        Button button = find(activity.getWindow().getDecorView(), label);
        assertNotNull("no visible button " + label + " in: " + visibleText(activity), button);
        button.performClick();
    }

    private static Button find(View view, String label) {
        if (view instanceof Button && view.isShown() && label.contentEquals(((Button) view).getText())) {
            return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = find(group.getChildAt(i), label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ClockView clockView(Activity activity) {
        return findClock(activity.getWindow().getDecorView());
    }

    private static ClockView findClock(View view) {
        if (view instanceof ClockView) {
            return (ClockView) view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                ClockView found = findClock(((ViewGroup) view).getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The shown TextView whose text starts with [prefix]; the window must have been laid out (shot). */
    private static TextView shownTextStartingWith(Activity activity, String prefix) {
        TextView found = findText(activity.getWindow().getDecorView(), prefix);
        assertNotNull("no shown text starting with " + prefix + " in: " + visibleText(activity), found);
        return found;
    }

    private static TextView findText(View view, String prefix) {
        if (!view.isShown()) {
            return null;
        }
        if (view instanceof TextView && !(view instanceof Button) && ((TextView) view).getText().toString().startsWith(prefix)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                TextView found = findText(((ViewGroup) view).getChildAt(i), prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** At most [maxLines] lines and nothing cut off with an ellipsis. */
    private static void assertFits(TextView view, int maxLines) {
        Layout layout = view.getLayout();
        assertNotNull("not laid out: " + view.getText(), layout);
        assertTrue(view.getText() + ": " + layout.getLineCount() + " lines", layout.getLineCount() <= maxLines);
        for (int i = 0; i < layout.getLineCount(); i++) {
            assertEquals("ellipsized: " + view.getText(), 0, layout.getEllipsisCount(i));
        }
    }

    /** Readable from the bed: at least 15 sp and a WCAG AAA contrast (7:1) on the black face. */
    private static void assertReadable(Activity activity, TextView view) {
        float sp = view.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity;
        assertTrue("status line is " + sp + " sp", sp >= 15f - 0.01f);
        double contrast = (luminance(view.getCurrentTextColor()) + 0.05) / 0.05;
        assertTrue("status line contrast on black is " + contrast, contrast >= 7.0);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color));
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static String visibleText(Activity activity) {
        StringBuilder out = new StringBuilder();
        collect(activity.getWindow().getDecorView(), out);
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (!view.isShown()) {
            return;
        }
        if (view instanceof TextView) {
            out.append(((TextView) view).getText()).append('\n');
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                collect(((ViewGroup) view).getChildAt(i), out);
            }
        }
    }

    /** Renders the whole window at 1920x1080 into build/sleep-screens/<name>.png. */
    private static void shot(Activity activity, String name) throws IOException {
        View decor = activity.getWindow().getDecorView();
        int w = 1920;
        int h = 1080;
        decor.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, w, h);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        File dir = new File(new File(System.getProperty("golden.output.dir", "build/golden-output")).getParentFile(),
                "sleep-screens");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
