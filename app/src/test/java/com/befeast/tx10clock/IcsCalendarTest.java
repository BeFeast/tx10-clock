package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Synthetic feeds only. The JVM zone is UTC on purpose: the display zone is Asia/Jerusalem. */
public class IcsCalendarTest {

    private static final ZoneId TZ = ZoneId.of("Asia/Jerusalem");
    private TimeZone jvmZone;

    @Before
    public void utcJvm() {
        jvmZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restore() {
        TimeZone.setDefault(jvmZone);
    }

    private static final String VTIMEZONE = String.join("\r\n",
            "BEGIN:VTIMEZONE", "TZID:Asia/Jerusalem", "X-LIC-LOCATION:Asia/Jerusalem",
            "BEGIN:DAYLIGHT", "TZOFFSETFROM:+0200", "TZOFFSETTO:+0300", "TZNAME:IDT",
            "DTSTART:19700327T020000", "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1FR", "END:DAYLIGHT",
            "BEGIN:STANDARD", "TZOFFSETFROM:+0300", "TZOFFSETTO:+0200", "TZNAME:IST",
            "DTSTART:19701025T020000", "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU", "END:STANDARD",
            "END:VTIMEZONE");

    private static String ics(String... events) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + VTIMEZONE + "\r\n"
                + String.join("\r\n", events) + "\r\nEND:VCALENDAR\r\n";
    }

    private static String event(String... lines) {
        return "BEGIN:VEVENT\r\n" + String.join("\r\n", lines) + "\r\nEND:VEVENT";
    }

    private static ZonedDateTime t(String local) {
        return LocalDateTime.parse(local).atZone(TZ);
    }

    private static CalendarSource source() throws ConfigException {
        return CalendarSource.parse("{\"schemaVersion\":1,\"icsUrl\":\"https://calendar.example/secret/basic.ics\","
                + "\"shiftKeywords\":[\"смена\",\"shift\"]}");
    }

    private static IcsCalendar.Result read(String ics) throws ConfigException {
        return IcsCalendar.read(ics, source(), t("2026-10-20T00:00"), t("2026-10-28T00:00"));
    }

    private static List<String> spans(IcsCalendar.Result r) {
        List<String> out = new ArrayList<>();
        for (SleepEvent e : r.events) {
            out.add(e.kind + " " + e.start.toLocalDateTime() + "–" + e.end.toLocalDateTime());
        }
        return out;
    }

    @Test
    public void recurringNightShiftsKeepWallClockTimesAcrossDstWithExdateOverrideAndCancel() throws Exception {
        String feed = ics(
                event("UID:night@test", "SUMMARY:Ночная смена", "DTSTART;TZID=Asia/Jerusalem:20261020T220000",
                        "DTEND;TZID=Asia/Jerusalem:20261021T060000", "RRULE:FREQ=DAILY;COUNT=6",
                        "EXDATE;TZID=Asia/Jerusalem:20261022T220000"),
                // The 23rd moves an hour later; the 21st is cancelled.
                event("UID:night@test", "SUMMARY:Ночная смена", "RECURRENCE-ID;TZID=Asia/Jerusalem:20261023T220000",
                        "DTSTART;TZID=Asia/Jerusalem:20261023T230000", "DTEND;TZID=Asia/Jerusalem:20261024T070000"),
                event("UID:night@test", "SUMMARY:Ночная смена", "RECURRENCE-ID;TZID=Asia/Jerusalem:20261021T220000",
                        "DTSTART;TZID=Asia/Jerusalem:20261021T220000", "DTEND;TZID=Asia/Jerusalem:20261022T060000",
                        "STATUS:CANCELLED"),
                event("UID:meet@test", "SUMMARY:Встреча с врачом", "DTSTART;TZID=Asia/Jerusalem:20261021T140000",
                        "DTEND;TZID=Asia/Jerusalem:20261021T150000"));
        IcsCalendar.Result r = read(feed);
        assertEquals(new ArrayList<>(java.util.Arrays.asList(
                "SHIFT 2026-10-20T22:00–2026-10-21T06:00",
                "MEETING 2026-10-21T14:00–2026-10-21T15:00",
                "SHIFT 2026-10-23T23:00–2026-10-24T07:00",
                "SHIFT 2026-10-24T22:00–2026-10-25T06:00",
                "SHIFT 2026-10-25T22:00–2026-10-26T06:00")), spans(r));
        SleepEvent dst = r.events.get(3);
        assertEquals("the DST night is 22:00–06:00 on the wall and 9 h long", Duration.ofHours(9),
                Duration.between(dst.start, dst.end));
        assertTrue(r.review.toString(), r.review.isEmpty());
    }

