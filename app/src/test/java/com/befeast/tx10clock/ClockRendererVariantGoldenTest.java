package com.befeast.tx10clock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Exact 1280x720 golden comparisons for the integration outcomes that the
 * default-frame golden ({@link ClockRendererRenderTest}) does not cover:
 *
 * <ul>
 *   <li>a representative external-config change — the 24-hour readout, which
 *       reflows the digital lines ("22:09" with no AM/PM prefix); and</li>
 *   <li>each declared burn-in extreme — the whole composition shifted to its
 *       {@code (-8,-8)} and {@code (+8,+8)} corners, matching the accepted
 *       visual contract's derived {@code reference-burnin-*} frames.</li>
 * </ul>
 *
 * <p>The burn-in cases render the reference composition through the exact
 * translation the production view applies ({@link ClockView#burnInTranslation}),
 * so the golden proves the complete analog + digital composition shifts together
 * as one unit, within the contract's {@code ±8 px} envelope, without clipping any
 * content or violating the 1280x720 screenshot tolerances. A mismatch writes
 * {@code <tag>.actual.png} / {@code .expected.png} / {@code .diff.png}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClockRendererVariantGoldenTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    // The accepted CI-golden tolerances from visual-contract comparison.json.
    private static final int CHANNEL_THRESHOLD = 8;
    private static final double MAX_MISMATCH_FRACTION = 0.001;

    // The binding reference state (Asia/Jerusalem 22:09:42, i.e. 10:09 PM). The
    // wall-clock fields are what the renderer draws, so rendering this instant in
    // UTC reproduces the reference composition deterministically.
    private static final ZonedDateTime FIXED_TIME =
            ZonedDateTime.of(2026, 7, 12, 22, 9, 42, 0, ZoneOffset.UTC);

    // Local epoch minute 0 sits on the (-8,-8) corner of the burn-in scan, and
    // minute 288 (the final cell of the default 17x17 serpentine) on (+8,+8).
    private static final ZonedDateTime BURN_IN_MINUS_MINUTE =
            ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final ZonedDateTime BURN_IN_PLUS_MINUTE =
            BURN_IN_MINUS_MINUTE.plusMinutes(288);

    /** Render one frame, optionally pre-translated by a whole-composition shift. */
    private static Bitmap render(ClockConfig config, int shiftX, int shiftY) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(shiftX, shiftY);
        new ClockRenderer(config).render(canvas, WIDTH, HEIGHT, FIXED_TIME);
        return bitmap;
    }

    @Test
    public void twentyFourHourConfigMatchesGolden() {
        ClockConfig config = ClockConfig.defaultConfig().toBuilder()
                .use24Hour(true)
                .build();
        GoldenImage.assertMatchesGolden(render(config, 0, 0),
                "/golden/clock_24h_1280x720.png", "clock_24h",
                CHANNEL_THRESHOLD, MAX_MISMATCH_FRACTION);
    }

    @Test
    public void burnInMinusEightExtremeMatchesGolden() {
        int[] shift = ClockView.burnInTranslation(
                ClockConfig.defaultConfig(), BURN_IN_MINUS_MINUTE);
        assertArrayEquals("epoch minute 0 is the declared (-8,-8) burn-in extreme",
                new int[]{-BurnInOffset.BOUND_PX, -BurnInOffset.BOUND_PX}, shift);
        GoldenImage.assertMatchesGolden(render(ClockConfig.defaultConfig(), shift[0], shift[1]),
                "/golden/clock_burnin_minus8_1280x720.png", "clock_burnin_minus8",
                CHANNEL_THRESHOLD, MAX_MISMATCH_FRACTION);
    }

    @Test
    public void burnInPlusEightExtremeMatchesGolden() {
        int[] shift = ClockView.burnInTranslation(
                ClockConfig.defaultConfig(), BURN_IN_PLUS_MINUTE);
        assertArrayEquals("minute 288 is the declared (+8,+8) burn-in extreme",
                new int[]{BurnInOffset.BOUND_PX, BurnInOffset.BOUND_PX}, shift);
        GoldenImage.assertMatchesGolden(render(ClockConfig.defaultConfig(), shift[0], shift[1]),
                "/golden/clock_burnin_plus8_1280x720.png", "clock_burnin_plus8",
                CHANNEL_THRESHOLD, MAX_MISMATCH_FRACTION);
    }

    /**
     * The composition keeps a fully black border at least as wide as the burn-in
     * envelope on every edge, so translating it by up to {@code ±8 px} can only
     * ever clip black — never a hand, numeral, tick, or digital glyph. This is
     * what lets the whole-composition shift stay clip-free at its extremes.
     */
    @Test
    public void compositionKeepsBlackMarginSoBurnInNeverClips() {
        Bitmap center = render(ClockConfig.defaultConfig(), 0, 0);
        int margin = BurnInOffset.BOUND_PX;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                boolean inBorder = x < margin || x >= WIDTH - margin
                        || y < margin || y >= HEIGHT - margin;
                if (inBorder) {
                    assertEquals("opaque black border pixel at " + x + "," + y,
                            0xFF000000, center.getPixel(x, y));
                }
            }
        }
    }

    /**
     * The analog + digital composition shifts as a single rigid unit: an integer
     * whole-canvas translation reproduces the centered frame exactly in the
     * overlapping region (no per-element re-layout, distortion, or resampling).
     */
    @Test
    public void wholeCompositionShiftsAsOneRigidUnit() {
        Bitmap center = render(ClockConfig.defaultConfig(), 0, 0);
        assertOverlapEqualsShift(center,
                render(ClockConfig.defaultConfig(), BurnInOffset.BOUND_PX, BurnInOffset.BOUND_PX),
                BurnInOffset.BOUND_PX, BurnInOffset.BOUND_PX);
        assertOverlapEqualsShift(center,
                render(ClockConfig.defaultConfig(), -BurnInOffset.BOUND_PX, -BurnInOffset.BOUND_PX),
                -BurnInOffset.BOUND_PX, -BurnInOffset.BOUND_PX);
    }

    /** Assert {@code shifted(x,y) == center(x-dx, y-dy)} across the overlap. */
    private static void assertOverlapEqualsShift(Bitmap center, Bitmap shifted, int dx, int dy) {
        for (int y = Math.max(0, dy); y < Math.min(HEIGHT, HEIGHT + dy); y++) {
            for (int x = Math.max(0, dx); x < Math.min(WIDTH, WIDTH + dx); x++) {
                int sx = x - dx;
                int sy = y - dy;
                if (sx < 0 || sx >= WIDTH || sy < 0 || sy >= HEIGHT) {
                    continue;
                }
                if (center.getPixel(sx, sy) != shifted.getPixel(x, y)) {
                    assertEquals("rigid-shift mismatch at " + x + "," + y
                                    + " (dx=" + dx + ", dy=" + dy + ")",
                            center.getPixel(sx, sy), shifted.getPixel(x, y));
                }
            }
        }
    }
}
