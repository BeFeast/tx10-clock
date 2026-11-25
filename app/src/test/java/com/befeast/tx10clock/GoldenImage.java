package com.befeast.tx10clock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

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

    /**
     * Assert that {@code actual} matches the committed golden at
     * {@code resourcePath} (a classpath path such as
     * {@code /golden/clock_1280x720.png}) within the accepted CI tolerance.
     *
     * <p>This is the shared golden-comparison workflow used by the offscreen
     * render tests, so every declared scenario (default frame, a representative
     * config change, and each burn-in extreme) checks and reports identically:
     *
     * <ul>
     *   <li>When the golden is absent, or {@code -Dgolden.record=true} is set,
     *       the freshly rendered frame is written under the golden resource
     *       directory and the assertion fails asking for a re-run — the same
     *       record-then-commit flow the default golden uses.</li>
     *   <li>Otherwise the frame is compared pixel-for-pixel. A mismatch beyond
     *       {@code maxMismatchFraction} writes {@code <tag>.actual.png},
     *       {@code <tag>.expected.png}, and {@code <tag>.diff.png} to the golden
     *       output directory (the artifacts CI uploads) and fails. The
     *       {@code tag} keeps multiple scenarios' artifacts from overwriting one
     *       another.</li>
     * </ul>
     */
    static void assertMatchesGolden(Bitmap actual, String resourcePath, String tag,
                                    int channelThreshold, double maxMismatchFraction) {
        Bitmap golden = loadFromClasspath(resourcePath);
        boolean record = Boolean.parseBoolean(System.getProperty("golden.record", "false"));
        if (golden == null || record) {
            File target = new File(recordDir(), baseName(resourcePath));
            writePng(actual, target);
            throw new AssertionError(
                    "Golden generated at " + target + "; rerun without golden.record");
        }
        Diff diff = compare(actual, golden, channelThreshold);
        if (diff.mismatchFraction() > maxMismatchFraction) {
            File out = outputDir();
            writePng(actual, new File(out, tag + ".actual.png"));
            writePng(golden, new File(out, tag + ".expected.png"));
            writePng(diff.diffImage, new File(out, tag + ".diff.png"));
            throw new AssertionError(String.format(Locale.US,
                    "Golden mismatch for %s: %.4f%% (max %.4f%%), artifacts in %s",
                    tag, diff.mismatchFraction() * 100.0,
                    maxMismatchFraction * 100.0, out));
        }
    }

    /** Directory the failure triage artifacts are written to (CI uploads it). */
    private static File outputDir() {
        File dir = new File(System.getProperty("golden.output.dir", "build/golden-output"));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Directory freshly recorded goldens are written to (override via property). */
    private static File recordDir() {
        return new File(System.getProperty("golden.record.dir", "src/test/resources/golden"));
    }

    /** The trailing file name of a {@code /golden/...} classpath resource path. */
    private static String baseName(String resourcePath) {
        int slash = resourcePath.lastIndexOf('/');
        return slash < 0 ? resourcePath : resourcePath.substring(slash + 1);
    }
}
