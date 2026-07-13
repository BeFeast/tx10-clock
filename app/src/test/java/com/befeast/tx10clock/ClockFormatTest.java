package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Deterministic text fixtures from visual contract v0.1.0. */
public class ClockFormatTest {

    private static final ZonedDateTime REFERENCE = ZonedDateTime.of(
            2026, 7, 12, 22, 9, 42, 0, ZoneId.of("Asia/Jerusalem"));

    @Test
    public void referenceTwelveHourFrameIsExact() {
        assertEquals("10:09", ClockFormat.main(REFERENCE, false));
        assertEquals("PM SUN, JUL 12 42", ClockFormat.secondary(REFERENCE, false, true));
    }

    @Test
    public void referenceTwentyFourHourFrameIsExact() {
        assertEquals("22:09", ClockFormat.main(REFERENCE, true));
        assertEquals("SUN, JUL 12 42", ClockFormat.secondary(REFERENCE, true, true));
    }

    @Test
    public void hidingDateDropsOnlyTheCompactDate() {
        // 12-hour keeps the AM/PM marker; only the date is removed.
        assertEquals("PM ", ClockFormat.secondaryPrefix(REFERENCE, false, false));
        assertEquals("PM 42", ClockFormat.secondary(REFERENCE, false, true, false));
        assertEquals("PM", ClockFormat.secondary(REFERENCE, false, false, false));
        // 24-hour has no AM/PM, so hiding the date leaves only the seconds.
        assertEquals("", ClockFormat.secondaryPrefix(REFERENCE, true, false));
        assertEquals("42", ClockFormat.secondary(REFERENCE, true, true, false));
        assertEquals("", ClockFormat.secondary(REFERENCE, true, false, false));
        // Showing the date is unchanged from the accepted contract fixtures.
        assertEquals("PM SUN, JUL 12 ", ClockFormat.secondaryPrefix(REFERENCE, false, true));
        assertEquals("PM SUN, JUL 12 42",
                ClockFormat.secondary(REFERENCE, false, true, true));
    }

    @Test
    public void midnightNoonAndSecondsAreStable() {
        ZonedDateTime midnight = REFERENCE.withHour(0).withMinute(0).withSecond(5);
        ZonedDateTime noon = REFERENCE.withHour(12).withMinute(0).withSecond(0);
        assertEquals("12:00", ClockFormat.main(midnight, false));
        assertEquals("AM", ClockFormat.amPm(midnight));
        assertEquals("05", ClockFormat.seconds(midnight));
        assertEquals("12:00", ClockFormat.main(noon, false));
        assertEquals("PM", ClockFormat.amPm(noon));
    }
}
