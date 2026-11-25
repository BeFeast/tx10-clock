package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

/** The cache: refresh, failure, stale, never the feed address anywhere but the source file. */
public class CalendarRepositoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ZoneId TZ = ZoneId.of("Asia/Jerusalem");
    private static final String URL = "https://calendar.example/calendar/ical/secret-token-abc/basic.ics";
    private static final String FEED = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\n"
            + "BEGIN:VEVENT\r\nUID:a@t\r\nSUMMARY:Смена\r\nDTSTART:20261020T190000Z\r\nDTEND:20261021T030000Z\r\nEND:VEVENT\r\n"
            + "END:VCALENDAR\r\n";

    private final AtomicReference<Instant> now = new AtomicReference<>(t("2026-10-21T05:00").toInstant());
    private final AtomicReference<Object> next = new AtomicReference<>();

    private static ZonedDateTime t(String local) {
        return LocalDateTime.parse(local).atZone(TZ);
    }

    private Clock clock() {
        return new Clock() {
            @Override public ZoneId getZone() { return TZ; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
    }

    private CalendarRepository repo(File config, File cache) {
        return new CalendarRepository(config, cache, (URI url) -> {
            Object answer = next.get();
            if (answer instanceof String) {
                throw new CalendarRepository.FetchException((String) answer);
            }
            return (byte[]) answer;
        }, clock());
    }

    private File configured() throws Exception {
        File dir = tmp.newFolder("config");
        Files.write(new File(dir, CalendarSource.FILE).toPath(), ("{\"schemaVersion\":1,\"icsUrl\":\"" + URL
                + "\",\"shiftKeywords\":[\"смена\"]}").getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    @Test
    public void noSourceMeansNotConnectedAndNoFetch() throws Exception {
        CalendarRepository r = repo(tmp.newFolder("c"), tmp.newFolder("d"));
        CalendarRepository.Snapshot s = r.snapshot(ZonedDateTime.now(clock()));
        assertFalse(s.configured);
        assertNull(s.configError);
        assertFalse(r.refreshDue());
    }

    @Test
    public void aBrokenSourceIsReportedNotGuessed() throws Exception {
        File dir = tmp.newFolder("c");
        Files.write(new File(dir, CalendarSource.FILE).toPath(), "{\"icsUrl\":1}".getBytes(StandardCharsets.UTF_8));
        CalendarRepository.Snapshot s = repo(dir, tmp.newFolder("d")).snapshot(ZonedDateTime.now(clock()));
        assertFalse(s.configured);
        assertEquals("OUT_OF_RANGE", s.configError);
    }

    @Test
    public void aFailedRefreshKeepsTheLastGoodCopyAndSaysItIsStale() throws Exception {
        File cache = tmp.newFolder("d");
        CalendarRepository r = repo(configured(), cache);
        assertTrue(r.refreshDue());
        next.set(FEED.getBytes(StandardCharsets.UTF_8));
        r.refresh();
        CalendarRepository.Snapshot ok = r.snapshot(ZonedDateTime.now(clock()));
        assertTrue(ok.configured);
        assertFalse(ok.stale);
        assertEquals(1, ok.events.size());
        assertFalse("not due right after an attempt", r.refreshDue());

        now.set(now.get().plusSeconds(16 * 60));
        next.set("http-500");
        r.refresh();
        CalendarRepository.Snapshot failed = r.snapshot(ZonedDateTime.now(clock()));
        assertEquals("the schedule is kept", 1, failed.events.size());
        assertTrue(failed.stale);
        assertEquals("http-500", failed.lastError);

        now.set(now.get().plusSeconds(16 * 60));
        next.set("<html>sign in</html>".getBytes(StandardCharsets.UTF_8));
        r.refresh();
        assertEquals("not-ics", r.snapshot(ZonedDateTime.now(clock())).lastError);
        assertEquals(1, r.snapshot(ZonedDateTime.now(clock())).events.size());

        for (File f : cache.listFiles()) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertFalse(f.getName() + " holds the feed address", content.contains("secret-token-abc"));
        }
    }

    private static void source(File dir, String url) throws Exception {
        Files.write(new File(dir, CalendarSource.FILE).toPath(), ("{\"schemaVersion\":1,\"icsUrl\":\"" + url
                + "\",\"shiftKeywords\":[\"смена\"]}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void anotherSourceStartsWithoutACacheAndALateAnswerForTheOldOneIsDropped() throws Exception {
        File config = configured();
        File cache = tmp.newFolder("d");
        CalendarRepository r = repo(config, cache);
        next.set(FEED.getBytes(StandardCharsets.UTF_8));
        r.refresh();
        assertEquals(1, r.snapshot(ZonedDateTime.now(clock())).events.size());

        // The source changes to calendar B: A's copy is not B's, and B is due at once.
        source(config, "https://calendar.example/calendar/ical/other-calendar-b/basic.ics");
        CalendarRepository.Snapshot b = r.snapshot(ZonedDateTime.now(clock()));
        assertTrue(b.configured);
        assertEquals(0, b.fetchedAtMs);
        assertTrue(b.events.isEmpty());
        assertTrue(r.refreshDue());

        // A fetch that started for B ends after the source moved on to C: nothing is written for C.
        CalendarRepository late = new CalendarRepository(config, cache, (URI url) -> {
            try {
                source(config, "https://calendar.example/calendar/ical/third-calendar-c/basic.ics");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return FEED.getBytes(StandardCharsets.UTF_8);
        }, clock());
        late.refresh();
        CalendarRepository.Snapshot c = r.snapshot(ZonedDateTime.now(clock()));
        assertEquals("C shows no copy fetched for B", 0, c.fetchedAtMs);
        assertTrue(c.events.isEmpty());
        assertTrue(r.refreshDue());
    }

    @Test
    public void aTruncatedFeedNeverReplacesTheGoodCopy() throws Exception {
        CalendarRepository r = repo(configured(), tmp.newFolder("d"));
        next.set(FEED.getBytes(StandardCharsets.UTF_8));
        r.refresh();
        now.set(now.get().plusSeconds(16 * 60));
        String cut = FEED.substring(0, FEED.indexOf("END:VEVENT"));
        next.set(cut.getBytes(StandardCharsets.UTF_8));
        r.refresh();
        CalendarRepository.Snapshot s = r.snapshot(ZonedDateTime.now(clock()));
        assertEquals("truncated", s.lastError);
        assertTrue(s.stale);
        assertEquals("the good copy still plans", 1, s.events.size());
    }

    @Test
    public void anOldCopyIsStaleEvenWithoutAnError() throws Exception {
        CalendarRepository r = repo(configured(), tmp.newFolder("d"));
        next.set(FEED.getBytes(StandardCharsets.UTF_8));
        r.refresh();
        now.set(now.get().plusSeconds(2 * 3600));
        CalendarRepository.Snapshot s = r.snapshot(ZonedDateTime.now(clock()));
        assertTrue(s.stale);
        assertNull(s.lastError);
        assertEquals(1, s.events.size());
    }
}
