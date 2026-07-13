package com.befeast.tx10clock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The view-layer burn-in wiring: the translation the view applies before the
 * renderer draws is gated by {@code burnInEnabled} and clamped to
 * {@code burnInMaxShiftPx}, and the default envelope uses the full engine.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
public class ClockViewBurnInTest {

    // Local epoch minute 0 lands on the (-8, -8) corner of the burn-in scan,
    // so the clamp is exercised for real rather than vacuously.
    private static final ZonedDateTime CORNER =
            ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static ClockConfig withBurnIn(boolean enabled, int maxPx) {
        return ClockConfig.defaultConfig().toBuilder()
                .burnInEnabled(enabled)
                .burnInMaxShiftPx(maxPx)
                .build();
    }

    @Test
    public void disabledBurnInLeavesCompositionCentered() {
        assertArrayEquals(new int[]{0, 0},
                ClockView.burnInTranslation(withBurnIn(false, 8), CORNER));
    }

    @Test
    public void zeroAmplitudeLeavesCompositionCentered() {
        assertArrayEquals(new int[]{0, 0},
                ClockView.burnInTranslation(withBurnIn(true, 0), CORNER));
    }

    @Test
    public void defaultEnvelopeUsesTheFullEngineOffset() {
        BurnInOffset raw = BurnInOffset.at(CORNER);
        assertArrayEquals("the (-8,-8) corner is the engine offset at epoch minute 0",
                new int[]{-8, -8}, new int[]{raw.x, raw.y});
        assertArrayEquals(new int[]{raw.x, raw.y},
                ClockView.burnInTranslation(withBurnIn(true, 8), CORNER));
    }

    @Test
    public void smallerMaxUsesItsOwnBoundedGrid() {
        assertArrayEquals(new int[]{-2, -2},
                ClockView.burnInTranslation(withBurnIn(true, 2), CORNER));
    }

    @Test
    public void smallerMaxStillChangesAtEveryMinuteBoundary() {
        for (int minute = 0; minute < 25; minute++) {
            int[] current = ClockView.burnInTranslation(
                    withBurnIn(true, 2), CORNER.plusMinutes(minute));
            int[] next = ClockView.burnInTranslation(
                    withBurnIn(true, 2), CORNER.plusMinutes(minute + 1));
            assertNotEquals(current[0] + "," + current[1], next[0] + "," + next[1]);
        }
    }
}
