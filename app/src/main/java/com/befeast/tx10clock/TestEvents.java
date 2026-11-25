package com.befeast.tx10clock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Explicitly labelled test data for the plan screen while no calendar is connected: two night
 * shifts 22:00–06:00, placed so the post-shift window is current or next. Never shown as real
 * calendar events; the UI marks every one of them as a test.
 */
final class TestEvents {

    static final String TITLE = "TEST: night shift";

    private TestEvents() {
    }

    static List<SleepEvent> nightShifts(ZonedDateTime now) {
        ZoneId zone = now.getZone();
        LocalDate first = now.toLocalTime().isBefore(LocalTime.of(15, 0))
                ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        List<SleepEvent> events = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            LocalDate day = first.plusDays(i);
            events.add(new SleepEvent(SleepEvent.Kind.SHIFT, TITLE,
                    day.atTime(22, 0).atZone(zone), day.plusDays(1).atTime(6, 0).atZone(zone), true));
        }
        return events;
    }
}
