package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

/** Deterministic 1280x720 visual-contract checks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClockRendererRenderTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final String GOLDEN_RESOURCE = "/golden/clock_1280x720.png";
    private static final int CHANNEL_THRESHOLD = 8;
    private static final double MAX_MISMATCH_FRACTION = 0.001;
    private static final ZonedDateTime FIXED_TIME = ZonedDateTime.of(
            2026, 7, 12, 22, 9, 42, 0, ZoneOffset.UTC);

    private Bitmap renderFixedFrame() {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        new ClockRenderer(ClockConfig.defaultConfig())
                .render(new Canvas(bitmap), WIDTH, HEIGHT, FIXED_TIME);
        return bitmap;
    }

    @Test
    public void frameHasExactSizeAndContractAnchors() {
        Bitmap frame = renderFixedFrame();
        assertEquals(WIDTH, frame.getWidth());
        assertEquals(HEIGHT, frame.getHeight());
        assertEquals(0xFF000000, frame.getPixel(0, 0));
        assertEquals(0xFF000000, frame.getPixel(WIDTH - 1, HEIGHT - 1));

        assertTrue("orange second hand/calendar accent are present",
                countPixelsNear(frame, 0xFFFF9F0A, 8) > 300);
        assertTrue("analog content occupies the accepted left region",
                countNonBlack(frame, 48, 72, 588, 648) > 5000);
        assertTrue("digital content occupies the accepted right region",
                countNonBlack(frame, 650, 140, 1232, 544) > 8000);
    }

    @Test
    public void offscreenFrameMatchesGolden() {
        GoldenImage.assertMatchesGolden(renderFixedFrame(), GOLDEN_RESOURCE,
                "clock_1280x720", CHANNEL_THRESHOLD, MAX_MISMATCH_FRACTION);
    }

    private static int countNonBlack(Bitmap bitmap, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if ((bitmap.getPixel(x, y) & 0x00FFFFFF) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countPixelsNear(Bitmap bitmap, int color, int tolerance) {
        int tr = (color >>> 16) & 0xFF;
        int tg = (color >>> 8) & 0xFF;
        int tb = color & 0xFF;
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int p = bitmap.getPixel(x, y);
                if (Math.abs(((p >>> 16) & 0xFF) - tr) <= tolerance
                        && Math.abs(((p >>> 8) & 0xFF) - tg) <= tolerance
                        && Math.abs((p & 0xFF) - tb) <= tolerance) {
                    count++;
                }
            }
        }
        return count;
    }
}