    @Test
    public void floatingTimesStayOnTheWallClockOfTheDisplayZone() throws Exception {
        IcsCalendar.Result r = read(ics(event("UID:float@test", "SUMMARY:Смена без зоны",
                "DTSTART:20261021T220000", "DTEND:20261022T060000")));
        assertEquals(1, r.events.size());
        assertEquals(t("2026-10-21T22:00"), r.events.get(0).start);
        assertEquals(t("2026-10-22T06:00"), r.events.get(0).end);
    }

    @Test
    public void utcTimesConvertToLocal() throws Exception {
        IcsCalendar.Result r = read(ics(event("UID:utc@test", "SUMMARY:Shift A", "DTSTART:20261021T190000Z",
                "DTEND:20261022T030000Z")));
        assertEquals(t("2026-10-21T22:00"), r.events.get(0).start);
        assertEquals(SleepEvent.Kind.SHIFT, r.events.get(0).kind);
    }

    @Test
    public void whatCannotBePlannedSafelyIsListedForReview() throws Exception {
        IcsCalendar.Result r = read(ics(
                event("UID:allday@test", "SUMMARY:Отпуск", "DTSTART;VALUE=DATE:20261022", "DTEND;VALUE=DATE:20261023"),
                event("UID:noend@test", "SUMMARY:Смена без конца", "DTSTART;TZID=Asia/Jerusalem:20261023T080000"),
                // 01:30 on 25.10 happens twice in Jerusalem.
                event("UID:twice@test", "SUMMARY:Ночной звонок", "DTSTART;TZID=Asia/Jerusalem:20261025T013000",
                        "DTEND;TZID=Asia/Jerusalem:20261025T014500"),
                event("UID:dup1@test", "SUMMARY:Смена", "DTSTART;TZID=Asia/Jerusalem:20261026T070000",
                        "DTEND;TZID=Asia/Jerusalem:20261026T150000"),
                event("UID:dup2@test", "SUMMARY:Смена", "DTSTART;TZID=Asia/Jerusalem:20261026T070000",
                        "DTEND;TZID=Asia/Jerusalem:20261026T150000"),
                event("UID:overlap@test", "SUMMARY:Смена вторая", "DTSTART;TZID=Asia/Jerusalem:20261026T140000",
                        "DTEND;TZID=Asia/Jerusalem:20261026T220000")));
        String review = String.join("\n", r.review);
        assertTrue(review, review.contains("All day: Отпуск"));
        assertTrue(review, review.contains("No end time: Смена без конца"));
        assertTrue(review, review.contains("Time change"));
        assertTrue(review, review.contains("Duplicate: Смена"));
        assertTrue(review, review.contains("Shifts overlap"));
        assertEquals("the all-day item is not an event", 2, r.events.size());
    }

    @Test
    public void classificationIsExplicitAndTheSourceIsStrict() throws Exception {
        CalendarSource s = source();
        assertEquals(SleepEvent.Kind.SHIFT, s.kindOf("СМЕНА утро"));
        assertEquals(SleepEvent.Kind.MEETING, s.kindOf("Совещание"));
        String[] bad = {
                "{\"schemaVersion\":1,\"icsUrl\":\"http://x.example/a.ics\",\"shiftKeywords\":[\"a\"]}",
                "{\"schemaVersion\":1,\"icsUrl\":\"https://u:p@x.example/a.ics\",\"shiftKeywords\":[\"a\"]}",
                "{\"schemaVersion\":1,\"icsUrl\":\"https://x.example/a.ics\"}",
                "{\"schemaVersion\":1,\"icsUrl\":\"https://x.example/a.ics\",\"shiftKeywords\":[]}",
                "{\"schemaVersion\":2,\"icsUrl\":\"https://x.example/a.ics\",\"shiftKeywords\":[\"a\"]}",
                "{\"schemaVersion\":1,\"icsUrl\":\"https://x.example/a.ics\",\"shiftKeywords\":[\"a\"],\"x\":1}",
        };
        for (String b : bad) {
            try {
                CalendarSource.parse(b);
                throw new AssertionError("accepted " + b);
            } catch (ConfigException expected) {
                // refused
            }
        }
    }
}
