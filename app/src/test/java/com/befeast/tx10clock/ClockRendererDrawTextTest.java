package com.befeast.tx10clock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Canvas;
import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCanvas;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Proves the renderer actually consumes the compact-date toggle by recording
 * the text it draws. Uses the legacy shadow canvas, which captures each
 * {@code drawText} call, so the assertion is on drawn content rather than
 * pixel geometry.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ClockRendererDrawTextTest {

    // 2026-07-12 22:09:42 UTC renders as "10:09" / "PM SUN, JUL 12 42".
    private static final ZonedDateTime FIXED_TIME =
            ZonedDateTime.of(2026, 7, 12, 22, 9, 42, 0, ZoneOffset.UTC);

    private String drawnText(ClockConfig config) {
        Canvas canvas = new Canvas();
        new ClockRenderer(config).render(canvas, 1280, 720, FIXED_TIME);
        // Shadow.extract avoids the generated Shadows.shadowOf(Canvas) overload,
        // whose signature references an API-31 class absent at compileSdk 29.
        ShadowCanvas shadow = Shadow.extract(canvas);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shadow.getTextHistoryCount(); i++) {
            sb.append(shadow.getDrawnTextEvent(i).text).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void defaultConfigDrawsTheCompactDate() {
        String drawn = drawnText(ClockConfig.defaultConfig());
        assertTrue("month token drawn by default", drawn.contains("JUL"));
        assertTrue("weekday token drawn by default", drawn.contains("SUN"));
    }

    @Test
    public void showDateFalseDrawsNoDate() {
        ClockConfig hidden = ClockConfig.defaultConfig().toBuilder()
                .showDate(false).build();
        String drawn = drawnText(hidden);
        assertFalse("no month token when showDate is false", drawn.contains("JUL"));
        assertFalse("no weekday token when showDate is false", drawn.contains("SUN"));
        // The main time line is unaffected by the date toggle.
        assertTrue("time line still drawn", drawn.contains("10:09"));
    }
}
