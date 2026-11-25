import XCTest
import TX10ClockCore

/// Deterministic time and calendar text fixtures.
final class ClockFormatTests: XCTestCase {

    private static let zone = TimeZone(identifier: "Asia/Jerusalem")!

    private static let reference = ZonedInstant(
        year: 2026, month: 7, dayOfMonth: 12,
        hour: 22, minute: 9, second: 42, timeZone: zone)!

    func testReferenceTwelveHourFrameIsExact() {
        XCTAssertEqual("10:09", ClockFormat.main(Self.reference, use24Hour: false))
        XCTAssertEqual("PM SUN, JUL 12 42",
                       ClockFormat.secondary(Self.reference, use24Hour: false, showSeconds: true))
    }

    func testReferenceTwentyFourHourFrameIsExact() {
        XCTAssertEqual("22:09", ClockFormat.main(Self.reference, use24Hour: true))
        XCTAssertEqual("SUN, JUL 12 42",
                       ClockFormat.secondary(Self.reference, use24Hour: true, showSeconds: true))
    }

    func testHidingDateDropsOnlyTheCompactDate() {
        let now = Self.reference
        // 12-hour keeps the AM/PM marker; only the date is removed.
        XCTAssertEqual("PM ", ClockFormat.secondaryPrefix(now, use24Hour: false, showDate: false))
        XCTAssertEqual("PM 42",
                       ClockFormat.secondary(now, use24Hour: false, showSeconds: true, showDate: false))
        XCTAssertEqual("PM",
                       ClockFormat.secondary(now, use24Hour: false, showSeconds: false, showDate: false))
        // 24-hour has no AM/PM, so hiding the date leaves only the seconds.
        XCTAssertEqual("", ClockFormat.secondaryPrefix(now, use24Hour: true, showDate: false))
        XCTAssertEqual("42",
                       ClockFormat.secondary(now, use24Hour: true, showSeconds: true, showDate: false))
        XCTAssertEqual("",
                       ClockFormat.secondary(now, use24Hour: true, showSeconds: false, showDate: false))
        // Showing the date is unchanged from the accepted contract fixtures.
        XCTAssertEqual("PM SUN, JUL 12 ",
                       ClockFormat.secondaryPrefix(now, use24Hour: false, showDate: true))
        XCTAssertEqual("PM SUN, JUL 12 42",
                       ClockFormat.secondary(now, use24Hour: false, showSeconds: true, showDate: true))
    }

    func testMidnightNoonAndSecondsAreStable() {
        let midnight = ZonedInstant(
            year: 2026, month: 7, dayOfMonth: 12,
            hour: 0, minute: 0, second: 5, timeZone: Self.zone)!
        let noon = ZonedInstant(
            year: 2026, month: 7, dayOfMonth: 12,
            hour: 12, minute: 0, second: 0, timeZone: Self.zone)!
        XCTAssertEqual("12:00", ClockFormat.main(midnight, use24Hour: false))
        XCTAssertEqual("AM", ClockFormat.amPm(midnight))
        XCTAssertEqual("05", ClockFormat.seconds(midnight))
        XCTAssertEqual("12:00", ClockFormat.main(noon, use24Hour: false))
        XCTAssertEqual("PM", ClockFormat.amPm(noon))
    }
}
