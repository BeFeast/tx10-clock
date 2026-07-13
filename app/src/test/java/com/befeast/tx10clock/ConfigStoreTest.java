package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Deterministic last-known-good / reload / atomic-replacement / status checks
 * for {@link ConfigStore}. Pure JVM: the store is File-based, so a real
 * temporary directory stands in for {@code getExternalFilesDir()}.
 */
public class ConfigStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Clock FIXED =
            Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private ConfigStore store(File dir) {
        return new ConfigStore(dir, ExternalConfig.MAX_BYTES, FIXED);
    }

    private void write(File dir, String name, String content) throws Exception {
        ConfigStore.atomicWrite(new File(dir, name), content.getBytes(StandardCharsets.UTF_8));
    }

    // --- reload / last-known-good -------------------------------------------

    @Test
    public void reloadWithNoFileYieldsDefaults() {
        ConfigStore s = store(tmp.getRoot());
        ExternalConfig c = s.reload();
        assertEquals(ExternalConfig.defaults(), c);
        assertEquals(ConfigStore.Source.DEFAULT, s.source());
        assertNull(s.lastRejectReason());
    }

    @Test
    public void reloadAcceptsValidConfig() throws Exception {
        File dir = tmp.getRoot();
        write(dir, ConfigStore.CONFIG_FILE, "{\"bootStart\":false,\"timeZone\":\"UTC\"}");
        ConfigStore s = store(dir);
        ExternalConfig c = s.reload();
        assertFalse(c.bootStart);
        assertEquals("UTC", c.timeZone);
        assertEquals(ConfigStore.Source.EXTERNAL, s.source());
        assertNull(s.lastRejectReason());
    }

    @Test
    public void rejectedReloadRetainsLastKnownGood() throws Exception {
        File dir = tmp.getRoot();
        write(dir, ConfigStore.CONFIG_FILE, "{\"bootStart\":false}");
        ConfigStore s = store(dir);
        assertFalse(s.reload().bootStart);

        // Replace with an invalid document (unknown key). The good copy stays.
        write(dir, ConfigStore.CONFIG_FILE, "{\"faceColor\":\"#fff\"}");
        ExternalConfig after = s.reload();
        assertFalse("last-known-good must survive a rejected reload", after.bootStart);
        assertEquals(ConfigStore.Source.EXTERNAL, s.source());
        assertEquals(ConfigException.Reason.UNKNOWN_KEY, s.lastRejectReason());
    }

    @Test
    public void deletedFileRetainsLastKnownGood() throws Exception {
        File dir = tmp.getRoot();
        write(dir, ConfigStore.CONFIG_FILE, "{\"showSeconds\":false}");
        ConfigStore s = store(dir);
        assertFalse(s.reload().showSeconds);

        Files.delete(new File(dir, ConfigStore.CONFIG_FILE).toPath());
        assertFalse("absence must not clobber last-known-good", s.reload().showSeconds);
    }

    @Test
    public void oversizedFileRejected() throws Exception {
        File dir = tmp.getRoot();
        StringBuilder big = new StringBuilder("{\"timeZone\":\"UTC\"}");
        while (big.length() <= ExternalConfig.MAX_BYTES) {
            big.append(' ');
        }
        Files.write(new File(dir, ConfigStore.CONFIG_FILE).toPath(),
                big.toString().getBytes(StandardCharsets.UTF_8));
        ConfigStore s = store(dir);
        assertEquals(ExternalConfig.defaults(), s.reload());
        assertEquals(ConfigException.Reason.OVERSIZED, s.lastRejectReason());
    }

    // --- representative same-directory atomic replacement -------------------

    @Test
    public void sameDirectoryAtomicReplacementIsPickedUpOnReload() throws Exception {
        File dir = tmp.getRoot();
        ConfigStore s = store(dir);

        write(dir, ConfigStore.CONFIG_FILE, "{\"timeZone\":\"UTC\",\"use24Hour\":true}");
        ExternalConfig first = s.reload();
        assertEquals("UTC", first.timeZone);
        assertTrue(first.use24Hour);

        // Publish a new config exactly as an external updater would: a
        // same-directory temp file atomically renamed over config.json.
        write(dir, ConfigStore.CONFIG_FILE, "{\"timeZone\":\"Asia/Tokyo\",\"use24Hour\":false}");
        ExternalConfig second = s.reload();
        assertEquals("Asia/Tokyo", second.timeZone);
        assertFalse(second.use24Hour);

        // The atomic dance leaves no temporary residue in the directory.
        for (String name : dir.list()) {
            assertFalse("no leftover temp files: " + name, name.endsWith(".tmp"));
        }
    }

    // --- status.json ---------------------------------------------------------

    @Test
    public void statusIsVerifierSafeAndReflectsConfig() throws Exception {
        File dir = tmp.getRoot();
        write(dir, ConfigStore.CONFIG_FILE, "{\"bootStart\":false,\"timeZone\":\"UTC\"}");
        ConfigStore s = store(dir);
        s.reload();
        s.writeStatus(true);

        File statusFile = new File(dir, ConfigStore.STATUS_FILE);
        assertTrue(statusFile.isFile());
        String json = new String(Files.readAllBytes(statusFile.toPath()), StandardCharsets.UTF_8);

        // It must be strict, well-formed JSON (round-trips through our parser).
        Object parsed = Json.parse(json);
        assertTrue(parsed instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;

        assertEquals(1L, map.get("statusSchemaVersion"));
        assertEquals("external", map.get("configSource"));
        assertEquals(Boolean.FALSE, map.get("lastReloadRejected"));
        assertNull(map.get("lastRejectReason"));
        assertEquals(Boolean.TRUE, map.get("bootLaunch"));
        assertEquals(1_700_000_000_000L, map.get("updatedAtEpochMillis"));

        @SuppressWarnings("unchecked")
        Map<String, Object> eff = (Map<String, Object>) map.get("effectiveConfig");
        assertEquals(Boolean.FALSE, eff.get("bootStart"));
        assertEquals("UTC", eff.get("timeZone"));

        // Verifier-safety: no device identifiers, absolute paths, or raw input.
        assertFalse(json.contains("/home/"));
        assertFalse(json.contains("/sdcard/"));
        assertFalse(json.toLowerCase().contains("serial"));
        assertFalse(json.toLowerCase().contains("android_id"));
        assertFalse(json.toLowerCase().contains("secret"));
    }

    @Test
    public void statusRecordsRejectReasonButNotRawInput() throws Exception {
        File dir = tmp.getRoot();
        write(dir, ConfigStore.CONFIG_FILE, "{\"timeZone\":\"Mars/Olympus\"}");
        ConfigStore s = store(dir);
        s.reload();

        String json = s.renderStatus(false);
        assertTrue(json.contains("\"lastReloadRejected\": true"));
        assertTrue(json.contains("\"lastRejectReason\": \"OUT_OF_RANGE\""));
        // The offending raw value is never echoed into the status document.
        assertFalse(json.contains("Mars/Olympus"));
    }

    @Test
    public void statusWriteIsAtomicWithNoResidue() throws Exception {
        File dir = tmp.getRoot();
        ConfigStore s = store(dir);
        s.reload();
        s.writeStatus(false);
        for (String name : dir.list()) {
            assertFalse("no leftover temp files: " + name, name.endsWith(".tmp"));
        }
    }
}
