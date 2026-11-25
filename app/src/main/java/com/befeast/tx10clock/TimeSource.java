package com.befeast.tx10clock;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The clock's single source of "now". Injecting this lets the golden harness
 * pin the renderer to a fixed instant so offscreen frames are deterministic,
 * while production uses the real system clock.
 */
public interface TimeSource {

    /** The current moment, already resolved to the desired time zone. */
    ZonedDateTime now();

    /** A time source backed by the device's real clock and default time zone. */
    static TimeSource system() {
        return () -> ZonedDateTime.now();
    }

    /** A time source that always returns {@code fixed}. Used by tests. */
    static TimeSource fixed(ZonedDateTime fixed) {
        return () -> fixed;
    }

    /** A time source backed by the real clock but pinned to a specific zone. */
    static TimeSource systemInZone(ZoneId zone) {
        return () -> ZonedDateTime.now(zone);
    }
}
