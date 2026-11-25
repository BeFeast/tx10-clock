package com.befeast.tx10clock;

import java.time.ZonedDateTime;

/**
 * One calendar instance the sleep planner reads: a work shift or a meeting. A meeting never turns
 * into a shift; the kind is decided by the source. Test events are always flagged so the UI can
 * label them and never present them as real calendar data.
 */
public final class SleepEvent {

    public enum Kind { SHIFT, MEETING }

    public final Kind kind;
    public final String title;
    public final ZonedDateTime start;
    public final ZonedDateTime end;
    public final boolean test;

    public SleepEvent(Kind kind, String title, ZonedDateTime start, ZonedDateTime end, boolean test) {
        if (kind == null || title == null || start == null || end == null) {
            throw new IllegalArgumentException("incomplete event");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("event must end after it starts");
        }
        this.kind = kind;
        this.title = title;
        this.start = start;
        this.end = end;
        this.test = test;
    }

    /** A shift that runs through the night: it ends on a later date than it starts. */
    boolean overnight() {
        return end.toLocalDate().isAfter(start.toLocalDate());
    }
}
