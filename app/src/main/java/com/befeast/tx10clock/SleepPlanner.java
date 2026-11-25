package com.befeast.tx10clock;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Suggests the next sleep window from shifts and meetings, with the reason for every boundary.
 *
 * <p>After an overnight shift the window starts once the person is home and wound down; before a
 * day shift it ends at the latest wake that still leaves travel and preparation. A meeting or the
 * next shift can only cut a window short: the shortfall is shown, never hidden, and an impossible
 * window asks for review instead of producing a negative duration. Durations are elapsed time, so
 * a night across a DST change counts its real hours. There is no alarm: the wake time is a plan.
 */
public final class SleepPlanner {

    public enum Status { NONE, OK, SHORT, NEEDS_REVIEW }

    /** Buffers in minutes. */
    public static final class Buffers {
        public final int travelToWork;
        public final int travelHome;
        public final int preparation;
        public final int windDown;
        public final int meetingPreparation;

        public Buffers(int travelToWork, int travelHome, int preparation, int windDown, int meetingPreparation) {
            this.travelToWork = travelToWork;
            this.travelHome = travelHome;
            this.preparation = preparation;
            this.windDown = windDown;
            this.meetingPreparation = meetingPreparation;
        }

        public static Buffers defaults() {
            return new Buffers(30, 30, 45, 30, 30);
        }
    }

    /** A suggestion. {@code start}/{@code end} are null when there is nothing to plan or it is impossible. */
    public static final class Plan {
        public final Status status;
        public final ZonedDateTime start;
        public final ZonedDateTime end;
        public final ZonedDateTime windDownFrom;
        public final long targetMinutes;
        public final long availableMinutes;
        public final SleepEvent basis;
        public final List<String> reasons;

        Plan(Status status, ZonedDateTime start, ZonedDateTime end, ZonedDateTime windDownFrom,
                long targetMinutes, long availableMinutes, SleepEvent basis, List<String> reasons) {
            this.status = status;
            this.start = start;
            this.end = end;
            this.windDownFrom = windDownFrom;
            this.targetMinutes = targetMinutes;
            this.availableMinutes = availableMinutes;
            this.basis = basis;
            this.reasons = Collections.unmodifiableList(reasons);
        }

        public long plannedMinutes() {
            return start == null ? 0 : Duration.between(start, end).toMinutes();
        }

