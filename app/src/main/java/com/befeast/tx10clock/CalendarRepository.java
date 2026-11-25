package com.befeast.tx10clock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * The calendar as last fetched: a read-only copy of the feed in the app's private files, with the
 * time of the last successful fetch and of the last attempt. A failed refresh keeps the last good
 * copy and marks it stale; it never clears the schedule. The feed address stays in the source
 * file only: it is not logged, cached, or put in an error.
 */
final class CalendarRepository {

    /** Downloads the feed; throws {@link FetchException} with a short code on any failure. */
    interface Fetcher {
        byte[] fetch(URI url) throws FetchException;
    }

    static final class FetchException extends Exception {
        private static final long serialVersionUID = 1L;
        final String code;

        FetchException(String code) {
            super(code);
            this.code = code;
        }
    }

    /** What the screen shows about the calendar. */
    static final class Snapshot {
        final boolean configured;
        final String configError;
        final List<SleepEvent> events;
        final List<String> review;
        final long fetchedAtMs;
        final String lastError;
        final boolean stale;

        Snapshot(boolean configured, String configError, List<SleepEvent> events, List<String> review,
                long fetchedAtMs, String lastError, boolean stale) {
            this.configured = configured;
            this.configError = configError;
            this.events = events;
            this.review = review;
            this.fetchedAtMs = fetchedAtMs;
            this.lastError = lastError;
            this.stale = stale;
        }
    }

    static final Duration STALE_AFTER = Duration.ofMinutes(60);
    static final Duration REFRESH_EVERY = Duration.ofMinutes(15);
    private static final String CACHE = "calendar-cache.ics";
    private static final String META = "calendar-cache.properties";

    private final File configDir;
    private final File cacheDir;
    private final Fetcher fetcher;
    private final Clock clock;

    CalendarRepository(File configDir, File cacheDir, Fetcher fetcher, Clock clock) {
        this.configDir = configDir;
        this.cacheDir = cacheDir;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /** From the cached copy only; never touches the network. */
    Snapshot snapshot(ZonedDateTime now) {
        CalendarSource source;
        try {
            source = CalendarSource.load(configDir);
        } catch (ConfigException e) {
            return new Snapshot(false, e.reason().name(), empty(), empty(), 0, null, false);
        }
        if (source == null) {
            return new Snapshot(false, null, empty(), empty(), 0, null, false);
        }
        Properties meta = metaFor(source);
        long fetchedAt = Long.parseLong(meta.getProperty("fetchedAt", "0"));
        String error = meta.getProperty("lastError");
        boolean stale = error != null || fetchedAt == 0
                || now.toInstant().isAfter(Instant.ofEpochMilli(fetchedAt).plus(STALE_AFTER));
        List<SleepEvent> events = empty();
        List<String> review = empty();
        File cache = new File(cacheDir, CACHE);
        if (fetchedAt > 0 && cache.isFile()) {
            try {
                String ics = new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
                IcsCalendar.Result result = IcsCalendar.read(ics, source, now.minusDays(1), now.plusDays(8));
                events = result.events;
                review = result.review;
            } catch (IOException | RuntimeException e) {
                error = "cache";
                stale = true;
            }
        }
        return new Snapshot(true, null, events, review, fetchedAt, error, stale);
    }

    /** True when a refresh is due: configured, and no attempt for this source within {@link #REFRESH_EVERY}. */
    boolean refreshDue() {
        CalendarSource source;
        try {
            source = CalendarSource.load(configDir);
        } catch (ConfigException e) {
            return false;
        }
        if (source == null) {
            return false;
        }
        long attempt = Long.parseLong(metaFor(source).getProperty("attemptAt", "0"));
        return clock.millis() - attempt >= REFRESH_EVERY.toMillis();
    }

    /** One fetch; on success replaces the cached copy atomically, on failure keeps it. */
    void refresh() {
        CalendarSource source;
        try {
            source = CalendarSource.load(configDir);
        } catch (ConfigException e) {
            return;
        }
        if (source == null) {
            return;
        }
        String fingerprint = source.fingerprint();
        Properties meta = metaFor(source);
        meta.setProperty("source", fingerprint);
        meta.setProperty("attemptAt", Long.toString(clock.millis()));
        byte[] body = null;
        try {
            body = fetcher.fetch(source.url);
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (!text.startsWith("BEGIN:VCALENDAR")) {
                throw new FetchException("not-ics");
            }
            // biweekly reads a cut-off document without complaint: require the whole calendar.
            if (!text.endsWith("END:VCALENDAR")) {
                throw new FetchException("truncated");
            }
            IcsCalendar.read(text, source, ZonedDateTime.now(clock), ZonedDateTime.now(clock).plusDays(1));
        } catch (FetchException e) {
            body = null;
            meta.setProperty("lastError", e.code);
        } catch (RuntimeException e) {
            body = null;
            meta.setProperty("lastError", "parse");
        }
        // The source may have changed while the fetch ran: a late answer for another calendar is dropped.
        if (!fingerprint.equals(currentFingerprint())) {
            return;
        }
        if (body != null) {
            try {
                write(new File(cacheDir, CACHE), body);
                meta.setProperty("fetchedAt", Long.toString(clock.millis()));
                meta.remove("lastError");
            } catch (IOException e) {
                // The previous copy is still in place, so its fetch time stays true.
                meta.setProperty("lastError", "cache-write");
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            meta.store(out, null);
            write(new File(cacheDir, META), out.toByteArray());
        } catch (IOException ignored) {
            // The next snapshot shows the previous state; nothing else depends on it.
        }
    }

    private String currentFingerprint() {
        try {
            CalendarSource now = CalendarSource.load(configDir);
            return now == null ? "" : now.fingerprint();
        } catch (ConfigException e) {
            return "";
        }
    }

    /** The cache state for {@code source}; a copy made for another source counts as none. */
    private Properties metaFor(CalendarSource source) {
        Properties meta = meta();
        if (!source.fingerprint().equals(meta.getProperty("source"))) {
            meta.clear();
        }
        return meta;
    }

    private Properties meta() {
        Properties p = new Properties();
        File file = new File(cacheDir, META);
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                p.clear();
            }
        }
        return p;
    }

    private static void write(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("rename failed");
        }
    }

    private static <T> List<T> empty() {
        return Collections.unmodifiableList(new ArrayList<T>());
    }

    /** The production fetcher: HTTPS GET, no redirects, 15 s timeouts, at most 2 MiB. */
    static final class HttpsFetcher implements Fetcher {
        private static final int MAX_BYTES = 2 * 1024 * 1024;

        @Override
        public byte[] fetch(URI url) throws FetchException {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) url.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(15_000);
                connection.setRequestProperty("Accept", "text/calendar");
                int code = connection.getResponseCode();
                if (code != 200) {
                    throw new FetchException("http-" + code);
                }
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_BYTES) {
                            throw new FetchException("too-large");
                        }
                    }
                    return out.toByteArray();
                }
            } catch (IOException | RuntimeException e) {
                throw new FetchException("network");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }
}
