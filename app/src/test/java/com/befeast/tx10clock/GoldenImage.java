package com.befeast.tx10clock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Small golden-image toolkit shared by the offscreen render tests.
 *
 * <p>Everything here goes through the real Android {@link Bitmap} pipeline
 * (which Robolectric's NATIVE graphics mode backs with the actual platform
 * graphics library) so encode/decode/compare are byte-for-byte consistent with
 * what the device would produce.
 */
final class GoldenImage {

    /** Result of a pixel comparison. */
    static final class Diff {
        final int mismatchedPixels;
        final int totalPixels;
        final int maxChannelDelta;
        final Bitmap diffImage;

        Diff(int mismatchedPixels, int totalPixels, int maxChannelDelta, Bitmap diffImage) {
            this.mismatchedPixels = mismatchedPixels;
            this.totalPixels = totalPixels;
            this.maxChannelDelta = maxChannelDelta;
            this.diffImage = diffImage;
        }

        double mismatchFraction() {
            return totalPixels == 0 ? 0.0 : (double) mismatchedPixels / totalPixels;
        }
    }

    private GoldenImage() {
    }

    /** Loads a golden PNG from the test classpath, or {@code null} if absent. */
    static Bitmap loadFromClasspath(String resourcePath) {
        try (InputStream in = GoldenImage.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read golden resource " + resourcePath, e);
        }
    }

    /** Writes {@code bitmap} to {@code file} as a PNG, creating parent dirs. */
    static void writePng(Bitmap bitmap, File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Could not create directory " + parent);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new RuntimeException("Bitmap.compress returned false for " + file);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write PNG " + file, e);
        }
    }

    /**
     * Compares two same-size bitmaps. A pixel counts as "mismatched" when any
     * ARGB channel differs by more than {@code channelThreshold}. The returned
     * diff image paints mismatched pixels magenta over a dimmed copy of actual.
     */
    static Diff compare(Bitmap actual, Bitmap golden, int channelThreshold) {
        final int w = actual.getWidth();
        final int h = actual.getHeight();
        if (golden.getWidth() != w || golden.getHeight() != h) {
            throw new IllegalArgumentException(
                    "Size mismatch: actual " + w + "x" + h
                            + " vs golden " + golden.getWidth() + "x" + golden.getHeight());
        }

        final int[] a = new int[w * h];
        final int[] g = new int[w * h];
        actual.getPixels(a, 0, w, 0, 0, w, h);
        golden.getPixels(g, 0, w, 0, 0, w, h);

        final int[] diff = new int[w * h];
        int mismatched = 0;
        int maxDelta = 0;
        for (int i = 0; i < a.length; i++) {
            final int pa = a[i];
            final int pg = g[i];
            final int da = Math.abs(((pa >>> 24) & 0xFF) - ((pg >>> 24) & 0xFF));
            final int dr = Math.abs(((pa >>> 16) & 0xFF) - ((pg >>> 16) & 0xFF));
            final int dg = Math.abs(((pa >>> 8) & 0xFF) - ((pg >>> 8) & 0xFF));
            final int db = Math.abs((pa & 0xFF) - (pg & 0xFF));
            final int channelMax = Math.max(Math.max(da, dr), Math.max(dg, db));
            maxDelta = Math.max(maxDelta, channelMax);
            if (channelMax > channelThreshold) {
                mismatched++;
                diff[i] = 0xFFFF00FF; // opaque magenta marks a mismatch
            } else {
                // Dim the actual pixel so real mismatches stand out.
                final int r = ((pa >>> 16) & 0xFF) / 4;
                final int gg = ((pa >>> 8) & 0xFF) / 4;
                final int b = (pa & 0xFF) / 4;
                diff[i] = 0xFF000000 | (r << 16) | (gg << 8) | b;
            }
        }

        final Bitmap diffImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        diffImage.setPixels(diff, 0, w, 0, 0, w, h);
        return new Diff(mismatched, a.length, maxDelta, diffImage);
    }
}
