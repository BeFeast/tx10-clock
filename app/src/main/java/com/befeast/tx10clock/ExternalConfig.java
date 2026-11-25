package com.befeast.tx10clock;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The validated runtime configuration model.
 *
 * <p>It carries behavioural preferences (auto-start on boot, 12/24-hour
 * readout, digital seconds, an optional display time zone) plus a small set of
 * strictly bounded renderer <em>selections</em>: approved colour-role names,
 * clipping-safe digital text sizes, the compact-date toggle, and the burn-in
 * shift enable/range. It still encodes no raw visual values — no packed or hex
 * colours, geometry, typography, drawing APIs, assets, or screenshot
 * tolerances. Resolving an approved colour <em>name</em> to an actual colour
 * value is the renderer mapping's job ({@link ClockConfig#fromExternal}).
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

    /**
     * The approved colour-role names an external document may select. These are
     * <em>names</em>, not values: the accepted contract's actual colour values
     * live on the renderer side and are resolved by
     * {@link ClockConfig#fromExternal}. The set deliberately excludes any name
     * that would render foreground content invisible on the fixed pure-black
     * background.
     */
    public static final Set<String> APPROVED_COLOR_NAMES =
            Collections.unmodifiableSet(new LinkedHashSet<>(
                    Arrays.asList("white", "silver", "grey", "orange")));

    /** Inclusive lower bound for the digital text size percentages. */
    public static final int MIN_SIZE_PERCENT = 50;

    /** Inclusive upper bound (clipping-safe design size) for text percentages. */
    public static final int MAX_SIZE_PERCENT = 100;

    /** Inclusive upper bound on the burn-in shift amplitude, per the contract. */
    public static final int MAX_BURN_IN_SHIFT_PX = 8;

    private static final int MAX_ZONE_LENGTH = 64;

    /** Accepted keys. Any other key is rejected as {@code UNKNOWN_KEY}. */
    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_BOOT_START = "bootStart";
    private static final String K_USE_24_HOUR = "use24Hour";
    private static final String K_SHOW_SECONDS = "showSeconds";
    private static final String K_TIME_ZONE = "timeZone";
    private static final String K_DIGITAL_COLOR = "digitalColor";
    private static final String K_DATE_COLOR = "dateColor";
    private static final String K_TICK_COLOR = "tickColor";
    private static final String K_ACCENT_COLOR = "accentColor";
    private static final String K_SHOW_DATE = "showDate";
    private static final String K_DIGITAL_SIZE_PERCENT = "digitalSizePercent";
    private static final String K_SECONDARY_SIZE_PERCENT = "secondarySizePercent";
    private static final String K_BURN_IN_ENABLED = "burnInEnabled";
    private static final String K_BURN_IN_MAX_SHIFT_PX = "burnInMaxShiftPx";

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

    /** Approved name for the digital/hands/numerals role. Defaults "white". */
    public final String digitalColor;

    /** Approved name for the compact-date role. Defaults "grey". */
    public final String dateColor;

    /** Approved name for the minor-tick role. Defaults "silver". */
    public final String tickColor;

    /** Approved name for the accent (second hand/seconds) role. Defaults "orange". */
    public final String accentColor;

    /** Whether the compact English date is shown. Defaults true. */
    public final boolean showDate;

    /** Main digital line size, percent of design size (50..100). Defaults 100. */
    public final int digitalSizePercent;

    /** Secondary digital line size, percent of design size (50..100). Defaults 100. */
    public final int secondarySizePercent;

    /** Whether the periodic whole-composition burn-in shift runs. Defaults true. */
    public final boolean burnInEnabled;

    /** Maximum burn-in shift amplitude in design pixels (0..8). Defaults 8. */
    public final int burnInMaxShiftPx;

    private ExternalConfig(Builder b) {
        this.bootStart = b.bootStart;
        this.use24Hour = b.use24Hour;
        this.showSeconds = b.showSeconds;
        this.timeZone = b.timeZone;
        this.digitalColor = b.digitalColor;
        this.dateColor = b.dateColor;
        this.tickColor = b.tickColor;
        this.accentColor = b.accentColor;
        this.showDate = b.showDate;
        this.digitalSizePercent = b.digitalSizePercent;
        this.secondarySizePercent = b.secondarySizePercent;
        this.burnInEnabled = b.burnInEnabled;
        this.burnInMaxShiftPx = b.burnInMaxShiftPx;
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
        if (map.containsKey(K_DIGITAL_COLOR)) {
            b.digitalColor(requireColorName(map, K_DIGITAL_COLOR));
        }
        if (map.containsKey(K_DATE_COLOR)) {
            b.dateColor(requireColorName(map, K_DATE_COLOR));
        }
        if (map.containsKey(K_TICK_COLOR)) {
            b.tickColor(requireColorName(map, K_TICK_COLOR));
        }
        if (map.containsKey(K_ACCENT_COLOR)) {
            b.accentColor(requireColorName(map, K_ACCENT_COLOR));
        }
        if (map.containsKey(K_SHOW_DATE)) {
            b.showDate(requireBoolean(map, K_SHOW_DATE));
        }
        if (map.containsKey(K_DIGITAL_SIZE_PERCENT)) {
            b.digitalSizePercent(requireIntInRange(map, K_DIGITAL_SIZE_PERCENT,
                    MIN_SIZE_PERCENT, MAX_SIZE_PERCENT));
        }
        if (map.containsKey(K_SECONDARY_SIZE_PERCENT)) {
            b.secondarySizePercent(requireIntInRange(map, K_SECONDARY_SIZE_PERCENT,
                    MIN_SIZE_PERCENT, MAX_SIZE_PERCENT));
        }
        if (map.containsKey(K_BURN_IN_ENABLED)) {
            b.burnInEnabled(requireBoolean(map, K_BURN_IN_ENABLED));
        }
        if (map.containsKey(K_BURN_IN_MAX_SHIFT_PX)) {
            b.burnInMaxShiftPx(requireIntInRange(map, K_BURN_IN_MAX_SHIFT_PX,
                    0, MAX_BURN_IN_SHIFT_PX));
        }
        return b.build();
    }

    private static boolean isKnownKey(String key) {
        return K_SCHEMA_VERSION.equals(key)
                || K_BOOT_START.equals(key)
                || K_USE_24_HOUR.equals(key)
                || K_SHOW_SECONDS.equals(key)
                || K_TIME_ZONE.equals(key)
                || K_DIGITAL_COLOR.equals(key)
                || K_DATE_COLOR.equals(key)
                || K_TICK_COLOR.equals(key)
                || K_ACCENT_COLOR.equals(key)
                || K_SHOW_DATE.equals(key)
                || K_DIGITAL_SIZE_PERCENT.equals(key)
                || K_SECONDARY_SIZE_PERCENT.equals(key)
                || K_BURN_IN_ENABLED.equals(key)
                || K_BURN_IN_MAX_SHIFT_PX.equals(key);
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

    private static String requireColorName(Map<String, Object> map, String key)
            throws ConfigException {
        Object v = map.get(key);
        if (!(v instanceof String)) {
            throw wrongType(key, "string", v);
        }
        String name = (String) v;
        if (!APPROVED_COLOR_NAMES.contains(name)) {
            throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE,
                    "key '" + key + "' must be one of the approved colour names "
                            + APPROVED_COLOR_NAMES);
        }
        return name;
    }

    private static int requireIntInRange(Map<String, Object> map, String key,
                                         int min, int max) throws ConfigException {
        Object v = map.get(key);
        // Json yields Long for integral numbers and Double for fractional ones;
        // a fractional value is not an acceptable integer.
        if (!(v instanceof Long)) {
            throw wrongType(key, "integer", v);
        }
        long value = (Long) v;
        if (value < min || value > max) {
            throw new ConfigException(ConfigException.Reason.OUT_OF_RANGE,
                    "key '" + key + "' must be in [" + min + ".." + max
                            + "] but was " + value);
        }
        return (int) value;
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
                && Objects.equals(timeZone, other.timeZone)
                && digitalColor.equals(other.digitalColor)
                && dateColor.equals(other.dateColor)
                && tickColor.equals(other.tickColor)
                && accentColor.equals(other.accentColor)
                && showDate == other.showDate
                && digitalSizePercent == other.digitalSizePercent
                && secondarySizePercent == other.secondarySizePercent
                && burnInEnabled == other.burnInEnabled
                && burnInMaxShiftPx == other.burnInMaxShiftPx;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bootStart, use24Hour, showSeconds, timeZone,
                digitalColor, dateColor, tickColor, accentColor, showDate,
                digitalSizePercent, secondarySizePercent,
                burnInEnabled, burnInMaxShiftPx);
    }

    @Override
    public String toString() {
        return "ExternalConfig{bootStart=" + bootStart
                + ", use24Hour=" + use24Hour
                + ", showSeconds=" + showSeconds
                + ", timeZone=" + timeZone
                + ", digitalColor=" + digitalColor
                + ", dateColor=" + dateColor
                + ", tickColor=" + tickColor
                + ", accentColor=" + accentColor
                + ", showDate=" + showDate
                + ", digitalSizePercent=" + digitalSizePercent
                + ", secondarySizePercent=" + secondarySizePercent
                + ", burnInEnabled=" + burnInEnabled
                + ", burnInMaxShiftPx=" + burnInMaxShiftPx + '}';
    }

    /** Fluent builder; every field starts at its documented default. */
    public static final class Builder {
        private boolean bootStart = true;
        private boolean use24Hour = false;
        private boolean showSeconds = true;
        private String timeZone = null;
        private String digitalColor = "white";
        private String dateColor = "grey";
        private String tickColor = "silver";
        private String accentColor = "orange";
        private boolean showDate = true;
        private int digitalSizePercent = 100;
        private int secondarySizePercent = 100;
        private boolean burnInEnabled = true;
        private int burnInMaxShiftPx = MAX_BURN_IN_SHIFT_PX;

        public Builder bootStart(boolean v) { this.bootStart = v; return this; }
        public Builder use24Hour(boolean v) { this.use24Hour = v; return this; }
        public Builder showSeconds(boolean v) { this.showSeconds = v; return this; }
        public Builder timeZone(String v) { this.timeZone = v; return this; }
        public Builder digitalColor(String v) { this.digitalColor = v; return this; }
        public Builder dateColor(String v) { this.dateColor = v; return this; }
        public Builder tickColor(String v) { this.tickColor = v; return this; }
        public Builder accentColor(String v) { this.accentColor = v; return this; }
        public Builder showDate(boolean v) { this.showDate = v; return this; }
        public Builder digitalSizePercent(int v) { this.digitalSizePercent = v; return this; }
        public Builder secondarySizePercent(int v) { this.secondarySizePercent = v; return this; }
        public Builder burnInEnabled(boolean v) { this.burnInEnabled = v; return this; }
        public Builder burnInMaxShiftPx(int v) { this.burnInMaxShiftPx = v; return this; }

        public ExternalConfig build() {
            return new ExternalConfig(this);
        }
    }
}
