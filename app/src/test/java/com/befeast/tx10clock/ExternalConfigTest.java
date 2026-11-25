package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * Deterministic parser/default/error/range checks for the renderer-agnostic
 * {@link ExternalConfig}. Pure JVM — no Android, no device — so strict ingestion
 * is verifiable independent of the licence-gated Android build.
 */
public class ExternalConfigTest {

    private static ConfigException.Reason reasonOf(String json) {
        ConfigException ex = assertThrows(ConfigException.class, () -> ExternalConfig.parse(json));
        return ex.reason();
    }

    // --- defaults & happy path ----------------------------------------------

    @Test
    public void defaultsAreBootStartTrueTwelveHourAndSystemZone() {
        ExternalConfig c = ExternalConfig.defaults();
        assertTrue(c.bootStart);
        assertFalse(c.use24Hour);
        assertTrue(c.showSeconds);
        assertNull(c.timeZone);
    }

    @Test
    public void defaultsKeepAcceptedRendererSelections() {
        ExternalConfig c = ExternalConfig.defaults();
        assertEquals("white", c.digitalColor);
        assertEquals("grey", c.dateColor);
        assertEquals("silver", c.tickColor);
        assertEquals("orange", c.accentColor);
        assertTrue(c.showDate);
        assertEquals(100, c.digitalSizePercent);
        assertEquals(100, c.secondarySizePercent);
        assertTrue(c.burnInEnabled);
        assertEquals(ExternalConfig.MAX_BURN_IN_SHIFT_PX, c.burnInMaxShiftPx);
        assertEquals(8, ExternalConfig.MAX_BURN_IN_SHIFT_PX);
    }

    @Test
    public void emptyObjectYieldsDefaults() throws Exception {
        assertEquals(ExternalConfig.defaults(), ExternalConfig.parse("{}"));
    }

    @Test
    public void fullValidDocumentParses() throws Exception {
        ExternalConfig c = ExternalConfig.parse(
                "{\"schemaVersion\":1,\"bootStart\":false,\"use24Hour\":false,"
                        + "\"showSeconds\":false,\"timeZone\":\"America/New_York\"}");
        assertFalse(c.bootStart);
        assertFalse(c.use24Hour);
        assertFalse(c.showSeconds);
        assertEquals("America/New_York", c.timeZone);
        assertEquals(ZoneId.of("America/New_York"), c.resolveZone(ZoneId.of("UTC")));
    }

    @Test
    public void partialDocumentMergesWithDefaults() throws Exception {
        ExternalConfig c = ExternalConfig.parse("{\"bootStart\":false}");
        assertFalse(c.bootStart);
        assertFalse(c.use24Hour);  // 12-hour default retained
        assertTrue(c.showSeconds); // default retained
        assertNull(c.timeZone);
    }

    @Test
    public void fullRendererSelectionDocumentParses() throws Exception {
        ExternalConfig c = ExternalConfig.parse(
                "{\"digitalColor\":\"silver\",\"dateColor\":\"white\","
                        + "\"tickColor\":\"grey\",\"accentColor\":\"white\","
                        + "\"showDate\":false,\"digitalSizePercent\":75,"
                        + "\"secondarySizePercent\":50,"
                        + "\"burnInEnabled\":false,\"burnInMaxShiftPx\":4}");
        assertEquals("silver", c.digitalColor);
        assertEquals("white", c.dateColor);
        assertEquals("grey", c.tickColor);
        assertEquals("white", c.accentColor);
        assertFalse(c.showDate);
        assertEquals(75, c.digitalSizePercent);
        assertEquals(50, c.secondarySizePercent);
        assertFalse(c.burnInEnabled);
        assertEquals(4, c.burnInMaxShiftPx);
    }

    @Test
    public void sizeAndBurnInBoundsAreInclusive() throws Exception {
        ExternalConfig low = ExternalConfig.parse(
                "{\"digitalSizePercent\":50,\"secondarySizePercent\":50,\"burnInMaxShiftPx\":0}");
        assertEquals(50, low.digitalSizePercent);
        assertEquals(50, low.secondarySizePercent);
        assertEquals(0, low.burnInMaxShiftPx);

        ExternalConfig high = ExternalConfig.parse(
                "{\"digitalSizePercent\":100,\"secondarySizePercent\":100,\"burnInMaxShiftPx\":8}");
        assertEquals(100, high.digitalSizePercent);
        assertEquals(100, high.secondarySizePercent);
        assertEquals(8, high.burnInMaxShiftPx);
    }

    @Test
    public void resolveZoneFallsBackWhenUnset() throws Exception {
        ExternalConfig c = ExternalConfig.parse("{}");
        assertEquals(ZoneId.of("UTC"), c.resolveZone(ZoneId.of("UTC")));
    }

    @Test
    public void whitespaceAroundDocumentIsAccepted() throws Exception {
        assertEquals(ExternalConfig.defaults(), ExternalConfig.parse("  \n\t {}\n "));
    }

    // --- structural rejections ----------------------------------------------

    @Test
    public void malformedJsonRejected() {
        assertEquals(ConfigException.Reason.MALFORMED, reasonOf("{"));
        assertEquals(ConfigException.Reason.MALFORMED, reasonOf("{\"bootStart\":}"));
        assertEquals(ConfigException.Reason.MALFORMED, reasonOf("not json"));
    }

