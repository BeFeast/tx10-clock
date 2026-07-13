package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * "config" static checks: the default theme is stable and the builder round
 * trips. Guards against accidental changes to colours that would silently
 * shift the golden image.
 */
public class ClockConfigTest {

    @Test
    public void defaultConfigIsOpaqueDarkTheme() {
        ClockConfig c = ClockConfig.defaultConfig();
        assertEquals(0xFF0E1726, c.backgroundColor);
        assertEquals(0xFF1B2A41, c.faceColor);
        assertEquals(0xFF4FC3F7, c.secondHandColor);
        assertTrue(c.use24Hour);
        assertTrue(c.showSeconds);
        // Every colour must be fully opaque so frames have no alpha bleed.
        for (int argb : new int[]{
                c.backgroundColor, c.faceColor, c.tickColor, c.hourHandColor,
                c.minuteHandColor, c.secondHandColor, c.digitalColor, c.dateColor}) {
            assertEquals("alpha must be 0xFF", 0xFF, (argb >>> 24) & 0xFF);
        }
    }

    @Test
    public void builderOverridesApply() {
        ClockConfig c = new ClockConfig.Builder()
                .use24Hour(false)
                .showSeconds(false)
                .backgroundColor(0xFF000000)
                .build();
        assertFalse(c.use24Hour);
        assertFalse(c.showSeconds);
        assertEquals(0xFF000000, c.backgroundColor);
    }

    @Test
    public void toBuilderRoundTrips() {
        ClockConfig original = new ClockConfig.Builder()
                .digitalColor(0xFF123456)
                .use24Hour(false)
                .build();
        ClockConfig copy = original.toBuilder().build();
        assertEquals(original.digitalColor, copy.digitalColor);
        assertEquals(original.use24Hour, copy.use24Hour);
        assertEquals(original.backgroundColor, copy.backgroundColor);
    }
}
