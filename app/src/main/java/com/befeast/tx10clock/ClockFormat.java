package com.befeast.tx10clock;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure text formatting for the digital readout. Extracted from the renderer so
 * the exact strings can be asserted in fast JVM unit tests (the "format" static
 * check) without touching Android graphics.
 *
 * <p>The accepted layout splits the digital time into independent pieces so the
 * renderer can size and colour them separately: a maximally large
 * {@code hours:minutes} block, a small AM/PM marker, a smaller seconds line, and
 * an English compact date. Each piece is a pure function of a
 * {@link ZonedDateTime} (plus the 12/24-hour flag for the main block), which is
 * what keeps the golden harness deterministic.
 */
public final class ClockFormat {

    private static final DateTimeFormatter HM_24H =
            DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter HM_12H =
            DateTimeFormatter.ofPattern("h:mm", Locale.US);
    private static final DateTimeFormatter AM_PM =
            DateTimeFormatter.ofPattern("a", Locale.US);
    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("ss", Locale.US);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US);

    private ClockFormat() {
    }

    /**
     * The large {@code hours:minutes} block. In 12-hour mode the hour has no
     * leading zero (e.g. {@code "9:07"}, {@code "12:00"}); in 24-hour mode it is
     * zero-padded (e.g. {@code "00:00"}, {@code "23:59"}). Seconds are never part
     * of this block &mdash; they live in {@link #seconds(ZonedDateTime)}.
     */
    public static String hoursMinutes(ZonedDateTime now, boolean use24Hour) {
        return now.format(use24Hour ? HM_24H : HM_12H);
    }

    /** The AM/PM marker for the 12-hour readout, e.g. {@code "AM"} / {@code "PM"}. */
    public static String amPm(ZonedDateTime now) {
        return now.format(AM_PM);
    }

    /** The two-digit seconds shown in the smaller second line, e.g. {@code "05"}. */
    public static String seconds(ZonedDateTime now) {
        return now.format(SECONDS);
    }

    /** The date line, e.g. {@code "Mon, 13 Jul 2026"}. */
    public static String date(ZonedDateTime now) {
        return now.format(DATE);
    }
}
