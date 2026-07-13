package com.befeast.tx10clock;

/**
 * Immutable visual/behavioural configuration for {@link ClockRenderer}.
 *
 * <p>All colours are packed 0xAARRGGBB ints so the type has no Android
 * dependency and can be constructed and asserted on in plain JVM unit tests.
 * The renderer is a pure function of a {@link ClockConfig} plus a timestamp,
 * which is what makes the golden harness deterministic.
 */
public final class ClockConfig {

    public final int backgroundColor;
    public final int faceColor;
    public final int tickColor;
    public final int hourHandColor;
    public final int minuteHandColor;
    public final int secondHandColor;
    public final int digitalColor;
    public final int dateColor;

    /** Whether the digital readout uses 24-hour (true) or 12-hour (false) form. */
    public final boolean use24Hour;

    /** Whether the sweeping second hand is drawn. */
    public final boolean showSeconds;

    private ClockConfig(Builder b) {
        this.backgroundColor = b.backgroundColor;
        this.faceColor = b.faceColor;
        this.tickColor = b.tickColor;
        this.hourHandColor = b.hourHandColor;
        this.minuteHandColor = b.minuteHandColor;
        this.secondHandColor = b.secondHandColor;
        this.digitalColor = b.digitalColor;
        this.dateColor = b.dateColor;
        this.use24Hour = b.use24Hour;
        this.showSeconds = b.showSeconds;
    }

    /**
     * The default elegant dark theme used by the shipping app and the golden
     * harness. Kept as a single source of truth so a golden regenerated from
     * this config matches what the device renders.
     */
    public static ClockConfig defaultConfig() {
        return new Builder().build();
    }

    public Builder toBuilder() {
        return new Builder()
                .backgroundColor(backgroundColor)
                .faceColor(faceColor)
                .tickColor(tickColor)
                .hourHandColor(hourHandColor)
                .minuteHandColor(minuteHandColor)
                .secondHandColor(secondHandColor)
                .digitalColor(digitalColor)
                .dateColor(dateColor)
                .use24Hour(use24Hour)
                .showSeconds(showSeconds);
    }

    /** Fluent builder; every field defaults to the dark theme. */
    public static final class Builder {
        private int backgroundColor = 0xFF000000; // pure black
        private int faceColor = 0xFF000000;       // open, unfilled analog face
        private int tickColor = 0xFFD1D1D6;
        private int hourHandColor = 0xFFF5F5F7;
        private int minuteHandColor = 0xFFF5F5F7;
        private int secondHandColor = 0xFFFF9F0A;  // warm orange accent
        private int digitalColor = 0xFFF5F5F7;
        private int dateColor = 0xFFA1A1A6;
        private boolean use24Hour = false;
        private boolean showSeconds = true;

        public Builder backgroundColor(int v) { this.backgroundColor = v; return this; }
        public Builder faceColor(int v) { this.faceColor = v; return this; }
        public Builder tickColor(int v) { this.tickColor = v; return this; }
        public Builder hourHandColor(int v) { this.hourHandColor = v; return this; }
        public Builder minuteHandColor(int v) { this.minuteHandColor = v; return this; }
        public Builder secondHandColor(int v) { this.secondHandColor = v; return this; }
        public Builder digitalColor(int v) { this.digitalColor = v; return this; }
        public Builder dateColor(int v) { this.dateColor = v; return this; }
        public Builder use24Hour(boolean v) { this.use24Hour = v; return this; }
        public Builder showSeconds(boolean v) { this.showSeconds = v; return this; }

        public ClockConfig build() {
            return new ClockConfig(this);
        }
    }
}
