package com.befeast.tx10clock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.io.TimezoneAssignment;
import biweekly.io.TimezoneInfo;
import biweekly.util.DateTimeComponents;
import biweekly.util.ICalDate;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;

/**
 * Reads one iCal feed into the events the sleep plan uses, for a bounded time range.
 *
 * <ul>
 *   <li>Recurrence follows RRULE/RDATE/EXDATE; an instance replaced by a RECURRENCE-ID override is
 *       taken from the override, and a cancelled instance or event is dropped.</li>
 *   <li>Every instance keeps the wall-clock length of its first occurrence, so a 22:00–06:00
 *       shift stays 22:00–06:00 across a DST change (and is then 9 or 7 hours long).</li>
 *   <li>What cannot be planned safely is listed for review instead of guessed: all-day items,
 *       events without an end, times that fall in a DST gap or repeat in a DST overlap,
 *       duplicates and overlapping shifts. A missing event never means free time.</li>
 * </ul>
 */
final class IcsCalendar {

    static final class Result {
        final List<SleepEvent> events;
        final List<String> review;

        Result(List<SleepEvent> events, List<String> review) {
            this.events = Collections.unmodifiableList(events);
            this.review = Collections.unmodifiableList(review);
        }
    }

    private static final int MAX_INSTANCES_PER_EVENT = 500;

    private IcsCalendar() {
    }

    /** @throws IllegalArgumentException when the text is not an iCalendar document */
    static Result read(String ics, CalendarSource source, ZonedDateTime from, ZonedDateTime to) {
        ICalendar cal = Biweekly.parse(ics).first();
        if (cal == null) {
            throw new IllegalArgumentException("not-ics");
        }
        ZoneId display = from.getZone();
        TimezoneInfo tz = cal.getTimezoneInfo();
        List<SleepEvent> events = new ArrayList<>();
        List<String> review = new ArrayList<>();

        // Overrides by UID and the instant of the instance they replace.
        Map<String, Set<Instant>> overridden = new HashMap<>();
        List<VEvent> overrides = new ArrayList<>();
        List<VEvent> masters = new ArrayList<>();
        for (VEvent e : cal.getEvents()) {
            if (e.getRecurrenceId() != null && e.getRecurrenceId().getValue() != null && e.getUid() != null) {
                overrides.add(e);
                Set<Instant> set = overridden.get(e.getUid().getValue());
                if (set == null) {
                    set = new HashSet<>();
                    overridden.put(e.getUid().getValue(), set);
                }
                set.add(e.getRecurrenceId().getValue().toInstant());
            } else {
                masters.add(e);
            }
        }

        for (VEvent e : masters) {
            String title = title(e);
            try {
                if (cancelled(e)) {
                    continue;
                }
                Timing timing = timing(e, tz, display);
                if (timing == null) {
                    if (startsWithin(e, from, to)) {
                        review.add(allDay(e) ? "All day: " + title : "No end time: " + title);
                    }
                    continue;
                }
                Set<Instant> skip = e.getUid() == null ? Collections.<Instant>emptySet()
                        : orEmpty(overridden.get(e.getUid().getValue()));
                if (timing.floating && !skip.isEmpty()) {
                    // Floating RECURRENCE-IDs were read on the JVM zone like the iterator below.
                    Set<Instant> moved = new HashSet<>();
                    for (Instant i : skip) {
                        moved.add(timing.toEventZone(i));
                    }
                    skip = moved;
                }
                boolean recurring = e.getRecurrenceRule() != null || !e.getRecurrenceDates().isEmpty();
                if (!recurring) {
                    add(events, review, source, title, timing, timing.firstStart, from, to);
                    continue;
                }
                DateIterator it = e.getDateIterator(timing.iteratorTimeZone);
                it.advanceTo(Date.from(from.toInstant().minus(timing.wallLength).minus(Duration.ofDays(2))));
                for (int n = 0; it.hasNext() && n < MAX_INSTANCES_PER_EVENT; n++) {
                    Instant start = timing.toEventZone(it.next().toInstant());
                    if (start.isAfter(to.toInstant())) {
                        break;
                    }
                    if (!skip.contains(start)) {
                        add(events, review, source, title, timing, start, from, to);
                    }
                }
            } catch (RuntimeException ex) {
                review.add("Could not read the event: " + title);
            }
        }

        for (VEvent e : overrides) {
            String title = title(e);
            try {
                if (cancelled(e)) {
                    continue;
                }
                Timing timing = timing(e, tz, display);
                if (timing == null) {
                    if (startsWithin(e, from, to)) {
                        review.add("No end time: " + title);
                    }
                    continue;
                }
                add(events, review, source, title, timing, timing.firstStart, from, to);
            } catch (RuntimeException ex) {
                review.add("Could not read the event: " + title);
            }
        }

        Collections.sort(events, (a, b) -> a.start.compareTo(b.start));
        List<SleepEvent> unique = dedupe(events, review);
        flagOverlappingShifts(unique, review);
        return new Result(unique, review);
    }

