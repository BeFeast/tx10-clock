import XCTest
import TX10ClockCore

/// Contract tests for the deterministic burn-in offset engine: bounds, minute
/// cadence, determinism, full coverage, and the visual contract's
/// representative times and fixture frames.
final class BurnInOffsetTests: XCTestCase {

    private static let zone = TimeZone(identifier: "Asia/Jerusalem")!

    /// The visual contract v0.1.0 reference instant (content-fixtures.json).
    private static let reference = ZonedInstant(
        year: 2026, month: 7, dayOfMonth: 12,
        hour: 22, minute: 9, second: 42, timeZone: zone)!

    private func shifted(_ base: ZonedInstant, bySeconds seconds: TimeInterval) -> ZonedInstant {
        ZonedInstant(date: base.date.addingTimeInterval(seconds), timeZone: base.timeZone)
    }

    func testEveryCyclePositionIsWithinContractBounds() {
        for minute in 0..<Int64(BurnInOffset.cycleMinutes) {
            let o = BurnInOffset.forMinuteIndex(minute)
            XCTAssertTrue(o.x >= -BurnInOffset.boundPx && o.x <= BurnInOffset.boundPx,
                          "x out of bounds at minute \(minute): \(o)")
            XCTAssertTrue(o.y >= -BurnInOffset.boundPx && o.y <= BurnInOffset.boundPx,
                          "y out of bounds at minute \(minute): \(o)")
        }
    }

    func testBoundsHoldForArbitraryIncludingNegativeMinuteIndexes() {
        let samples: [Int64] = [.min, -1_000_000, -1, 0, 1,
                                29_731_569 /* the reference instant's minute */, .max]
        for minute in samples {
            let o = BurnInOffset.forMinuteIndex(minute)
            XCTAssertTrue(abs(o.x) <= BurnInOffset.boundPx && abs(o.y) <= BurnInOffset.boundPx,
                          "out of bounds at minute \(minute): \(o)")
        }
    }

    func testOffsetIsStableForEveryInstantWithinAMinute() {
        let top = ZonedInstant(
            year: 2026, month: 7, dayOfMonth: 12,
            hour: 22, minute: 9, second: 0, timeZone: Self.zone)!
        let atTop = BurnInOffset.at(top)
        XCTAssertEqual(atTop, BurnInOffset.at(shifted(top, bySeconds: 1)))
        XCTAssertEqual(atTop, BurnInOffset.at(shifted(top, bySeconds: 30)))
        XCTAssertEqual(atTop, BurnInOffset.at(shifted(top, bySeconds: 59)))
        // Java probes 59.999999999 s; Date's Double resolution (~0.5 µs at
        // this epoch) would round that up onto the boundary, so probe the last
        // representable instant before the next minute instead.
        let lastInstant = ZonedInstant(
            date: Date(timeIntervalSince1970: (top.date.timeIntervalSince1970 + 60).nextDown),
            timeZone: top.timeZone)
        XCTAssertEqual(atTop, BurnInOffset.at(lastInstant))
    }

    func testOffsetChangesExactlyAtTheMinuteBoundary() {
        let top = ZonedInstant(
            year: 2026, month: 7, dayOfMonth: 12,
            hour: 22, minute: 9, second: 0, timeZone: Self.zone)!
        let lastInstant = ZonedInstant(
            date: Date(timeIntervalSince1970: (top.date.timeIntervalSince1970 + 60).nextDown),
            timeZone: top.timeZone)
        let nextMinute = shifted(top, bySeconds: 60)
        XCTAssertEqual(BurnInOffset.at(top), BurnInOffset.at(lastInstant))
        XCTAssertNotEqual(BurnInOffset.at(top), BurnInOffset.at(nextMinute))
    }

    func testConsecutiveMinutesAlwaysDifferAcrossFullCycleAndWrap() {
        // Cover a whole cycle plus the wrap back to the first cell.
        for minute in 0...Int64(BurnInOffset.cycleMinutes) {
            XCTAssertNotEqual(BurnInOffset.forMinuteIndex(minute),
                              BurnInOffset.forMinuteIndex(minute + 1),
                              "no change at minute boundary \(minute)")
        }
    }

    func testSameMinuteAlwaysYieldsSameOffset() {
        for minute in [Int64(0), 1, 16, 17, 288, 289, 29_731_569] {
            XCTAssertEqual(BurnInOffset.forMinuteIndex(minute),
                           BurnInOffset.forMinuteIndex(minute))
        }
        // A repeated computation from the same instant is identical too.
        XCTAssertEqual(BurnInOffset.at(Self.reference), BurnInOffset.at(Self.reference))
    }

    func testCenterAndDeclaredExtremeFixturesAreRepresentable() {
        // The contract package declares a centered frame plus (-8,-8) and
        // (+8,+8) corner frames. The scan must occupy each exactly once per
        // cycle, and every other representable position as well.
        var seen = Set<String>()
        for minute in 0..<Int64(BurnInOffset.cycleMinutes) {
            let o = BurnInOffset.forMinuteIndex(minute)
            XCTAssertTrue(seen.insert("\(o.x),\(o.y)").inserted,
                          "position revisited within a cycle: \(o)")
        }
        XCTAssertEqual(BurnInOffset.cycleMinutes, seen.count)
        XCTAssertTrue(seen.contains("0,0"), "center frame unrepresented")
        XCTAssertTrue(seen.contains("-8,-8"), "(-8,-8) extreme unrepresented")
        XCTAssertTrue(seen.contains("8,8"), "(+8,+8) extreme unrepresented")
    }

