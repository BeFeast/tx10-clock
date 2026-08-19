import XCTest
@testable import TX10ClockCore

/// "config" static checks: the default theme is stable and copies round trip.
/// Guards against accidental changes to colours that would silently shift the
/// golden image.
final class ClockConfigTests: XCTestCase {

    func testDefaultConfigMatchesAcceptedContract() {
        let c = ClockConfig.defaultConfig()
        XCTAssertEqual(0xFF000000, c.backgroundColor)
        XCTAssertEqual(0xFF000000, c.faceColor)
        XCTAssertEqual(0xFFD1D1D6, c.tickColor)
        XCTAssertEqual(0xFFFF9F0A, c.secondHandColor)
        XCTAssertEqual(0xFFF5F5F7, c.digitalColor)
        XCTAssertEqual(0xFFA1A1A6, c.dateColor)
        XCTAssertFalse(c.use24Hour)
        XCTAssertTrue(c.showSeconds)
        XCTAssertTrue(c.showDate)
        XCTAssertEqual(100, c.digitalSizePercent)
        XCTAssertEqual(100, c.secondarySizePercent)
        XCTAssertTrue(c.burnInEnabled)
        XCTAssertEqual(8, c.burnInMaxShiftPx)
        // Every colour must be fully opaque so frames have no alpha bleed.
        for argb in [c.backgroundColor, c.faceColor, c.tickColor, c.hourHandColor,
                     c.minuteHandColor, c.secondHandColor, c.digitalColor, c.dateColor] {
            XCTAssertEqual(0xFF, (argb >> 24) & 0xFF, "alpha must be 0xFF")
        }
    }

    func testInitializerOverridesApply() {
        let c = ClockConfig(backgroundColor: 0xFF000000,
                            use24Hour: true,
                            showSeconds: false)
        XCTAssertTrue(c.use24Hour)
        XCTAssertFalse(c.showSeconds)
        XCTAssertEqual(0xFF000000, c.backgroundColor)
    }

    func testCopyRoundTrips() {
        let original = ClockConfig(digitalColor: 0xFF123456,
                                   use24Hour: false,
                                   showDate: false,
                                   digitalSizePercent: 75,
                                   burnInEnabled: false,
                                   burnInMaxShiftPx: 4)
        let copy = original
        XCTAssertEqual(original, copy)
        XCTAssertEqual(original.digitalColor, copy.digitalColor)
        XCTAssertEqual(original.use24Hour, copy.use24Hour)
        XCTAssertEqual(original.backgroundColor, copy.backgroundColor)
        XCTAssertEqual(original.showDate, copy.showDate)
        XCTAssertEqual(original.digitalSizePercent, copy.digitalSizePercent)
        XCTAssertEqual(original.secondarySizePercent, copy.secondarySizePercent)
        XCTAssertEqual(original.burnInEnabled, copy.burnInEnabled)
        XCTAssertEqual(original.burnInMaxShiftPx, copy.burnInMaxShiftPx)
    }

    // MARK: fromExternal mapping

    func testFromExternalDefaultsEqualAcceptedTheme() {
        let mapped = ClockConfig.fromExternal(ExternalConfig.defaults())
        let theme = ClockConfig.defaultConfig()
        XCTAssertEqual(theme.backgroundColor, mapped.backgroundColor)
        XCTAssertEqual(theme.faceColor, mapped.faceColor)
        XCTAssertEqual(theme.tickColor, mapped.tickColor)
        XCTAssertEqual(theme.hourHandColor, mapped.hourHandColor)
        XCTAssertEqual(theme.minuteHandColor, mapped.minuteHandColor)
        XCTAssertEqual(theme.secondHandColor, mapped.secondHandColor)
        XCTAssertEqual(theme.digitalColor, mapped.digitalColor)
        XCTAssertEqual(theme.dateColor, mapped.dateColor)
        XCTAssertEqual(theme.use24Hour, mapped.use24Hour)
        XCTAssertEqual(theme.showSeconds, mapped.showSeconds)
        XCTAssertEqual(theme.showDate, mapped.showDate)
        XCTAssertEqual(theme.digitalSizePercent, mapped.digitalSizePercent)
        XCTAssertEqual(theme.secondarySizePercent, mapped.secondarySizePercent)
        XCTAssertEqual(theme.burnInEnabled, mapped.burnInEnabled)
        XCTAssertEqual(theme.burnInMaxShiftPx, mapped.burnInMaxShiftPx)
    }

    func testFromExternalResolvesApprovedNamesToContractValues() throws {
        let external = try ExternalConfig.parse(
            "{\"digitalColor\":\"silver\",\"dateColor\":\"white\","
                + "\"tickColor\":\"grey\",\"accentColor\":\"white\"}")
        let mapped = ClockConfig.fromExternal(external)
        XCTAssertEqual(0xFFD1D1D6, mapped.digitalColor)   // silver
        XCTAssertEqual(0xFFD1D1D6, mapped.hourHandColor)  // hands follow digital role
        XCTAssertEqual(0xFFD1D1D6, mapped.minuteHandColor)
        XCTAssertEqual(0xFFF5F5F7, mapped.dateColor)      // white
        XCTAssertEqual(0xFFA1A1A6, mapped.tickColor)      // grey
        XCTAssertEqual(0xFFF5F5F7, mapped.secondHandColor) // white accent
        // Background stays pure black regardless of any selection.
        XCTAssertEqual(0xFF000000, mapped.backgroundColor)
        XCTAssertEqual(0xFF000000, mapped.faceColor)
    }

    func testFromExternalThreadsBoundedFieldsThrough() throws {
        let external = try ExternalConfig.parse(
            "{\"use24Hour\":true,\"showSeconds\":false,\"showDate\":false,"
                + "\"digitalSizePercent\":60,\"secondarySizePercent\":80,"
                + "\"burnInEnabled\":false,\"burnInMaxShiftPx\":2}")
        let mapped = ClockConfig.fromExternal(external)
        XCTAssertTrue(mapped.use24Hour)
        XCTAssertFalse(mapped.showSeconds)
        XCTAssertFalse(mapped.showDate)
        XCTAssertEqual(60, mapped.digitalSizePercent)
        XCTAssertEqual(80, mapped.secondarySizePercent)
        XCTAssertFalse(mapped.burnInEnabled)
        XCTAssertEqual(2, mapped.burnInMaxShiftPx)
    }
}
