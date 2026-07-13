package com.befeast.tx10clock;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Pure formatting for the two-line digital clock contract. */
public final class ClockFormat {

    private static final DateTimeFormatter MAIN_24 =
            DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter MAIN_12 =
            DateTimeFormatter.ofPattern("h:mm", Locale.US);
    private static final DateTimeFormatter AM_PM =
            DateTimeFormatter.ofPattern("a", Locale.US);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);
    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("ss", Locale.US);

    private ClockFormat() {
    }

    /** Large first line, e.g. {@code 10:09} or {@code 22:09}. */
    public static String main(ZonedDateTime now, boolean use24Hour) {
        return now.format(use24Hour ? MAIN_24 : MAIN_12);
    }

    /** Compact English date without a year, e.g. {@code SUN, JUL 12}. */
    public static String compactDate(ZonedDateTime now) {
        return now.format(DATE).toUpperCase(Locale.US);
    }

    public static String amPm(ZonedDateTime now) {
        return now.format(AM_PM).toUpperCase(Locale.US);
    }

    public static String seconds(ZonedDateTime now) {
        return now.format(SECONDS);
    }

    /** Grey portion of the second line; orange seconds are drawn separately. */
    public static String secondaryPrefix(ZonedDateTime now, boolean use24Hour) {
        return secondaryPrefix(now, use24Hour, true);
    }

    /**
     * Grey portion of the second line, honouring the compact-date toggle. When
     * {@code showDate} is false the date is dropped, leaving the 12-hour AM/PM
     * marker on its own (and nothing at all in 24-hour form). The orange seconds
     * field is drawn separately by the renderer.
     */
    public static String secondaryPrefix(ZonedDateTime now, boolean use24Hour,
                                         boolean showDate) {
        StringBuilder sb = new StringBuilder();
        if (!use24Hour) {
            sb.append(amPm(now)).append(' ');
        }
        if (showDate) {
            sb.append(compactDate(now)).append(' ');
        }
        return sb.toString();
    }

    /** Exact second-line fixture useful outside the renderer. */
    public static String secondary(ZonedDateTime now, boolean use24Hour,
                                   boolean showSeconds) {
        return secondary(now, use24Hour, showSeconds, true);
    }

    /** Second-line fixture that also honours the compact-date toggle. */
    public static String secondary(ZonedDateTime now, boolean use24Hour,
                                   boolean showSeconds, boolean showDate) {
        String prefix = secondaryPrefix(now, use24Hour, showDate).trim();
        if (!showSeconds) {
            return prefix;
        }
        return prefix.isEmpty() ? seconds(now) : prefix + " " + seconds(now);
    }

    /** Compatibility helper retained for callers from the initial scaffold. */
    public static String time(ZonedDateTime now, ClockConfig config) {
        return main(now, config.use24Hour);
    }

    /** Compatibility helper now returns the accepted compact uppercase date. */
    public static String date(ZonedDateTime now) {
        return compactDate(now);
    }
}
