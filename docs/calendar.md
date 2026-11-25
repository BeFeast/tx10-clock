# Calendar for the sleep plan

The sleep plan reads **one calendar** as a read-only iCal feed. For Google Calendar that is the
calendar's *secret address in iCal format*. Clock never signs in, never writes to the calendar,
and never sends calendar data to Dremla: Dremla receives only the session mode, the music
duration and the end of the sleep window.

Without a configured source the plan says **"Calendar not connected"** and plans nothing. A
missing event never means free time. Debug builds can show clearly labelled test events; release
builds cannot.

## Source file

`calendar-source.json` lives in the same app-scoped directory as `config.json` (see
[`external-config.md`](external-config.md)). It is never committed, logged, cached, shown on
screen or written to `status.json`: the feed address is a credential.

```json
{
  "schemaVersion": 1,
  "icsUrl": "https://calendar.google.com/calendar/ical/<calendar-id>/private-<secret>/basic.ics",
  "shiftKeywords": ["смена"]
}
```

- `icsUrl`: HTTPS only, with no user info or fragment. Anything else rejects the whole file, and
  the screen says the file is invalid.
- `shiftKeywords`: required and non-empty. An event whose title contains one of these words (case
  does not matter) is a **shift**; every other timed event is a **meeting**. Nothing is classified
  by guess.
- Unknown fields, a wrong `schemaVersion`, or a file over 8 KiB are rejected.

Replace the file the same way as `config.json`: write a temporary file in the same directory, then
rename it over the old one. Removing the file disconnects the calendar; the cached copy is then
not used.

## Getting the address (operator, once)

In Google Calendar on the web: **Settings → the chosen calendar → Integrate calendar → Secret
address in iCal format**. Anyone with this address can read the calendar. Treat it like a
password, keep it in a secret store, and pass it only by reference. If it leaks, use **Reset**
there and install the new file.

## Refresh, cache and staleness

- **Fetch:** HTTPS GET, no redirects, 15 s timeouts, at most 2 MiB. The response must be an
  iCalendar document.
- **Cadence:** at most every 15 minutes while Clock is on screen; the screen re-checks every 5
  minutes.
- **On success:** the feed is stored in the app's private files and replaces the previous copy
  atomically.
- **On failure** (network, HTTP status, not iCal, parse error): the last good copy stays in use.
  The screen says **"Calendar out of date"**, with the last successful update time and a short error code.
- A copy older than 60 minutes is shown as stale even without an error.
- A refresh only changes the *suggestion*. A confirmed plan and an active sleep session are never
  rewritten. The plan screen shows "confirmed · calendar changed" with the new suggestion, and
  **Take suggestion** adopts it explicitly; it then needs a confirm.

## What is planned and what needs review

- **Recurrence:** RRULE, RDATE and EXDATE. A RECURRENCE-ID override replaces exactly its instance,
  and a cancelled instance or event disappears.
- **Wall-clock lengths:** every instance keeps the wall-clock length of the first one. A
  22:00–06:00 shift stays 22:00–06:00 on a DST night and is then 9 h (autumn) or 7 h (spring)
  long.
- **Time zones:** times with a TZID use that zone. UTC times are converted, and floating times (no
  zone) are read on Clock's own zone.
- **Listed under "Review" and not planned:**
  - all-day items;
  - events without an end;
  - times shifted by a DST gap or repeated in a DST overlap;
  - duplicates;
  - overlapping shifts.
