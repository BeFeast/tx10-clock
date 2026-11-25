package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Phase-0 acceptance cases; all events are synthetic. Times are Asia/Jerusalem. */
public class SleepPlannerTest {

    private static final ZoneId TZ = ZoneId.of("Asia/Jerusalem");
    private static final SleepPlanner.Buffers B = SleepPlanner.Buffers.defaults();
    private static final int EIGHT_HOURS = 8 * 60;

    private static ZonedDateTime t(String local) {
        return LocalDateTime.parse(local).atZone(TZ);
    }

    private static SleepEvent shift(String start, String end) {
        return new SleepEvent(SleepEvent.Kind.SHIFT, "shift", t(start), t(end), true);
    }

    private static SleepEvent meeting(String start, String end) {
        return new SleepEvent(SleepEvent.Kind.MEETING, "meeting", t(start), t(end), true);
    }

    private static SleepPlanner.Plan plan(String now, SleepEvent... events) {
        return SleepPlanner.suggest(t(now), new ArrayList<>(Arrays.asList(events)), B, EIGHT_HOURS);
    }

    @Test
    public void earlyShiftEndsTheWindowAtTheLatestWake() {
        SleepPlanner.Plan p = plan("2026-10-20T18:00", shift("2026-10-21T07:00", "2026-10-21T15:00"));
        assertEquals(SleepPlanner.Status.OK, p.status);
        assertEquals(t("2026-10-20T21:45"), p.start);
        assertEquals(t("2026-10-21T05:45"), p.end);
        assertEquals(t("2026-10-20T21:15"), p.windDownFrom);
        assertTrue(p.reasons.get(0).contains("wake up by 21.10 05:45"));
    }

    @Test
    public void afterANightShiftTheWindowStartsOnceHomeAndWoundDown() {
        SleepPlanner.Plan p = plan("2026-10-21T06:30", shift("2026-10-20T22:00", "2026-10-21T06:00"));
        assertEquals(SleepPlanner.Status.OK, p.status);
        assertEquals(t("2026-10-21T07:00"), p.start);
        assertEquals(t("2026-10-21T15:00"), p.end);
        assertEquals(480, p.plannedMinutes());
    }

    @Test
    public void aMeetingCutsTheWindowAndTheShortfallIsShown() {
        SleepPlanner.Plan p = plan("2026-10-21T06:30", shift("2026-10-20T22:00", "2026-10-21T06:00"),
                meeting("2026-10-21T14:00", "2026-10-21T15:00"));
        assertEquals(SleepPlanner.Status.SHORT, p.status);
        assertEquals(t("2026-10-21T07:00"), p.start);
        assertEquals(t("2026-10-21T13:30"), p.end);
        assertEquals(390, p.plannedMinutes());
        assertEquals(90, p.deficitMinutes());
        assertTrue(String.join("\n", p.reasons).contains("short by 1 h 30 min"));
    }

    @Test
    public void aMeetingRunningWhenSleepWouldStartPushesTheStartWithAReason() {
        SleepPlanner.Plan p = plan("2026-10-21T06:10", shift("2026-10-20T22:00", "2026-10-21T06:00"),
                meeting("2026-10-21T06:30", "2026-10-21T08:00"));
        assertEquals(SleepPlanner.Status.OK, p.status);
        assertEquals(t("2026-10-21T08:30"), p.start);
        assertEquals(t("2026-10-21T16:30"), p.end);
        assertEquals(t("2026-10-21T08:00"), p.windDownFrom);
        assertTrue(String.join("\n", p.reasons).contains("Meeting 06:30–08:00"));
    }

    @Test
    public void anEveningMeetingBeforeAnEarlyShiftShortensTheNight() {
        SleepPlanner.Plan p = plan("2026-10-20T18:00", shift("2026-10-21T07:00", "2026-10-21T15:00"),
                meeting("2026-10-20T21:00", "2026-10-20T23:00"));
        assertEquals(SleepPlanner.Status.SHORT, p.status);
        assertEquals(t("2026-10-20T23:30"), p.start);
        assertEquals(t("2026-10-21T05:45"), p.end);
        assertEquals(105, p.deficitMinutes());
    }

    @Test
    public void aSeriesOfNightsRepeatsThePostShiftWindowOnTheRightDates() {
        SleepEvent first = shift("2026-10-20T22:00", "2026-10-21T06:00");
        SleepEvent second = shift("2026-10-21T22:00", "2026-10-22T06:00");
        SleepPlanner.Plan day1 = plan("2026-10-21T06:30", first, second);
        assertEquals(t("2026-10-21T07:00"), day1.start);
        assertEquals(t("2026-10-21T15:00"), day1.end);
        SleepPlanner.Plan day2 = plan("2026-10-21T16:00", first, second);
        assertEquals(t("2026-10-22T07:00"), day2.start);
        assertEquals(t("2026-10-22T15:00"), day2.end);
    }

    @Test
    public void nightToEarlyDayIsImpossibleAndNeedsReview() {
        SleepPlanner.Plan p = plan("2026-10-21T05:00", shift("2026-10-20T22:00", "2026-10-21T06:00"),
                shift("2026-10-21T07:00", "2026-10-21T15:00"));
        assertEquals(SleepPlanner.Status.NEEDS_REVIEW, p.status);
        assertNull(p.start);
        assertTrue(String.join("\n", p.reasons).contains("needs review"));
    }

    @Test
    public void aNightAcrossTheAutumnDstChangeHasNineElapsedHours() {
        SleepEvent night = shift("2026-10-24T22:00", "2026-10-25T06:00");
        assertEquals(Duration.ofHours(9), Duration.between(night.start, night.end));
        SleepPlanner.Plan p = plan("2026-10-25T06:30", night);
        assertEquals(t("2026-10-25T07:00"), p.start);
        assertEquals(480, p.plannedMinutes());
    }

    @Test
    public void noShiftsMeansNoWindowNotFreeTime() {
        SleepPlanner.Plan p = plan("2026-10-21T06:30", meeting("2026-10-21T14:00", "2026-10-21T15:00"));
        assertEquals(SleepPlanner.Status.NONE, p.status);
        assertNull(p.start);
    }

    @Test
    public void startRequestsCarryOnlyValidSessionFields() {
        long now = t("2026-10-21T07:00").toInstant().toEpochMilli();
        long end = t("2026-10-21T15:00").toInstant().toEpochMilli();
        assertNull(DremlaLink.refusal("day", 45, end, "id", now));
        assertEquals("unknown mode", DremlaLink.refusal("nap", 45, end, "id", now));
        assertEquals("invalid music duration", DremlaLink.refusal("day", 50, end, "id", now));
        assertEquals("sleep window already ended", DremlaLink.refusal("day", 45, now, "id", now));
        assertEquals("missing request id", DremlaLink.refusal("day", 45, end, "", now));
        assertEquals(DremlaLink.MODE_DAY, DremlaLink.modeFor(t("2026-10-21T07:00")));
        assertEquals(DremlaLink.MODE_NIGHT, DremlaLink.modeFor(t("2026-10-20T21:45")));
        List<Integer> seen = new ArrayList<>();
        int m = DremlaLink.DEFAULT_MUSIC_MINUTES;
        for (int i = 0; i < DremlaLink.MUSIC_CHOICES.length; i++) {
            seen.add(m);
            m = DremlaLink.nextMusic(m);
        }
        assertEquals(DremlaLink.MUSIC_CHOICES.length, seen.size());
    }
}
