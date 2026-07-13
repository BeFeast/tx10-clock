package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Fast JVM checks for the digital "format" surface. No Android graphics needed,
 * so these run as plain JUnit outside the Robolectric sandbox.
 */
public class ClockFormatTest {

    private static final ZonedDateTime T =
            ZonedDateTime.of(2026, 7, 13, 14, 8, 5, 0, ZoneOffset.UTC);

    @Test
    public void time24HourWithSeconds() {
        ClockConfig cfg = new ClockConfig.Builder().use24Hour(true).showSeconds(true).build();
        assertEquals("14:08:05", ClockFormat.time(T, cfg));
    }

    @Test
    public void time24HourWithoutSeconds() {
        ClockConfig cfg = new ClockConfig.Builder().use24Hour(true).showSeconds(false).build();
        assertEquals("14:08", ClockFormat.time(T, cfg));
    }

    @Test
    public void time12HourWithSeconds() {
        ClockConfig cfg = new ClockConfig.Builder().use24Hour(false).showSeconds(true).build();
        assertEquals("02:08:05 PM", ClockFormat.time(T, cfg));
    }

    @Test
    public void time12HourWithoutSeconds() {
        ClockConfig cfg = new ClockConfig.Builder().use24Hour(false).showSeconds(false).build();
        assertEquals("02:08 PM", ClockFormat.time(T, cfg));
    }

    @Test
    public void dateIsAbbreviatedAndStable() {
        assertEquals("Mon, 13 Jul 2026", ClockFormat.date(T));
    }
}
