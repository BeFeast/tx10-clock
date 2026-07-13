package com.befeast.tx10clock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Locale;

/**
 * File-backed store for the app-owned external configuration and the
 * verifier-safe {@code status.json}.
 *
 * <p>The store operates on a plain {@link File} directory — on the device that
 * is {@code getExternalFilesDir(null)} (operator alias
 * {@code /sdcard/Android/data/com.befeast.tx10clock/files}), which needs no
 * storage permission — but the class itself has <b>no Android dependency</b>, so
 * its last-known-good, reload, atomic-replacement, and status behaviours are
 * exercised deterministically under a plain JVM.
 *
 * <h2>Update protocol</h2>
 * A well-behaved external updater (the delivery/operator tooling) publishes a
 * new config by writing a <em>same-directory</em> temporary file and then doing
 * an atomic rename over {@code config.json}. The app never observes a partially
 * written file: it always reads a whole prior or whole new document. The store
 * uses the identical temp-plus-atomic-rename dance for its own
 * {@code status.json} writes (see {@link #atomicWrite(File, byte[])}).
 *
 * <h2>Last-known-good</h2>
 * {@link #reload()} re-reads {@code config.json}. A document that passes strict
 * {@link ExternalConfig} validation becomes the new last-known-good. Anything
 * rejected (or an absent/unreadable file) leaves the previously accepted config
 * in force; the rejection <em>reason</em> is recorded for {@code status.json}.
 */
public final class ConfigStore {

    public static final String CONFIG_FILE = "config.json";
    public static final String STATUS_FILE = "status.json";
    public static final int STATUS_SCHEMA_VERSION = 1;

    /** Where the effective config came from. */
    public enum Source { DEFAULT, EXTERNAL }

    private final File dir;
    private final long maxBytes;
    private final Clock clock;

    private ExternalConfig current = ExternalConfig.defaults();
    private Source source = Source.DEFAULT;
    private ConfigException.Reason lastRejectReason = null;

    public ConfigStore(File dir) {
        this(dir, ExternalConfig.MAX_BYTES, Clock.systemUTC());
    }

    public ConfigStore(File dir, long maxBytes, Clock clock) {
        if (dir == null) {
            throw new NullPointerException("dir");
        }
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    /** The effective (last-known-good) configuration currently in force. */
    public ExternalConfig current() {
        return current;
    }

    /** Where {@link #current()} came from. */
    public Source source() {
        return source;
    }

    /** The reason the most recent reload rejected a document, or {@code null}. */
    public ConfigException.Reason lastRejectReason() {
        return lastRejectReason;
    }

    public File configFile() {
        return new File(dir, CONFIG_FILE);
    }

    public File statusFile() {
        return new File(dir, STATUS_FILE);
    }

    /**
     * Re-read {@code config.json} and update the last-known-good copy iff the
     * document is accepted. Never throws for bad input: a rejected, absent, or
     * unreadable file leaves the prior accepted config in force and returns it.
     */
    public ExternalConfig reload() {
        File f = configFile();
        if (!f.isFile()) {
            // Absence is not a rejection: there is simply no external override.
            return current;
        }
        Path path = f.toPath();
        try {
            long size = Files.size(path);
            if (size > maxBytes) {
                lastRejectReason = ConfigException.Reason.OVERSIZED;
                return current;
            }
            byte[] raw = Files.readAllBytes(path);
            ExternalConfig parsed = ExternalConfig.parse(raw);
            current = parsed;
            source = Source.EXTERNAL;
            lastRejectReason = null;
            return current;
        } catch (ConfigException ex) {
            lastRejectReason = ex.reason();
            return current;
        } catch (IOException ex) {
            // Transient read failure — keep the last-known-good, note it coarsely.
            lastRejectReason = ConfigException.Reason.MALFORMED;
            return current;
        }
    }

    /**
     * Atomically write the verifier-safe {@code status.json}. It exposes only
     * coarse runtime/config state and the effective (operator-supplied) config
     * values — never device identifiers, absolute paths, raw input, or secrets.
     *
     * @param launchedFromBoot whether the current run was started by the
     *     {@code BOOT_COMPLETED} receiver.
     */
    public void writeStatus(boolean launchedFromBoot) throws IOException {
        atomicWrite(statusFile(), renderStatus(launchedFromBoot).getBytes(StandardCharsets.UTF_8));
    }

    /** The exact {@code status.json} body, exposed for deterministic testing. */
    String renderStatus(boolean launchedFromBoot) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"statusSchemaVersion\": ").append(STATUS_SCHEMA_VERSION).append(",\n");
        sb.append("  \"configSource\": ")
                .append(quote(source.name().toLowerCase(Locale.ROOT))).append(",\n");
        sb.append("  \"lastReloadRejected\": ").append(lastRejectReason != null).append(",\n");
        sb.append("  \"lastRejectReason\": ")
                .append(lastRejectReason == null ? "null" : quote(lastRejectReason.name())).append(",\n");
        sb.append("  \"bootLaunch\": ").append(launchedFromBoot).append(",\n");
        sb.append("  \"updatedAtEpochMillis\": ").append(clock.millis()).append(",\n");
        sb.append("  \"effectiveConfig\": {\n");
        sb.append("    \"schemaVersion\": ").append(ExternalConfig.SCHEMA_VERSION).append(",\n");
        sb.append("    \"bootStart\": ").append(current.bootStart).append(",\n");
        sb.append("    \"use24Hour\": ").append(current.use24Hour).append(",\n");
        sb.append("    \"showSeconds\": ").append(current.showSeconds).append(",\n");
        sb.append("    \"timeZone\": ")
                .append(current.timeZone == null ? "null" : quote(current.timeZone)).append(",\n");
        sb.append("    \"digitalColor\": ").append(quote(current.digitalColor)).append(",\n");
        sb.append("    \"dateColor\": ").append(quote(current.dateColor)).append(",\n");
        sb.append("    \"tickColor\": ").append(quote(current.tickColor)).append(",\n");
        sb.append("    \"accentColor\": ").append(quote(current.accentColor)).append(",\n");
        sb.append("    \"showDate\": ").append(current.showDate).append(",\n");
        sb.append("    \"digitalSizePercent\": ").append(current.digitalSizePercent).append(",\n");
        sb.append("    \"secondarySizePercent\": ").append(current.secondarySizePercent).append(",\n");
        sb.append("    \"burnInEnabled\": ").append(current.burnInEnabled).append(",\n");
        sb.append("    \"burnInMaxShiftPx\": ").append(current.burnInMaxShiftPx).append("\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Write {@code content} to {@code target} via a same-directory temporary
     * file and an atomic rename, so a concurrent reader never sees a partial
     * document. This is the exact protocol external updaters use for
     * {@code config.json}; a representative round-trip is covered by the tests.
     */
    public static void atomicWrite(File target, byte[] content) throws IOException {
        Path targetPath = target.toPath();
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, target.getName() + ".", ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, targetPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // Fall back to a best-effort replace on filesystems without
                // atomic-move support; still no partially written target.
                Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
