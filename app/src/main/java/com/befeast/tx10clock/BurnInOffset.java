package com.befeast.tx10clock;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Deterministic burn-in protection offset for the whole composition.
 *
 * <p>Implements the accepted visual contract v0.1.0 burn-in clauses
 * ({@code geometry.json#burn_in}, {@code motion.json#burn_in}): a discrete
 * whole-composition translation whose x and y components each stay within
 * ±{@link #BOUND_PX} px, holding one position for a full local wall-clock
 * minute and moving instantly to the next position at the minute boundary.
 *
 * <p>The schedule is a boustrophedon (serpentine) scan of the full
 * 17×17 offset grid, one cell per minute, wrapping after
 * {@link #CYCLE_MINUTES} minutes (~4.8 h). This gives:
 *
 * <ul>
 *   <li><b>Determinism</b> — the offset is a pure function of the local
 *       wall-clock minute; equal minutes always yield equal offsets.</li>
 *   <li><b>Guaranteed change</b> — consecutive scan cells differ by exactly
 *       one pixel on one axis, and the cycle wrap jumps from (+8,+8) back
 *       to (-8,-8), so the offset changes at every minute boundary.</li>
 *   <li><b>Uniform coverage</b> — every representable position, including
 *       the centered frame (0,0) and the contract's corner-extreme frames
 *       (-8,-8) and (+8,+8), is occupied exactly once per cycle.</li>
 * </ul>
 *
 * <p>This class is intentionally pure: no randomness, no persistence, no
 * Android dependency. Applying the translation to the rendered frame is the
 * renderer's concern and is out of this class's scope.
 */
public final class BurnInOffset {

    /** Maximum magnitude of each offset component, per geometry.json. */
    public static final int BOUND_PX = 8;

    /** How long one position is held, per motion.json. */
    public static final int CADENCE_SECONDS = 60;

    /** Grid side length: every integer offset in [-BOUND_PX, +BOUND_PX]. */
    private static final int SPAN = 2 * BOUND_PX + 1;

    /** Minutes until the scan revisits a position (17 × 17 cells). */
    public static final int CYCLE_MINUTES = SPAN * SPAN;

    /** Horizontal translation in pixels, within [-BOUND_PX, +BOUND_PX]. */
    public final int x;

    /** Vertical translation in pixels, within [-BOUND_PX, +BOUND_PX]. */
    public final int y;

    private BurnInOffset(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /**
     * The offset in effect at {@code now}, constant for the whole local
     * wall-clock minute containing it.
     */
    public static BurnInOffset at(ZonedDateTime now) {
        return forMinuteIndex(minuteIndexOf(now));
    }

    /**
     * The offset for a configured bound. Each bound gets its own complete
     * serpentine grid, so reducing the amplitude still changes position at
     * every minute boundary instead of clamping several consecutive default
     * positions to the same edge value.
     */
    public static BurnInOffset at(ZonedDateTime now, int boundPx) {
        return forMinuteIndex(minuteIndexOf(now), boundPx);
    }

    /**
     * The local wall-clock minute {@code now} falls in, counted from the
     * local epoch. Uses the wall-clock fields (not the instant) so the
     * schedule follows what the viewer sees on screen.
     */
    public static long minuteIndexOf(ZonedDateTime now) {
        return Math.floorDiv(now.toLocalDateTime().toEpochSecond(ZoneOffset.UTC), CADENCE_SECONDS);
    }

    /** The offset for a given minute index; pure and total for any long. */
    public static BurnInOffset forMinuteIndex(long minuteIndex) {
        return forMinuteIndex(minuteIndex, BOUND_PX);
    }

    /**
     * Offset for one minute and a caller-selected bound in
     * {@code [0, BOUND_PX]}. The default overload remains the exact accepted
     * 17x17 contract; smaller bounds traverse their own full grid.
     */
    public static BurnInOffset forMinuteIndex(long minuteIndex, int boundPx) {
        if (boundPx < 0 || boundPx > BOUND_PX) {
            throw new IllegalArgumentException(
                    "boundPx must be between 0 and " + BOUND_PX);
        }
        int span = 2 * boundPx + 1;
        int cycleMinutes = span * span;
        int cell = (int) Math.floorMod(minuteIndex, cycleMinutes);
        int row = cell / span;
        int col = cell % span;
        // Even rows scan left-to-right, odd rows right-to-left, so each step
        // within the cycle moves exactly one pixel on exactly one axis; the
        // wrap jumps from the positive corner back to the negative corner.
        int x = (row % 2 == 0 ? col : span - 1 - col) - boundPx;
        int y = row - boundPx;
        return new BurnInOffset(x, y);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BurnInOffset)) {
            return false;
        }
        BurnInOffset other = (BurnInOffset) o;
        return x == other.x && y == other.y;
    }

    @Override
    public int hashCode() {
        return 31 * x + y;
    }

    @Override
    public String toString() {
        return "BurnInOffset(" + x + ", " + y + ")";
    }
}
