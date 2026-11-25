package com.befeast.tx10clock;

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

/**
 * Behavioural proof that the strict size percentages actually reach the pixels:
 * a 50% line paints strictly less ink than the default 100% line in the same
 * region (a uniform shrink is ~1/4 of the area), while still drawing something.
 * The default (100%) render is byte-for-byte covered by the golden test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClockRendererConfigTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final ZonedDateTime FIXED_TIME =
            ZonedDateTime.of(2026, 7, 12, 22, 9, 42, 0, ZoneOffset.UTC);

    private Bitmap render(ClockConfig config) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        new ClockRenderer(config).render(new Canvas(bitmap), WIDTH, HEIGHT, FIXED_TIME);
        return bitmap;
    }

    @Test
    public void smallerDigitalSizePaintsLessMainInk() {
        ClockConfig full = ClockConfig.defaultConfig();
        ClockConfig half = full.toBuilder().digitalSizePercent(50).build();
        // Main digital digits only (baseline ~311, size 182), above the calendar.
        int fullInk = ink(render(full), 650, 150, 1100, 330);
        int halfInk = ink(render(half), 650, 150, 1100, 330);
        assertTrue("main ink at 50% (" + halfInk + ") < 100% (" + fullInk + ")",
                halfInk < fullInk);
        assertTrue("main line still drawn at 50%", halfInk > 0);
    }

    @Test
    public void smallerSecondarySizePaintsLessMetadataInk() {
        ClockConfig full = ClockConfig.defaultConfig();
        ClockConfig half = full.toBuilder().secondarySizePercent(50).build();
        // Seconds/AM-PM column only; the calendar itself is intentionally fixed-size.
        int fullInk = ink(render(full), 1080, 140, 1232, 330);
        int halfInk = ink(render(half), 1080, 140, 1232, 330);
        assertTrue("metadata ink at 50% (" + halfInk + ") < 100% (" + fullInk + ")",
                halfInk < fullInk);
        assertTrue("metadata still drawn at 50%", halfInk > 0);
    }

    private static int ink(Bitmap bitmap, int left, int top, int right, int bottom) {
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
}