        public long deficitMinutes() {
            return Math.max(0, targetMinutes - plannedMinutes());
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private SleepPlanner() {
    }

    public static Plan suggest(ZonedDateTime now, List<SleepEvent> events, Buffers b, int targetMinutes) {
        List<SleepEvent> shifts = new ArrayList<>();
        List<SleepEvent> meetings = new ArrayList<>();
        for (SleepEvent e : events) {
            (e.kind == SleepEvent.Kind.SHIFT ? shifts : meetings).add(e);
        }
        Collections.sort(shifts, (x, y) -> x.start.compareTo(y.start));
        Collections.sort(meetings, (x, y) -> x.start.compareTo(y.start));

        Plan best = null;
        ZonedDateTime bestAnchor = null;
        for (SleepEvent shift : shifts) {
            Plan candidate;
            ZonedDateTime anchor;
            if (shift.overnight()) {
                anchor = shift.end.plusMinutes(b.travelHome + b.windDown);
                candidate = afterShift(now, shift, shifts, meetings, b, targetMinutes);
            } else {
                anchor = shift.start.minusMinutes(b.travelToWork + b.preparation + targetMinutes);
                candidate = beforeShift(now, shift, shifts, meetings, b, targetMinutes);
            }
            if (candidate == null) {
                continue;
            }
            if (best == null || anchor.isBefore(bestAnchor)) {
                best = candidate;
                bestAnchor = anchor;
            }
        }
        if (best != null) {
            return best;
        }
        List<String> none = new ArrayList<>();
        none.add("No shifts ahead — sleep window not calculated");
        return new Plan(Status.NONE, null, null, null, targetMinutes, 0, null, none);
    }

    /** Main sleep right after an overnight shift; null once that window is over. */
    private static Plan afterShift(ZonedDateTime now, SleepEvent shift, List<SleepEvent> shifts,
            List<SleepEvent> meetings, Buffers b, int target) {
        ZonedDateTime earliest = shift.end.plusMinutes(b.travelHome + b.windDown);
        List<String> reasons = new ArrayList<>();
        reasons.add("Shift until " + at(now, shift.end) + " + travel " + b.travelHome
                + " min + wind-down " + b.windDown + " min → sleep from " + at(now, earliest));
        earliest = afterOverlappingMeetings(now, earliest, meetings, b, reasons);
        Limit limit = nextLimit(shift.end, earliest, shifts, meetings, b, now);
        ZonedDateTime wanted = earliest.plusMinutes(target);
        if (limit != null && !limit.latestWake.isAfter(earliest)) {
            reasons.add(limit.reason);
            reasons.add("Wake-up before sleep starts — the plan needs review");
            if (limit.latestWake.isBefore(now) && earliest.isBefore(now)) {
                return null;
            }
            return new Plan(Status.NEEDS_REVIEW, null, null, null, target, 0, shift, reasons);
        }
        ZonedDateTime end = wanted;
        long available = target;
        if (limit != null && limit.latestWake.isBefore(wanted)) {
            end = limit.latestWake;
            available = Duration.between(earliest, end).toMinutes();
            reasons.add(limit.reason);
        } else {
            reasons.add("Target " + hours(target) + " → until " + at(now, wanted));
        }
        if (!end.isAfter(now)) {
            return null;
        }
        Status status = available < target ? Status.SHORT : Status.OK;
        if (status == Status.SHORT) {
            reasons.add("Available " + hours(available) + " — short by " + hours(target - available));
        }
        return new Plan(status, earliest, end, earliest.minusMinutes(b.windDown), target, available, shift, reasons);
    }

    /** Main sleep before a day shift, ending at the latest wake; null once the wake has passed. */
    private static Plan beforeShift(ZonedDateTime now, SleepEvent shift, List<SleepEvent> shifts,
            List<SleepEvent> meetings, Buffers b, int target) {
        ZonedDateTime latestWake = shift.start.minusMinutes(b.travelToWork + b.preparation);
        if (!latestWake.isAfter(now)) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        reasons.add("Shift at " + at(now, shift.start) + " − travel " + b.travelToWork + " min − getting ready "
                + b.preparation + " min → wake up by " + at(now, latestWake));
        ZonedDateTime start = latestWake.minusMinutes(target);
        ZonedDateTime end = latestWake;
        // A meeting still running when sleep would start pushes the start later.
        start = afterOverlappingMeetings(now, start, meetings, b, reasons);
        // A meeting inside the night moves the wake earlier.
        for (SleepEvent m : meetings) {
            ZonedDateTime wake = m.start.minusMinutes(b.meetingPreparation);
            if (m.start.isAfter(start) && wake.isBefore(end)) {
                end = wake;
                reasons.add(meetingReason(now, m, b, wake));
            }
        }
        // The previous shift can push the start later.
        for (SleepEvent p : shifts) {
            if (p == shift || !p.end.isAfter(start.minusMinutes(b.travelHome + b.windDown)) || !p.start.isBefore(end)) {
                continue;
            }
            ZonedDateTime earliest = p.end.plusMinutes(b.travelHome + b.windDown);
            reasons.add("Previous shift until " + at(now, p.end) + " → sleep not before " + at(now, earliest));
            start = earliest;
        }
        if (!end.isAfter(start)) {
            reasons.add("Wake-up before sleep starts — the plan needs review");
            return new Plan(Status.NEEDS_REVIEW, null, null, null, target, 0, shift, reasons);
        }
        long available = Duration.between(start, end).toMinutes();
        if (available >= target) {
            reasons.add("Target " + hours(target) + " → sleep from " + at(now, start) + ", wind-down from "
                    + at(now, start.minusMinutes(b.windDown)));
        }
        Status status = available < target ? Status.SHORT : Status.OK;
        if (status == Status.SHORT) {
            reasons.add("Available " + hours(available) + " — short by " + hours(target - available));
        }
        return new Plan(status, start, end, start.minusMinutes(b.windDown), target, available, shift, reasons);
    }

    private static final class Limit {
        final ZonedDateTime latestWake;
        final String reason;

        Limit(ZonedDateTime latestWake, String reason) {
            this.latestWake = latestWake;
            this.reason = reason;
        }
    }

    /** Moves {@code start} past every meeting that is still running then, plus wind-down. */
    private static ZonedDateTime afterOverlappingMeetings(ZonedDateTime now, ZonedDateTime start,
            List<SleepEvent> meetings, Buffers b, List<String> reasons) {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (SleepEvent m : meetings) {
                if (!m.start.isAfter(start) && m.end.isAfter(start)) {
                    start = m.end.plusMinutes(b.windDown);
                    reasons.add("Meeting " + at(now, m.start) + "–" + at(now, m.end) + " + wind-down "
                            + b.windDown + " min → sleep not before " + at(now, start));
                    moved = true;
                }
            }
        }
        return start;
    }

    /** The first commitment after a shift that bounds the wake: the next shift or a meeting. */
    private static Limit nextLimit(ZonedDateTime shiftEnd, ZonedDateTime earliest, List<SleepEvent> shifts,
            List<SleepEvent> meetings, Buffers b, ZonedDateTime now) {
        Limit limit = null;
        for (SleepEvent s : shifts) {
            if (!s.start.isAfter(shiftEnd)) {
                continue;
            }
            ZonedDateTime wake = s.start.minusMinutes(b.travelToWork + b.preparation);
            limit = new Limit(wake, "Next shift at " + at(now, s.start) + " − travel " + b.travelToWork
                    + " min − getting ready " + b.preparation + " min → wake up by " + at(now, wake));
            break;
        }
        for (SleepEvent m : meetings) {
            if (!m.start.isAfter(earliest)) {
                continue;
            }
            ZonedDateTime wake = m.start.minusMinutes(b.meetingPreparation);
            if (limit == null || wake.isBefore(limit.latestWake)) {
                limit = new Limit(wake, meetingReason(now, m, b, wake));
            }
            break;
        }
        return limit;
    }

    private static String meetingReason(ZonedDateTime now, SleepEvent m, Buffers b, ZonedDateTime wake) {
        return "Meeting at " + at(now, m.start) + " − preparation " + b.meetingPreparation
                + " min → wake up by " + at(now, wake);
    }

    /** "HH:mm" today, "dd.MM HH:mm" on another date. */
    static String at(ZonedDateTime now, ZonedDateTime t) {
        ZonedDateTime local = t.withZoneSameInstant(now.getZone());
        return local.toLocalDate().equals(now.toLocalDate()) ? TIME.format(local) : DAY_TIME.format(local);
    }

    static String hours(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }
}