    func testScheduleFollowsLocalWallClockNotUtcInstant() {
        // The same instant seen from two zones is a different wall-clock
        // minute, so the schedule (which protects what the viewer sees)
        // may differ; the same wall-clock reading in two zones must agree.
        let jerusalem = Self.reference
        let sameWallClockUtc = ZonedInstant(
            year: 2026, month: 7, dayOfMonth: 12,
            hour: 22, minute: 9, second: 42,
            timeZone: TimeZone(identifier: "UTC")!)!
        XCTAssertEqual(BurnInOffset.at(jerusalem), BurnInOffset.at(sameWallClockUtc))
    }

    func testRepresentativeTimesProduceBoundedOffsets() {
        let zone = Self.zone
        let utc = TimeZone(identifier: "UTC")!
        let representatives: [ZonedInstant] = [
            Self.reference,
            ZonedInstant(year: 2026, month: 1, dayOfMonth: 1,
                         hour: 0, minute: 0, second: 0, timeZone: zone)!,   // midnight, new year
            ZonedInstant(year: 2026, month: 7, dayOfMonth: 12,
                         hour: 12, minute: 0, second: 0, timeZone: zone)!,  // noon
            ZonedInstant(year: 2026, month: 12, dayOfMonth: 31,
                         hour: 23, minute: 59, second: 59, timeZone: zone)!,
            ZonedInstant(year: 1970, month: 1, dayOfMonth: 1,
                         hour: 0, minute: 0, second: 0, timeZone: utc)!,
            ZonedInstant(year: 1969, month: 12, dayOfMonth: 31,
                         hour: 23, minute: 59, second: 0, timeZone: utc)!,  // pre-epoch
        ]
        for t in representatives {
            let o = BurnInOffset.at(t)
            XCTAssertTrue(abs(o.x) <= BurnInOffset.boundPx && abs(o.y) <= BurnInOffset.boundPx,
                          "out of bounds at \(t.date): \(o)")
        }
    }

    func testCadenceAndBoundsConstantsMatchTheVisualContract() {
        // geometry.json: translation_bounds_px x=[-8,8] y=[-8,8];
        // motion.json: cadence_seconds=60.
        XCTAssertEqual(8, BurnInOffset.boundPx)
        XCTAssertEqual(60, BurnInOffset.cadenceSeconds)
        XCTAssertEqual(17 * 17, BurnInOffset.cycleMinutes)
    }

    func testConfiguredBoundsKeepChangingEveryMinuteAcrossTheirCycle() {
        for bound in 1...BurnInOffset.boundPx {
            let span = 2 * bound + 1
            let cycle = span * span
            var seen = Set<String>()
            for minute in 0..<Int64(cycle) {
                let current = BurnInOffset.forMinuteIndex(minute, boundPx: bound)
                let next = BurnInOffset.forMinuteIndex(minute + 1, boundPx: bound)
                XCTAssertTrue(abs(current.x) <= bound && abs(current.y) <= bound)
                XCTAssertTrue(seen.insert("\(current.x),\(current.y)").inserted,
                              "position revisited for bound \(bound)")
                XCTAssertNotEqual(current, next,
                                  "no change for bound \(bound) at \(minute)")
            }
            XCTAssertEqual(cycle, seen.count)
        }
    }

    // Java's configuredBoundMustStayInsideAcceptedEnvelope asserts
    // IllegalArgumentException for boundPx outside [0, 8]; the Swift port
    // enforces the same envelope with a precondition, which traps and cannot
    // be asserted from XCTest, so that case is intentionally not ported.

    func testMinuteIndexMatchesJavaReferenceFixtures() {
        // Pins ZonedInstant.localEpochSeconds() to the Java wall-clock epoch,
        // including floored (not truncated) pre-epoch seconds.
        XCTAssertEqual(29_731_569, BurnInOffset.minuteIndexOf(Self.reference))
        let preEpoch = ZonedInstant(
            year: 1969, month: 12, dayOfMonth: 31,
            hour: 23, minute: 59, second: 0,
            timeZone: TimeZone(identifier: "UTC")!)!
        XCTAssertEqual(-1, BurnInOffset.minuteIndexOf(preEpoch))
    }

    func testExtremeYearGoldenFixturesSelectTheDeclaredCorners() throws {
        // The Gregorian calendar repeats every 400 years. These years
        // (ClockRendererVariantGoldenTest's BURN_IN_MINUS/PLUS_MINUTE)
        // preserve Sunday, July 12 22:09:42 while their local-minute indices
        // select the (-8,-8) and (+8,+8) corners of the 17x17 scan.
        let minus = try XCTUnwrap(ZonedInstant(
            year: 98826, month: 7, dayOfMonth: 12,
            hour: 22, minute: 9, second: 42, timeZone: Self.zone))
        let plus = try XCTUnwrap(ZonedInstant(
            year: 109626, month: 7, dayOfMonth: 12,
            hour: 22, minute: 9, second: 42, timeZone: Self.zone))
        XCTAssertEqual(7, minus.dayOfWeekMon1)
        XCTAssertEqual(7, plus.dayOfWeekMon1)
        XCTAssertEqual(50_941_614_129, BurnInOffset.minuteIndexOf(minus))
        XCTAssertEqual(56_621_865_489, BurnInOffset.minuteIndexOf(plus))
        let minusOffset = BurnInOffset.at(minus)
        XCTAssertEqual(-8, minusOffset.x)
        XCTAssertEqual(-8, minusOffset.y)
        let plusOffset = BurnInOffset.at(plus)
        XCTAssertEqual(8, plusOffset.x)
        XCTAssertEqual(8, plusOffset.y)
    }
}
