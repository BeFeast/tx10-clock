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

    /** Whether the compact English date is drawn on the secondary line. */
    public final boolean showDate;

    /** Main digital line size as a percentage of the design size (50..100). */
    public final int digitalSizePercent;

    /** Secondary digital line size as a percentage of the design size (50..100). */
    public final int secondarySizePercent;

    /** Whether the periodic whole-composition burn-in shift is enabled. */
    public final boolean burnInEnabled;

    /** Maximum burn-in shift amplitude in design pixels (0..8). */
    public final int burnInMaxShiftPx;

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
        this.showDate = b.showDate;
        this.digitalSizePercent = b.digitalSizePercent;
        this.secondarySizePercent = b.secondarySizePercent;
        this.burnInEnabled = b.burnInEnabled;
        this.burnInMaxShiftPx = b.burnInMaxShiftPx;
    }

    /**
     * The default elegant dark theme used by the shipping app and the golden
     * harness. Kept as a single source of truth so a golden regenerated from
     * this config matches what the device renders.
     */
    public static ClockConfig defaultConfig() {
        return new Builder().build();
    }

    /**
     * Map a strictly validated {@link ExternalConfig} onto the renderer model.
     *
     * <p>This is the single place where an approved colour-role <em>name</em>
     * resolves to an accepted-contract colour <em>value</em>. The mapping never
     * invents colours: every name in {@link ExternalConfig#APPROVED_COLOR_NAMES}
     * resolves to one of the accepted palette values below, the background stays
     * pure black, and geometry/typography remain untouched. Because the input
     * has already passed strict parsing, its bounded fields (sizes, burn-in
     * range) are threaded through as-is.
     */
    public static ClockConfig fromExternal(ExternalConfig external) {
        int primary = resolveColor(external.digitalColor);
        return new Builder()
                .digitalColor(primary)
                .hourHandColor(primary)
                .minuteHandColor(primary)
                .dateColor(resolveColor(external.dateColor))
                .tickColor(resolveColor(external.tickColor))
                .secondHandColor(resolveColor(external.accentColor))
                .use24Hour(external.use24Hour)
                .showSeconds(external.showSeconds)
                .showDate(external.showDate)
                .digitalSizePercent(external.digitalSizePercent)
                .secondarySizePercent(external.secondarySizePercent)
                .burnInEnabled(external.burnInEnabled)
                .burnInMaxShiftPx(external.burnInMaxShiftPx)
                .build();
    }

    /**
     * Resolve an approved colour-role name to its accepted-contract packed
     * value. The name set is closed by strict parsing, so an unknown name here
     * is a programming error, not user input.
     */
    private static int resolveColor(String name) {
        switch (name) {
            case "white": return 0xFFF5F5F7;  // contract primary
            case "silver": return 0xFFD1D1D6; // contract ticks
            case "grey": return 0xFFA1A1A6;   // contract secondary
            case "orange": return 0xFFFF9F0A; // contract accent
            default:
                throw new IllegalArgumentException("unapproved colour name: " + name);
        }
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
                .showSeconds(showSeconds)
                .showDate(showDate)
                .digitalSizePercent(digitalSizePercent)
                .secondarySizePercent(secondarySizePercent)
                .burnInEnabled(burnInEnabled)
                .burnInMaxShiftPx(burnInMaxShiftPx);
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
        private boolean showDate = true;
        private int digitalSizePercent = 100;
        private int secondarySizePercent = 100;
        private boolean burnInEnabled = true;
        private int burnInMaxShiftPx = 8; // accepted contract burn-in bound

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
        public Builder showDate(boolean v) { this.showDate = v; return this; }
        public Builder digitalSizePercent(int v) { this.digitalSizePercent = v; return this; }
        public Builder secondarySizePercent(int v) { this.secondarySizePercent = v; return this; }
        public Builder burnInEnabled(boolean v) { this.burnInEnabled = v; return this; }
        public Builder burnInMaxShiftPx(int v) { this.burnInMaxShiftPx = v; return this; }

        public ClockConfig build() {
            return new ClockConfig(this);
        }
    }
}
