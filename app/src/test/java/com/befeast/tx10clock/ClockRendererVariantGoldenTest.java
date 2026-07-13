package com.befeast.tx10clock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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
 *       reflows the time lockup ("22:09" with no AM/PM marker); and</li>
 *   <li>each declared burn-in extreme — the whole composition shifted to its
 *       {@code (-8,-8)} and {@code (+8,+8)} corners.</li>
 * </ul>
 *
 * <p>Every golden here is rendered through a laid-out {@link ClockView}, i.e.
 * the production {@link ClockView#onDraw} path, driven only by an injected
 * {@link TimeSource} and {@link ClockConfig}. So the frame the golden compares
 * is exactly what the shipping view paints: {@code onDraw} itself decides the
 * per-minute burn-in translation ({@link ClockView#burnInTranslation}), applies
 * it in the right order, and hands the renderer the view's own
 * {@code getWidth()/getHeight()}. If {@code onDraw} stopped applying the offset,
 * applied it in the wrong order, or used the wrong dimensions, these goldens
 * would fail — which the earlier "render the renderer directly and translate the
 * canvas ourselves" harness could not catch.
 *
 * <p>Because the burn-in offset is a pure function of the rendered instant, the
 * two extreme goldens use calendar-equivalent instants whose local-minute index
 * selects each corner of the scan while retaining the binding SUN, JUL 12
 * 22:09:42 text. Each golden therefore proves the complete analog + digital
 * composition shifts together as one unit, within the contract's {@code ±8 px}
 * envelope, without clipping any content or violating the 1280x720 screenshot
 * tolerances. A mismatch writes {@code <tag>.actual.png} /
 * {@code .expected.png} / {@code .diff.png}.
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

    // The Gregorian calendar repeats every 400 years. These years therefore
    // preserve Sunday, July 12 22:09:42 while their local-minute indices select
    // the (-8,-8) and (+8,+8) corners of the 17x17 burn-in scan.
    private static final ZonedDateTime BURN_IN_MINUS_MINUTE = FIXED_TIME.withYear(98826);
    private static final ZonedDateTime BURN_IN_PLUS_MINUTE = FIXED_TIME.withYear(109626);

    /**
     * Render one frame through the production {@link ClockView} draw path: lay a
     * view out at the full 1280x720 surface, hand it the config and a fixed
     * clock, and let {@link ClockView#onDraw} paint — including its own burn-in
     * translation derived from {@code now}. This is the integration these
     * goldens claim to verify, so the golden is the view's actual output rather
     * than a renderer call the test translated by hand.
     */
    private static Bitmap renderThroughView(ClockConfig config, ZonedDateTime now) {
        Context context = RuntimeEnvironment.getApplication();
        ClockView view = new ClockView(context);
        view.apply(config, TimeSource.fixed(now));
        view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** Render one frame directly, optionally pre-translated by a whole shift. */
    private static Bitmap renderShifted(ClockConfig config, int shiftX, int shiftY) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(shiftX, shiftY);
        new ClockRenderer(config).render(canvas, WIDTH, HEIGHT, FIXED_TIME);
        return bitmap;
    }

    @Test
    public void twentyFourHourConfigMatchesGolden() {
        // Burn-in disabled so this golden isolates the 24-hour reflow at the
        // centered position; onDraw still applies the (0,0) translation.
        ClockConfig config = ClockConfig.defaultConfig().toBuilder()
                .use24Hour(true)
                .burnInEnabled(false)
                .build();
        GoldenImage.assertMatchesGolden(renderThroughView(config, FIXED_TIME),
                "/golden/clock_24h_1280x720.png", "clock_24h",
                CHANNEL_THRESHOLD, MAX_MISMATCH_FRACTION);
    }

    @Test
    public void burnInMinusEightExtremeMatchesGolden() {
        int[] shift = ClockView.burnInTranslation(
                ClockConfig.defaultConfig(), BURN_IN_MINUS_MINUTE);
        assertArrayEquals("the selected minute is the declared (-8,-8) burn-in extreme",
                new int[]{-BurnInOffset.BOUND_PX, -BurnInOffset.BOUND_PX}, shift);
        GoldenImage.assertMatchesGolden(
                renderThroughView(ClockConfig.defaultConfig(), BURN_IN_MINUS_MINUTE),
                "/golden/clock_burnin_minus8_1280x720.png", "clock_burnin_minus8",
                CHANNEL_THRESHOLD, MAX_MISMATCH_FRACTION);
    }

    @Test
    public void burnInPlusEightExtremeMatchesGolden() {
        int[] shift = ClockView.burnInTranslation(
                ClockConfig.defaultConfig(), BURN_IN_PLUS_MINUTE);
        assertArrayEquals("the selected minute is the declared (+8,+8) burn-in extreme",
                new int[]{BurnInOffset.BOUND_PX, BurnInOffset.BOUND_PX}, shift);
        GoldenImage.assertMatchesGolden(
                renderThroughView(ClockConfig.defaultConfig(), BURN_IN_PLUS_MINUTE),
                "/golden/clock_burnin_plus8_1280x720.png", "clock_burnin_plus8",
                CHANNEL_THRESHOLD, MAX_MISMATCH_FRACTION);
    }

    /**
     * The composition keeps a fully black border at least as wide as the burn-in
     * envelope on every edge, so translating it by up to {@code ±8 px} can only
     * ever clip black — never a hand, numeral, tick, or digital glyph. This is
     * what lets the whole-composition shift stay clip-free at its extremes. It is
     * a geometry invariant of the renderer, so it is checked on the renderer's
     * own centered output.
     */
    @Test
    public void compositionKeepsBlackMarginSoBurnInNeverClips() {
        Bitmap center = renderShifted(ClockConfig.defaultConfig(), 0, 0);
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
     * This is a renderer/canvas invariant, so it exercises the renderer directly
     * with arbitrary integer shifts rather than the time-derived view offset.
     */
    @Test
    public void wholeCompositionShiftsAsOneRigidUnit() {
        Bitmap center = renderShifted(ClockConfig.defaultConfig(), 0, 0);
        assertOverlapEqualsShift(center,
                renderShifted(ClockConfig.defaultConfig(), BurnInOffset.BOUND_PX, BurnInOffset.BOUND_PX),
                BurnInOffset.BOUND_PX, BurnInOffset.BOUND_PX);
        assertOverlapEqualsShift(center,
                renderShifted(ClockConfig.defaultConfig(), -BurnInOffset.BOUND_PX, -BurnInOffset.BOUND_PX),
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