    @Test
    public void trailingContentRejected() {
        assertEquals(ConfigException.Reason.MALFORMED, reasonOf("{} garbage"));
        assertEquals(ConfigException.Reason.MALFORMED, reasonOf("{}{}"));
    }

    @Test
    public void nonObjectTopLevelRejected() {
        assertEquals(ConfigException.Reason.NOT_OBJECT, reasonOf("[]"));
        assertEquals(ConfigException.Reason.NOT_OBJECT, reasonOf("42"));
        assertEquals(ConfigException.Reason.NOT_OBJECT, reasonOf("\"hi\""));
        assertEquals(ConfigException.Reason.NOT_OBJECT, reasonOf("true"));
    }

    @Test
    public void duplicateKeyRejected() {
        assertEquals(ConfigException.Reason.DUPLICATE_KEY,
                reasonOf("{\"bootStart\":true,\"bootStart\":false}"));
    }

    @Test
    public void unknownKeyRejected() {
        assertEquals(ConfigException.Reason.UNKNOWN_KEY, reasonOf("{\"colorHex\":\"#fff\"}"));
        assertEquals(ConfigException.Reason.UNKNOWN_KEY, reasonOf("{\"bootStart\":true,\"extra\":1}"));
    }

    @Test
    public void deeplyNestedDocumentRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Json.MAX_DEPTH + 5; i++) {
            sb.append('[');
        }
        assertEquals(ConfigException.Reason.MALFORMED, reasonOf(sb.toString()));
    }

    // --- type & range rejections --------------------------------------------

    @Test
    public void wrongTypeRejected() {
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"bootStart\":\"true\"}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"showSeconds\":1}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"timeZone\":42}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"schemaVersion\":\"1\"}"));
        // A fractional number is not an acceptable integer schemaVersion.
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"schemaVersion\":1.5}"));
    }

    @Test
    public void wrongTypeRendererSelectionsRejected() {
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"digitalColor\":7}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"accentColor\":true}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"showDate\":\"yes\"}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"digitalSizePercent\":\"90\"}"));
        // A fractional size or shift is not an acceptable integer.
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"digitalSizePercent\":75.5}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"burnInMaxShiftPx\":4.2}"));
        assertEquals(ConfigException.Reason.WRONG_TYPE, reasonOf("{\"burnInEnabled\":0}"));
    }

    @Test
    public void unapprovedColorNameRejected() {
        // Only names in the approved palette are accepted — never raw values.
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"digitalColor\":\"red\"}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"dateColor\":\"#FF0000\"}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"tickColor\":\"0xFF00FF00\"}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"accentColor\":\"ORANGE\"}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"accentColor\":\"black\"}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"digitalColor\":\"\"}"));
    }

    @Test
    public void outOfRangeSizesRejected() {
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"digitalSizePercent\":49}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"digitalSizePercent\":101}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"secondarySizePercent\":0}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"secondarySizePercent\":200}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"digitalSizePercent\":-75}"));
    }

    @Test
    public void outOfRangeBurnInShiftRejected() {
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"burnInMaxShiftPx\":9}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"burnInMaxShiftPx\":-1}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"burnInMaxShiftPx\":800}"));
    }

    @Test
    public void duplicateRendererKeyRejected() {
        assertEquals(ConfigException.Reason.DUPLICATE_KEY,
                reasonOf("{\"accentColor\":\"orange\",\"accentColor\":\"white\"}"));
    }

    @Test
    public void outOfRangeSchemaVersionRejected() {
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"schemaVersion\":2}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"schemaVersion\":0}"));
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"schemaVersion\":-1}"));
    }

    @Test
    public void invalidTimeZoneRejected() {
        assertEquals(ConfigException.Reason.OUT_OF_RANGE, reasonOf("{\"timeZone\":\"Mars/Olympus\"}"));
    }

    @Test
    public void oversizedTimeZoneRejected() {
        StringBuilder z = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            z.append('A');
        }
        assertEquals(ConfigException.Reason.OUT_OF_RANGE,
                reasonOf("{\"timeZone\":\"" + z + "\"}"));
    }

    // --- oversized document -------------------------------------------------

    @Test
    public void oversizedDocumentRejected() {
        byte[] big = new byte[ExternalConfig.MAX_BYTES + 1];
        Arrays.fill(big, (byte) ' ');
        ConfigException ex =
                assertThrows(ConfigException.class, () -> ExternalConfig.parse(big));
        assertEquals(ConfigException.Reason.OVERSIZED, ex.reason());
    }

    @Test
    public void byteAndStringParseAgree() throws Exception {
        String json = "{\"use24Hour\":false}";
        assertEquals(ExternalConfig.parse(json),
                ExternalConfig.parse(json.getBytes(StandardCharsets.UTF_8)));
    }

    // --- value semantics -----------------------------------------------------

    @Test
    public void equalsAndHashCodeByValue() throws Exception {
        ExternalConfig a = ExternalConfig.parse("{\"timeZone\":\"UTC\"}");
        ExternalConfig b = ExternalConfig.parse("{\"timeZone\":\"UTC\"}");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(ExternalConfig.defaults()));
    }
}
