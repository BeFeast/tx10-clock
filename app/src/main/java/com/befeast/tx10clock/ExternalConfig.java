package com.befeast.tx10clock;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * The validated, renderer-agnostic runtime configuration model.
 *
 * <p>This is deliberately <em>behavioural only</em>: it carries preferences such
 * as whether to auto-start on boot, 12/24-hour readout, whether seconds are
 * shown, and an optional display time zone. It encodes <b>no</b> visual contract
 * — no colours, geometry, typography, assets, or screenshot tolerances. Those
 * belong to the renderer/visual package and are intentionally out of scope here.
 *
 * <p>Instances are immutable and are only ever produced by {@link #defaults()}
 * or by {@link #parse(byte[])}/{@link #parse(String)}, which reject anything
 * that is oversized, malformed, has duplicate or unknown keys, or holds an
 * out-of-range value. A successfully parsed instance is what a {@link ConfigStore}
 * retains as its internal last-known-good copy.
 */
public final class ExternalConfig {

    /** Hard upper bound on an accepted document; anything larger is rejected. */
    public static final int MAX_BYTES = 8 * 1024;

    /** The only configuration schema version this build accepts. */
    public static final long SCHEMA_VERSION = 1L;

    private static final int MAX_ZONE_LENGTH = 64;

    /** Accepted keys. Any other key is rejected as {@code UNKNOWN_KEY}. */
    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_BOOT_START = "bootStart";
    private static final String K_USE_24_HOUR = "use24Hour";
    private static final String K_SHOW_SECONDS = "showSeconds";
    private static final String K_TIME_ZONE = "timeZone";

    /** Whether the app auto-starts after {@code BOOT_COMPLETED}. Defaults true. */
    public final boolean bootStart;

    /** Whether the digital readout uses 24-hour form. Defaults false. */
    public final boolean use24Hour;

    /** Whether the second hand and seconds field are shown. Defaults true. */
    public final boolean showSeconds;

    /**
     * IANA zone id for the displayed time, or {@code null} to follow the
     * device's default zone. When set it is guaranteed to be a valid id.
     */
    public final String timeZone;

    private ExternalConfig(Builder b) {
        this.bootStart = b.bootStart;
        this.use24Hour = b.use24Hour;
        this.showSeconds = b.showSeconds;
        this.timeZone = b.timeZone;
    }

    /** The built-in defaults used when no accepted external config exists. */
    public static ExternalConfig defaults() {
        return new Builder().build();
    }

    /**
     * Resolve the configured {@link ZoneId}, or the supplied fallback when no
     * explicit zone is set. The stored {@link #timeZone} is always valid, so
     * this cannot throw.
     */
    public ZoneId resolveZone(ZoneId fallback) {
        return timeZone == null ? fallback : ZoneId.of(timeZone);
    }

    /**
     * Parse and strictly validate raw UTF-8 config bytes.
     *
     * @throws ConfigException if the bytes are oversized, malformed, contain a
     *     duplicate or unknown key, are not a JSON object, or hold a value of
     *     the wrong type or outside its accepted range.
     */
    public static ExternalConfig parse(byte[] raw) throws ConfigException {
        Objects.requireNonNull(raw, "raw");
        if (raw.length > MAX_BYTES) {
            throw new ConfigException(ConfigException.Reason.OVERSIZED,
                    "config exceeds " + MAX_BYTES + " byte limit (" + raw.length + ")");
        }
        return parse(new String(raw, StandardCharsets.UTF_8));
    }

    /** Parse and strictly validate a config document already decoded to text. */
    public static ExternalConfig parse(String text) throws ConfigException {
        Objects.requireNonNull(text, "text");
        Object root = Json.parse(text);
        if (!(root instanceof Map)) {
            throw new ConfigException(ConfigException.Reason.NOT_OBJECT,
                    "top-level JSON value must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;

        for (String key : map.keySet()) {
            if (!isKnownKey(key)) {
                throw new ConfigException(ConfigException.Reason.UNKNOWN_KEY,
                        "unknown key '" + key + "'");
            }
        }

        // schemaVersion: optional; when present must be exactly SCHEMA_VERSION.
        if (map.containsKey(K_SCHEMA_VERSION)) {
            long version = requireLong(map, K_SCHEMA_VERSION);
            if (version != SCHEMA_VERSION) {
                throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE,
                        "unsupported schemaVersion " + version
                                + " (this build accepts " + SCHEMA_VERSION + ")");
            }
        }

        Builder b = new Builder();
        if (map.containsKey(K_BOOT_START)) {
            b.bootStart(requireBoolean(map, K_BOOT_START));
        }
        if (map.containsKey(K_USE_24_HOUR)) {
            b.use24Hour(requireBoolean(map, K_USE_24_HOUR));
        }
        if (map.containsKey(K_SHOW_SECONDS)) {
            b.showSeconds(requireBoolean(map, K_SHOW_SECONDS));
        }
        if (map.containsKey(K_TIME_ZONE)) {
            b.timeZone(requireZone(map, K_TIME_ZONE));
        }
        return b.build();
    }

    private static boolean isKnownKey(String key) {
        return K_SCHEMA_VERSION.equals(key)
                || K_BOOT_START.equals(key)
                || K_USE_24_HOUR.equals(key)
                || K_SHOW_SECONDS.equals(key)
                || K_TIME_ZONE.equals(key);
    }

    private static boolean requireBoolean(Map<String, Object> map, String key)
            throws ConfigException {
        Object v = map.get(key);
        if (!(v instanceof Boolean)) {
            throw wrongType(key, "boolean", v);
        }
        return (Boolean) v;
    }

    private static long requireLong(Map<String, Object> map, String key)
            throws ConfigException {
        Object v = map.get(key);
        // Json yields Long for integral numbers and Double for fractional ones;
        // a fractional value is not an acceptable integer.
        if (!(v instanceof Long)) {
            throw wrongType(key, "integer", v);
        }
        return (Long) v;
    }

    private static String requireZone(Map<String, Object> map, String key)
            throws ConfigException {
        Object v = map.get(key);
        if (!(v instanceof String)) {
            throw wrongType(key, "string", v);
        }
        String zone = (String) v;
        if (zone.length() > MAX_ZONE_LENGTH) {
            throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE,
                    "timeZone exceeds " + MAX_ZONE_LENGTH + " chars");
        }
        try {
            // Normalise and validate; a bad id throws DateTimeException.
            return ZoneId.of(zone).getId();
        } catch (DateTimeException ex) {
            throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE,
                    "unknown timeZone '" + zone + "'");
        }
    }

    private static ConfigException wrongType(String key, String expected, Object actual) {
        String actualType = actual == null ? "null" : actual.getClass().getSimpleName();
        return new ConfigException(ConfigException.Reason.WRONG_TYPE,
                "key '" + key + "' expected " + expected + " but got " + actualType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExternalConfig)) {
            return false;
        }
        ExternalConfig other = (ExternalConfig) o;
        return bootStart == other.bootStart
                && use24Hour == other.use24Hour
                && showSeconds == other.showSeconds
                && Objects.equals(timeZone, other.timeZone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bootStart, use24Hour, showSeconds, timeZone);
    }

    @Override
    public String toString() {
        return "ExternalConfig{bootStart=" + bootStart
                + ", use24Hour=" + use24Hour
                + ", showSeconds=" + showSeconds
                + ", timeZone=" + timeZone + '}';
    }

    /** Fluent builder; every field starts at its documented default. */
    public static final class Builder {
        private boolean bootStart = true;
        private boolean use24Hour = false;
        private boolean showSeconds = true;
        private String timeZone = null;

        public Builder bootStart(boolean v) { this.bootStart = v; return this; }
        public Builder use24Hour(boolean v) { this.use24Hour = v; return this; }
        public Builder showSeconds(boolean v) { this.showSeconds = v; return this; }
        public Builder timeZone(String v) { this.timeZone = v; return this; }

        public ExternalConfig build() {
            return new ExternalConfig(this);
        }
    }
}
