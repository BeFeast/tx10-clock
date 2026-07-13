package com.befeast.tx10clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Plain-JVM contract tests for the deterministic burn-in offset engine:
 * bounds, minute cadence, determinism, full coverage, and the visual
 * contract's representative times and fixture frames.
 */
public class BurnInOffsetTest {

    /** The visual contract v0.1.0 reference instant (content-fixtures.json). */
    private static final ZonedDateTime REFERENCE = ZonedDateTime.of(
            2026, 7, 12, 22, 9, 42, 0, ZoneId.of("Asia/Jerusalem"));

    @Test
    public void everyCyclePositionIsWithinContractBounds() {
        for (long minute = 0; minute < BurnInOffset.CYCLE_MINUTES; minute++) {
            BurnInOffset o = BurnInOffset.forMinuteIndex(minute);
            assertTrue("x out of bounds at minute " + minute + ": " + o,
                    o.x >= -BurnInOffset.BOUND_PX && o.x <= BurnInOffset.BOUND_PX);
            assertTrue("y out of bounds at minute " + minute + ": " + o,
                    o.y >= -BurnInOffset.BOUND_PX && o.y <= BurnInOffset.BOUND_PX);
        }
    }

    @Test
    public void boundsHoldForArbitraryIncludingNegativeMinuteIndexes() {
        long[] samples = {Long.MIN_VALUE, -1_000_000L, -1L, 0L, 1L,
                29_731_569L /* the reference instant's minute */, Long.MAX_VALUE};
        for (long minute : samples) {
            BurnInOffset o = BurnInOffset.forMinuteIndex(minute);
            assertTrue("out of bounds at minute " + minute + ": " + o,
                    Math.abs(o.x) <= BurnInOffset.BOUND_PX
                            && Math.abs(o.y) <= BurnInOffset.BOUND_PX);
        }
    }

    @Test
    public void offsetIsStableForEveryInstantWithinAMinute() {
        ZonedDateTime top = REFERENCE.withSecond(0).withNano(0);
        BurnInOffset atTop = BurnInOffset.at(top);
        assertEquals(atTop, BurnInOffset.at(top.plusSeconds(1)));
        assertEquals(atTop, BurnInOffset.at(top.plusSeconds(30)));
        assertEquals(atTop, BurnInOffset.at(top.plusSeconds(59)));
        assertEquals(atTop, BurnInOffset.at(top.plusSeconds(59).plusNanos(999_999_999)));
    }

    @Test
    public void offsetChangesExactlyAtTheMinuteBoundary() {
        ZonedDateTime top = REFERENCE.withSecond(0).withNano(0);
        ZonedDateTime lastInstant = top.plusSeconds(59).plusNanos(999_999_999);
        ZonedDateTime nextMinute = top.plusMinutes(1);
        assertEquals(BurnInOffset.at(top), BurnInOffset.at(lastInstant));
        assertNotEquals(BurnInOffset.at(top), BurnInOffset.at(nextMinute));
    }

    @Test
    public void consecutiveMinutesAlwaysDifferAcrossFullCycleAndWrap() {
        // Cover a whole cycle plus the wrap back to the first cell.
        for (long minute = 0; minute <= BurnInOffset.CYCLE_MINUTES; minute++) {
            assertNotEquals("no change at minute boundary " + minute,
                    BurnInOffset.forMinuteIndex(minute),
                    BurnInOffset.forMinuteIndex(minute + 1));
        }
    }

    @Test
    public void sameMinuteAlwaysYieldsSameOffset() {
        for (long minute : new long[]{0L, 1L, 16L, 17L, 288L, 289L, 29_731_569L}) {
            assertEquals(BurnInOffset.forMinuteIndex(minute),
                    BurnInOffset.forMinuteIndex(minute));
        }
        // A repeated computation from the same instant is identical too.
        assertEquals(BurnInOffset.at(REFERENCE), BurnInOffset.at(REFERENCE));
    }