    /** Start instant, zone and wall-clock length of an event; null when it has no usable time span. */
    private static final class Timing {
        final Instant firstStart;
        final ZoneId zone;
        final boolean floating;
        /** The zone biweekly computes this event's dates in: the JVM zone for floating times. */
        final TimeZone iteratorTimeZone;
        final LocalTime localStart;
        final Duration wallLength;

        Timing(Instant firstStart, ZoneId zone, boolean floating, TimeZone iteratorTimeZone, LocalTime localStart,
                Duration wallLength) {
            this.firstStart = firstStart;
            this.zone = zone;
            this.floating = floating;
            this.iteratorTimeZone = iteratorTimeZone;
            this.localStart = localStart;
            this.wallLength = wallLength;
        }

        /** A biweekly date of this event as the instant of the same wall time in the event's zone. */
        Instant toEventZone(Instant computed) {
            if (!floating) {
                return computed;
            }
            return LocalDateTime.ofInstant(computed, iteratorTimeZone.toZoneId()).atZone(zone).toInstant();
        }
    }

    private static Timing timing(VEvent e, TimezoneInfo tz, ZoneId display) {
        if (e.getDateStart() == null || e.getDateStart().getValue() == null || allDay(e)) {
            return null;
        }
        ICalDate start = e.getDateStart().getValue();
        TimezoneAssignment assignment = tz.getTimezone(e.getDateStart());
        boolean floating = assignment == null && tz.isFloating(e.getDateStart());
        ZoneId zone;
        TimeZone iteratorTimeZone;
        if (assignment != null) {
            iteratorTimeZone = assignment.getTimeZone();
            zone = zoneOf(iteratorTimeZone, display);
        } else if (floating) {
            // No TZID and no Z: the written wall time is local to the clock's own zone.
            zone = display;
            iteratorTimeZone = TimeZone.getDefault();
        } else {
            zone = ZoneOffset.UTC;
            iteratorTimeZone = TimeZone.getTimeZone("UTC");
        }
        Instant first = floating ? written(start).atZone(zone).toInstant() : start.toInstant();
        LocalDateTime localStart = LocalDateTime.ofInstant(first, zone);
        LocalDateTime localEnd;
        if (e.getDateEnd() != null && e.getDateEnd().getValue() != null) {
            ICalDate end = e.getDateEnd().getValue();
            localEnd = tz.isFloating(e.getDateEnd()) ? written(end) : LocalDateTime.ofInstant(end.toInstant(), zone);
        } else if (e.getDuration() != null && e.getDuration().getValue() != null) {
            localEnd = LocalDateTime.ofInstant(first.plusMillis(e.getDuration().getValue().toMillis()), zone);
        } else {
            return null;
        }
        Duration wall = Duration.between(localStart, localEnd);
        if (wall.isNegative() || wall.isZero()) {
            return null;
        }
        // The written wall time: if it fell in a DST gap, the converted instant moved and differs.
        LocalTime written = localStart.toLocalTime();
        DateTimeComponents raw = start.getRawComponents();
        if (raw != null && !raw.isUtc() && assignment != null) {
            written = LocalTime.of(raw.getHour(), raw.getMinute(), raw.getSecond());
        }
        return new Timing(first, zone, floating, iteratorTimeZone, written, wall);
    }

