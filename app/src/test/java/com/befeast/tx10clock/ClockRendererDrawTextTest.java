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
 * Proves the renderer actually consumes the calendar toggle by recording
 * the text it draws. Uses the legacy shadow canvas, which captures each
 * {@code drawText} call, so the assertion is on drawn content rather than
 * pixel geometry.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ClockRendererDrawTextTest {

    // 2026-07-12 22:09:42 UTC renders 10:09 with 42/PM metadata and the July calendar.
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
    public void defaultConfigDrawsTheHybridCalendarAndTimeMetadata() {
        String drawn = drawnText(ClockConfig.defaultConfig());
        assertTrue("month header drawn by default", drawn.contains("JULY 2026"));
        assertTrue("weekday card drawn by default", drawn.contains("SUNDAY"));
        assertTrue("seconds drawn at upper right", drawn.contains("42"));
        assertTrue("period drawn at lower right", drawn.contains("PM"));
    }

    @Test
    public void showDateFalseDrawsNoDate() {
        ClockConfig hidden = ClockConfig.defaultConfig().toBuilder()
                .showDate(false).build();
        String drawn = drawnText(hidden);
        assertFalse("no month header when showDate is false", drawn.contains("JULY 2026"));
        assertFalse("no weekday card when showDate is false", drawn.contains("SUNDAY"));
        // The time lockup is unaffected by the calendar toggle.
        assertTrue("time line still drawn", drawn.contains("10:09"));
        assertTrue("seconds still drawn", drawn.contains("42"));
        assertTrue("period still drawn", drawn.contains("PM"));
    }

    @Test
    public void showSecondsFalseHidesOnlySeconds() {
        ClockConfig hidden = ClockConfig.defaultConfig().toBuilder()
                .showSeconds(false).build();
        String drawn = drawnText(hidden);
        assertFalse("seconds hidden", drawn.contains("42"));
        assertTrue("period remains", drawn.contains("PM"));
        assertTrue("calendar remains", drawn.contains("JULY 2026"));
    }

    @Test
    public void twentyFourHourModeHasSecondsButNoPeriod() {
        ClockConfig twentyFourHour = ClockConfig.defaultConfig().toBuilder()
                .use24Hour(true).build();
        String drawn = drawnText(twentyFourHour);
        assertTrue("24-hour main time drawn", drawn.contains("22:09"));
        assertTrue("seconds remain", drawn.contains("42"));
        assertFalse("period omitted in 24-hour mode", drawn.contains("PM"));
        assertTrue("calendar remains", drawn.contains("JULY 2026"));
    }
}
