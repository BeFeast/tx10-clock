package com.befeast.tx10clock;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure text formatting for the digital readout. Extracted from the renderer so
 * the exact strings can be asserted in fast JVM unit tests (the "format" static
 * check) without touching Android graphics.
 */
public final class ClockFormat {

    private static final DateTimeFormatter TIME_24H =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final DateTimeFormatter TIME_24H_NO_SECONDS =
            DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter TIME_12H =
            DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US);
    private static final DateTimeFormatter TIME_12H_NO_SECONDS =
            DateTimeFormatter.ofPattern("hh:mm a", Locale.US);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.US);

    private ClockFormat() {
    }

    /** The digital time string honouring {@code use24Hour}/{@code showSeconds}. */
    public static String time(ZonedDateTime now, ClockConfig config) {
        DateTimeFormatter fmt;
        if (config.use24Hour) {
            fmt = config.showSeconds ? TIME_24H : TIME_24H_NO_SECONDS;
        } else {
            fmt = config.showSeconds ? TIME_12H : TIME_12H_NO_SECONDS;
        }
        return now.format(fmt);
    }

    /** The date line, e.g. {@code "Mon, 13 Jul 2026"}. */
    public static String date(ZonedDateTime now) {
        return now.format(DATE);
    }
}
