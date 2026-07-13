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
    public void defaultConfigMatchesAcceptedContract() {
        ClockConfig c = ClockConfig.defaultConfig();
        assertEquals(0xFF000000, c.backgroundColor);
        assertEquals(0xFF000000, c.faceColor);
        assertEquals(0xFFD1D1D6, c.tickColor);
        assertEquals(0xFFFF9F0A, c.secondHandColor);
        assertEquals(0xFFF5F5F7, c.digitalColor);
        assertEquals(0xFFA1A1A6, c.dateColor);
        assertFalse(c.use24Hour);
        assertTrue(c.showSeconds);
        assertTrue(c.showDate);
        assertEquals(100, c.digitalSizePercent);
        assertEquals(100, c.secondarySizePercent);
        assertTrue(c.burnInEnabled);
        assertEquals(8, c.burnInMaxShiftPx);
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
                .use24Hour(true)
                .showSeconds(false)
                .backgroundColor(0xFF000000)
                .build();
        assertTrue(c.use24Hour);
        assertFalse(c.showSeconds);
        assertEquals(0xFF000000, c.backgroundColor);
    }

    @Test
    public void toBuilderRoundTrips() {
        ClockConfig original = new ClockConfig.Builder()
                .digitalColor(0xFF123456)
                .use24Hour(false)
                .showDate(false)
                .digitalSizePercent(75)
                .burnInEnabled(false)
                .burnInMaxShiftPx(4)
                .build();
        ClockConfig copy = original.toBuilder().build();
        assertEquals(original.digitalColor, copy.digitalColor);
        assertEquals(original.use24Hour, copy.use24Hour);
        assertEquals(original.backgroundColor, copy.backgroundColor);
        assertEquals(original.showDate, copy.showDate);
        assertEquals(original.digitalSizePercent, copy.digitalSizePercent);
        assertEquals(original.secondarySizePercent, copy.secondarySizePercent);
        assertEquals(original.burnInEnabled, copy.burnInEnabled);
        assertEquals(original.burnInMaxShiftPx, copy.burnInMaxShiftPx);
    }

    // --- fromExternal mapping -------------------------------------------------

    @Test
    public void fromExternalDefaultsEqualAcceptedTheme() {
        ClockConfig mapped = ClockConfig.fromExternal(ExternalConfig.defaults());
        ClockConfig theme = ClockConfig.defaultConfig();
        assertEquals(theme.backgroundColor, mapped.backgroundColor);
        assertEquals(theme.faceColor, mapped.faceColor);
        assertEquals(theme.tickColor, mapped.tickColor);
        assertEquals(theme.hourHandColor, mapped.hourHandColor);
        assertEquals(theme.minuteHandColor, mapped.minuteHandColor);
        assertEquals(theme.secondHandColor, mapped.secondHandColor);
        assertEquals(theme.digitalColor, mapped.digitalColor);
        assertEquals(theme.dateColor, mapped.dateColor);
        assertEquals(theme.use24Hour, mapped.use24Hour);
        assertEquals(theme.showSeconds, mapped.showSeconds);
        assertEquals(theme.showDate, mapped.showDate);
        assertEquals(theme.digitalSizePercent, mapped.digitalSizePercent);
        assertEquals(theme.secondarySizePercent, mapped.secondarySizePercent);
        assertEquals(theme.burnInEnabled, mapped.burnInEnabled);
        assertEquals(theme.burnInMaxShiftPx, mapped.burnInMaxShiftPx);
    }

    @Test
    public void fromExternalResolvesApprovedNamesToContractValues() throws Exception {
        ExternalConfig external = ExternalConfig.parse(
                "{\"digitalColor\":\"silver\",\"dateColor\":\"white\","
                        + "\"tickColor\":\"grey\",\"accentColor\":\"white\"}");
        ClockConfig mapped = ClockConfig.fromExternal(external);
        assertEquals(0xFFD1D1D6, mapped.digitalColor);   // silver
        assertEquals(0xFFD1D1D6, mapped.hourHandColor);  // hands follow digital role
        assertEquals(0xFFD1D1D6, mapped.minuteHandColor);
        assertEquals(0xFFF5F5F7, mapped.dateColor);      // white
        assertEquals(0xFFA1A1A6, mapped.tickColor);      // grey
        assertEquals(0xFFF5F5F7, mapped.secondHandColor); // white accent
        // Background stays pure black regardless of any selection.
        assertEquals(0xFF000000, mapped.backgroundColor);
        assertEquals(0xFF000000, mapped.faceColor);
    }

    @Test
    public void fromExternalThreadsBoundedFieldsThrough() throws Exception {
        ExternalConfig external = ExternalConfig.parse(
                "{\"use24Hour\":true,\"showSeconds\":false,\"showDate\":false,"
                        + "\"digitalSizePercent\":60,\"secondarySizePercent\":80,"
                        + "\"burnInEnabled\":false,\"burnInMaxShiftPx\":2}");
        ClockConfig mapped = ClockConfig.fromExternal(external);
        assertTrue(mapped.use24Hour);
        assertFalse(mapped.showSeconds);
        assertFalse(mapped.showDate);
        assertEquals(60, mapped.digitalSizePercent);
        assertEquals(80, mapped.secondarySizePercent);
        assertFalse(mapped.burnInEnabled);
        assertEquals(2, mapped.burnInMaxShiftPx);
    }
}