    /** The date-time as written in the feed, before biweekly turned it into an instant. */
    private static LocalDateTime written(ICalDate date) {
        DateTimeComponents raw = date.getRawComponents();
        if (raw == null) {
            return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        }
        return LocalDateTime.of(raw.getYear(), raw.getMonth(), raw.getDate(), raw.getHour(), raw.getMinute(),
                raw.getSecond());
    }

    private static void add(List<SleepEvent> events, List<String> review, CalendarSource source, String title,
            Timing timing, Instant instanceStart, ZonedDateTime from, ZonedDateTime to) {
        LocalDateTime localStart = LocalDateTime.ofInstant(instanceStart, timing.zone);
        LocalDateTime localEnd = localStart.plus(timing.wallLength);
        ZonedDateTime start = localStart.atZone(timing.zone);
        ZonedDateTime end = localEnd.atZone(timing.zone);
        if (!end.isAfter(from) || start.isAfter(to)) {
            return;
        }
        // A recurring time pushed by a DST gap, or a wall time that happens twice, needs the user.
        if (!localStart.toLocalTime().equals(timing.localStart) || ambiguous(timing.zone, localStart)
                || ambiguous(timing.zone, localEnd) || gap(timing.zone, localEnd)) {
            review.add("Time change: missing or repeated time — " + title + " " + SleepPlanner.at(from, start));
            return;
        }
        events.add(new SleepEvent(source.kindOf(title), title, start.withZoneSameInstant(from.getZone()),
                end.withZoneSameInstant(from.getZone()), false));
    }

    private static boolean ambiguous(ZoneId zone, LocalDateTime t) {
        return zone.getRules().getValidOffsets(t).size() > 1;
    }

    private static boolean gap(ZoneId zone, LocalDateTime t) {
        return zone.getRules().getValidOffsets(t).isEmpty();
    }

    private static List<SleepEvent> dedupe(List<SleepEvent> events, List<String> review) {
        List<SleepEvent> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SleepEvent e : events) {
            String key = e.title + "|" + e.start.toInstant() + "|" + e.end.toInstant();
            if (seen.add(key)) {
                unique.add(e);
            } else {
                review.add("Duplicate: " + e.title);
            }
        }
        return unique;
    }

    private static void flagOverlappingShifts(List<SleepEvent> events, List<String> review) {
        SleepEvent last = null;
        for (SleepEvent e : events) {
            if (e.kind != SleepEvent.Kind.SHIFT) {
                continue;
            }
            if (last != null && e.start.isBefore(last.end)) {
                review.add("Shifts overlap: " + last.title + " and " + e.title);
            }
            if (last == null || e.end.isAfter(last.end)) {
                last = e;
            }
        }
    }

    private static ZoneId zoneOf(TimeZone timeZone, ZoneId fallback) {
        try {
            return ZoneId.of(timeZone.getID());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean allDay(VEvent e) {
        return e.getDateStart() != null && e.getDateStart().getValue() != null && !e.getDateStart().getValue().hasTime();
    }

    private static boolean cancelled(VEvent e) {
        return e.getStatus() != null && e.getStatus().isCancelled();
    }

    private static boolean startsWithin(VEvent e, ZonedDateTime from, ZonedDateTime to) {
        if (e.getDateStart() == null || e.getDateStart().getValue() == null) {
            return false;
        }
        Instant start = e.getDateStart().getValue().toInstant();
        return !start.isBefore(from.toInstant().minus(Duration.ofDays(1))) && !start.isAfter(to.toInstant());
    }

    private static String title(VEvent e) {
        String t = e.getSummary() == null ? null : e.getSummary().getValue();
        return t == null || t.trim().isEmpty() ? "(untitled)" : t.trim();
    }

    private static <T> Set<T> orEmpty(Set<T> set) {
        return set == null ? Collections.<T>emptySet() : set;
    }
}
