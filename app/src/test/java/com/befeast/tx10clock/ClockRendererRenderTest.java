package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Deterministic offscreen golden harness.
 *
 * <p>The production {@link ClockRenderer} draws into a {@code 1280x720}
 * {@code ARGB_8888} bitmap in the declared API 29 Robolectric environment with
 * NATIVE graphics (real pixels, no device). The frame is pinned to a fixed
 * {@link TimeSource}/{@link ClockConfig} and compared against a committed golden
 * PNG; on mismatch the actual, expected and diff images are written for triage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q) // API 29
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClockRendererRenderTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final String GOLDEN_RESOURCE = "/golden/clock_1280x720.png";

    // Small tolerances absorb negligible anti-aliasing jitter while still
    // catching any real rendering regression.
    private static final int CHANNEL_THRESHOLD = 8;
    private static final double MAX_MISMATCH_FRACTION = 0.002; // 0.2% of pixels

    // Distinct hour/minute/second so all three hands are visually separated.
    private static final ZonedDateTime FIXED_TIME =
            ZonedDateTime.of(2026, 7, 13, 10, 8, 42, 0, ZoneOffset.UTC);

    private Bitmap renderFixedFrame() {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        ClockRenderer renderer = new ClockRenderer(ClockConfig.defaultConfig());
        renderer.render(canvas, WIDTH, HEIGHT, TimeSource.fixed(FIXED_TIME).now());
        return bitmap;
    }

    @Test
    public void frameHasExactSizeAndConfig() {
        Bitmap frame = renderFixedFrame();
        assertEquals(WIDTH, frame.getWidth());
        assertEquals(HEIGHT, frame.getHeight());
        assertEquals(Bitmap.Config.ARGB_8888, frame.getConfig());
    }

    @Test
    public void sceneHasExpectedStructure() {
        Bitmap frame = renderFixedFrame();
        ClockConfig cfg = ClockConfig.defaultConfig();

        // Pure-black corners: the frame is drawn over pure black.
        assertEquals(0xFF000000, cfg.backgroundColor);
        assertEquals(cfg.backgroundColor, frame.getPixel(6, 6));
        assertEquals(cfg.backgroundColor, frame.getPixel(WIDTH - 6, HEIGHT - 6));

        // The analog face lives in the LEFT band. Its ticks and hands paint a
        // meaningful number of non-background pixels inside the disk...
        final int cx = Math.round(WIDTH * 0.255f);
        final int cy = Math.round(HEIGHT * 0.5f);
        final int r = Math.round(Math.min(WIDTH * 0.22f, HEIGHT * 0.405f));
        assertTrue("face is on the left band", cx < WIDTH / 2);

        int diskTotal = 0;
        int diskNonBackground = 0;
        int innerTotal = 0;
        int innerBackground = 0;
        for (int dy = -r; dy <= r; dy += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                final int d2 = dx * dx + dy * dy;
                if (d2 > r * r) {
                    continue;
                }
                final boolean bg = frame.getPixel(cx + dx, cy + dy) == cfg.backgroundColor;
                diskTotal++;
                if (!bg) {
                    diskNonBackground++;
                }
                // ...yet the face stays MINIMAL: the deep interior (inside 0.5r,
                // clear of ticks and mostly clear of hands) is largely black, so
                // this is not an accidental filled disk.
                if (d2 < (r * r) / 4) {
                    innerTotal++;
                    if (bg) {
                        innerBackground++;
                    }
                }
            }
        }
        assertTrue("analog ticks/hands should paint the left face",
                diskNonBackground > 300);
        assertTrue("minimal face: deep interior stays mostly black over pure black",
                innerBackground > innerTotal / 2);

        // The cyan accent (second hand / hub / digital seconds) is present.
        assertTrue("expected cyan accent pixels", countAccentPixels(frame) > 50);

        // The maximally large digital readout lives in the RIGHT band: many
        // bright near-white text pixels appear right of centre, around the
        // vertical middle...
        assertTrue("expected bright digital text on the right",
                countBrightPixelsInRegion(frame, 0.55f, 1.0f, 0.25f, 0.75f) > 200);
        // ...and the big digital block is not down in the far-left band.
        assertTrue("far-left band should not hold the big digital block",
                countBrightPixelsInRegion(frame, 0.0f, 0.15f, 0.25f, 0.75f) < 50);
    }

    @Test
    public void offscreenFrameMatchesGolden() {
        Bitmap actual = renderFixedFrame();
        File outputDir = outputDir();

        Bitmap golden = GoldenImage.loadFromClasspath(GOLDEN_RESOURCE);
        boolean record = Boolean.parseBoolean(System.getProperty("golden.record", "false"));

        if (golden == null || record) {
            File goldenFile = new File(recordDir(), "clock_1280x720.png");
            GoldenImage.writePng(actual, goldenFile);
            fail("Golden image (re)generated at " + goldenFile
                    + " — commit it, then re-run without -Dgolden.record to verify.");
        }

        GoldenImage.Diff diff = GoldenImage.compare(actual, golden, CHANNEL_THRESHOLD);
        if (diff.mismatchFraction() > MAX_MISMATCH_FRACTION) {
            GoldenImage.writePng(actual, new File(outputDir, "clock_1280x720.actual.png"));
            GoldenImage.writePng(golden, new File(outputDir, "clock_1280x720.expected.png"));
            GoldenImage.writePng(diff.diffImage, new File(outputDir, "clock_1280x720.diff.png"));
            fail(String.format(Locale.US,
                    "Golden mismatch: %d/%d pixels (%.4f%%) exceed channel threshold %d "
                            + "(maxDelta=%d). Wrote actual/expected/diff PNGs to %s",
                    diff.mismatchedPixels, diff.totalPixels, diff.mismatchFraction() * 100.0,
                    CHANNEL_THRESHOLD, diff.maxChannelDelta, outputDir.getAbsolutePath()));
        }
    }

    private static int countAccentPixels(Bitmap frame) {
        // Cyan accent: blue dominant, red low, green mid-high.
        int count = 0;
        for (int y = 0; y < HEIGHT; y += 2) {
            for (int x = 0; x < WIDTH; x += 2) {
                int p = frame.getPixel(x, y);
                int r = (p >>> 16) & 0xFF;
                int g = (p >>> 8) & 0xFF;
                int b = p & 0xFF;
                if (b > 180 && r < 140 && g > 150) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Counts near-white ("bright") pixels in a fractional sub-rectangle. */
    private static int countBrightPixelsInRegion(
            Bitmap frame, float leftFrac, float rightFrac, float topFrac, float bottomFrac) {
        int left = Math.round(WIDTH * leftFrac);
        int right = Math.round(WIDTH * rightFrac);
        int top = Math.round(HEIGHT * topFrac);
        int bottom = Math.round(HEIGHT * bottomFrac);
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int p = frame.getPixel(x, y);
                int r = (p >>> 16) & 0xFF;
                int g = (p >>> 8) & 0xFF;
                int b = p & 0xFF;
                if (r > 200 && g > 200 && b > 200) {
                    count++;
                }
            }
        }
        return count;
    }

    private static File outputDir() {
        File dir = new File(System.getProperty("golden.output.dir", "build/golden-output"));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static File recordDir() {
        return new File(System.getProperty("golden.record.dir", "src/test/resources/golden"));
    }
}