    @Test
    public void centerAndDeclaredExtremeFixturesAreRepresentable() {
        // The contract package declares a centered frame plus (-8,-8) and
        // (+8,+8) corner frames. The scan must occupy each exactly once per
        // cycle, and every other representable position as well.
        Set<String> seen = new HashSet<>();
        for (long minute = 0; minute < BurnInOffset.CYCLE_MINUTES; minute++) {
            BurnInOffset o = BurnInOffset.forMinuteIndex(minute);
            assertTrue("position revisited within a cycle: " + o,
                    seen.add(o.x + "," + o.y));
        }
        assertEquals(BurnInOffset.CYCLE_MINUTES, seen.size());
        assertTrue("center frame unrepresented", seen.contains("0,0"));
        assertTrue("(-8,-8) extreme unrepresented", seen.contains("-8,-8"));
        assertTrue("(+8,+8) extreme unrepresented", seen.contains("8,8"));
    }

    @Test
    public void scheduleFollowsLocalWallClockNotUtcInstant() {
        // The same instant seen from two zones is a different wall-clock
        // minute, so the schedule (which protects what the viewer sees)
        // may differ; the same wall-clock reading in two zones must agree.
        ZonedDateTime jerusalem = REFERENCE;
        ZonedDateTime sameWallClockUtc = jerusalem.toLocalDateTime().atZone(ZoneId.of("UTC"));
        assertEquals(BurnInOffset.at(jerusalem), BurnInOffset.at(sameWallClockUtc));
    }

    @Test
    public void representativeTimesProduceBoundedOffsets() {
        ZoneId zone = ZoneId.of("Asia/Jerusalem");
        ZonedDateTime[] representatives = {
                REFERENCE,
                ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone),   // midnight, new year
                ZonedDateTime.of(2026, 7, 12, 12, 0, 0, 0, zone), // noon
                ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, zone),
                ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")),
                ZonedDateTime.of(1969, 12, 31, 23, 59, 0, 0, ZoneId.of("UTC")), // pre-epoch
        };
        for (ZonedDateTime t : representatives) {
            BurnInOffset o = BurnInOffset.at(t);
            assertTrue("out of bounds at " + t + ": " + o,
                    Math.abs(o.x) <= BurnInOffset.BOUND_PX
                            && Math.abs(o.y) <= BurnInOffset.BOUND_PX);
        }
    }

    @Test
    public void cadenceAndBoundsConstantsMatchTheVisualContract() {
        // geometry.json: translation_bounds_px x=[-8,8] y=[-8,8];
        // motion.json: cadence_seconds=60.
        assertEquals(8, BurnInOffset.BOUND_PX);
        assertEquals(60, BurnInOffset.CADENCE_SECONDS);
        assertEquals(17 * 17, BurnInOffset.CYCLE_MINUTES);
    }

    @Test
    public void configuredBoundsKeepChangingEveryMinuteAcrossTheirCycle() {
        for (int bound = 1; bound <= BurnInOffset.BOUND_PX; bound++) {
            int span = 2 * bound + 1;
            int cycle = span * span;
            Set<String> seen = new HashSet<>();
            for (long minute = 0; minute < cycle; minute++) {
                BurnInOffset current = BurnInOffset.forMinuteIndex(minute, bound);
                BurnInOffset next = BurnInOffset.forMinuteIndex(minute + 1, bound);
                assertTrue(Math.abs(current.x) <= bound && Math.abs(current.y) <= bound);
                assertTrue("position revisited for bound " + bound,
                        seen.add(current.x + "," + current.y));
                assertNotEquals("no change for bound " + bound + " at " + minute,
                        current, next);
            }
            assertEquals(cycle, seen.size());
        }
    }

    @Test
    public void configuredBoundMustStayInsideAcceptedEnvelope() {
        assertThrows(IllegalArgumentException.class,
                () -> BurnInOffset.forMinuteIndex(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> BurnInOffset.forMinuteIndex(0, BurnInOffset.BOUND_PX + 1));
    }
}
