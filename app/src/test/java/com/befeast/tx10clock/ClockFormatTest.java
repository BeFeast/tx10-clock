package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Fast, deterministic JVM checks for the digital "format" surface: the 12/24-hour
 * boundaries, the AM/PM marker, the seconds line, the compact date, and correct
 * local rendering across the Asia/Jerusalem DST transitions. No Android graphics
 * are needed, so these run as plain JUnit outside the Robolectric sandbox.
 */
public class ClockFormatTest {

    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");

    private static ZonedDateTime utc(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, 0, ZoneOffset.UTC);
    }

    // --- Large hours:minutes block ------------------------------------------

    @Test
    public void hoursMinutes24HourZeroPadded() {
        assertEquals("00:00", ClockFormat.hoursMinutes(utc(2026, 7, 13, 0, 0, 0), true));
        assertEquals("09:07", ClockFormat.hoursMinutes(utc(2026, 7, 13, 9, 7, 0), true));
        assertEquals("14:08", ClockFormat.hoursMinutes(utc(2026, 7, 13, 14, 8, 0), true));
        assertEquals("23:59", ClockFormat.hoursMinutes(utc(2026, 7, 13, 23, 59, 0), true));
    }

    @Test
    public void hoursMinutes12HourNoLeadingZeroAcrossBoundaries() {
        // Midnight and noon both read 12 in 12-hour form.
        assertEquals("12:00", ClockFormat.hoursMinutes(utc(2026, 7, 13, 0, 0, 0), false));
        assertEquals("12:30", ClockFormat.hoursMinutes(utc(2026, 7, 13, 12, 30, 0), false));
        // Single-digit hours have no leading zero; PM hours wrap past 12.
        assertEquals("9:07", ClockFormat.hoursMinutes(utc(2026, 7, 13, 9, 7, 0), false));
        assertEquals("1:05", ClockFormat.hoursMinutes(utc(2026, 7, 13, 13, 5, 0), false));
        assertEquals("11:59", ClockFormat.hoursMinutes(utc(2026, 7, 13, 23, 59, 0), false));
    }

    // --- AM/PM marker --------------------------------------------------------

    @Test
    public void amPmAcrossMiddayAndMidnight() {
        assertEquals("AM", ClockFormat.amPm(utc(2026, 7, 13, 0, 0, 0)));   // midnight
        assertEquals("AM", ClockFormat.amPm(utc(2026, 7, 13, 11, 59, 0))); // just before noon
        assertEquals("PM", ClockFormat.amPm(utc(2026, 7, 13, 12, 0, 0)));  // noon
        assertEquals("PM", ClockFormat.amPm(utc(2026, 7, 13, 23, 0, 0)));  // late evening
    }

    // --- Seconds line --------------------------------------------------------

    @Test
    public void secondsAreZeroPaddedTwoDigits() {
        assertEquals("00", ClockFormat.seconds(utc(2026, 7, 13, 10, 8, 0)));
        assertEquals("05", ClockFormat.seconds(utc(2026, 7, 13, 10, 8, 5)));
        assertEquals("42", ClockFormat.seconds(utc(2026, 7, 13, 10, 8, 42)));
    }

    // --- Compact date --------------------------------------------------------

    @Test
    public void dateIsAbbreviatedAndStable() {
        assertEquals("Mon, 13 Jul 2026", ClockFormat.date(utc(2026, 7, 13, 14, 8, 5)));
        assertEquals("Wed, 1 Jan 2025", ClockFormat.date(utc(2025, 1, 1, 0, 0, 0)));
    }

    // --- Asia/Jerusalem local time & DST transitions -------------------------

    @Test
    public void jerusalemStandardTimeIsUtcPlusTwo() {
        // Mid-January: Israel Standard Time is UTC+02:00.
        ZonedDateTime local = Instant.parse("2026-01-13T12:00:00Z").atZone(JERUSALEM);
        assertEquals(ZoneOffset.ofHours(2), local.getOffset());
        assertEquals("14:00", ClockFormat.hoursMinutes(local, true));
        assertEquals("2:00", ClockFormat.hoursMinutes(local, false));
        assertEquals("PM", ClockFormat.amPm(local));
    }

    @Test
    public void jerusalemDaylightTimeIsUtcPlusThree() {
        // Mid-July: Israel Daylight Time is UTC+03:00.
        ZonedDateTime local = Instant.parse("2026-07-13T12:00:00Z").atZone(JERUSALEM);
        assertEquals(ZoneOffset.ofHours(3), local.getOffset());
        assertEquals("15:00", ClockFormat.hoursMinutes(local, true));
        assertEquals("3:00", ClockFormat.hoursMinutes(local, false));
        assertEquals("PM", ClockFormat.amPm(local));
    }

    @Test
    public void jerusalemSpringForwardSkipsThePartialHour() {
        // Israel springs forward on 2026-03-27 at 02:00 local (00:00Z): the wall
        // clock jumps straight from 01:59:59 (+02:00) to 03:00:00 (+03:00).
        ZonedDateTime before = Instant.parse("2026-03-26T23:59:00Z").atZone(JERUSALEM);
        ZonedDateTime after = Instant.parse("2026-03-27T00:00:00Z").atZone(JERUSALEM);

        assertEquals(ZoneOffset.ofHours(2), before.getOffset());
        assertEquals("01:59", ClockFormat.hoursMinutes(before, true));

        assertEquals(ZoneOffset.ofHours(3), after.getOffset());
        assertEquals("03:00", ClockFormat.hoursMinutes(after, true));
    }

    @Test
    public void jerusalemFallBackReturnsToStandardTime() {
        // Israel falls back on 2026-10-25 at 02:00 local (from +03:00 to +02:00).
        ZonedDateTime before = Instant.parse("2026-10-24T12:00:00Z").atZone(JERUSALEM);
        ZonedDateTime after = Instant.parse("2026-10-26T12:00:00Z").atZone(JERUSALEM);

        assertEquals(ZoneOffset.ofHours(3), before.getOffset());
        assertEquals(ZoneOffset.ofHours(2), after.getOffset());
        // Same 12:00Z instant reads one hour earlier locally after the fall-back.
        assertEquals("15:00", ClockFormat.hoursMinutes(before, true));
        assertEquals("14:00", ClockFormat.hoursMinutes(after, true));
    }
}
