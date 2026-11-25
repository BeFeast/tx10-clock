import XCTest
@testable import TX10ClockCore

/// Deterministic parser/default/error/range checks for the renderer-agnostic
/// ``ExternalConfig``. Pure Foundation — no UIKit, no device — so strict
/// ingestion is verifiable with `swift test` on any platform.
final class ExternalConfigTests: XCTestCase {

    private func reasonOf(_ json: String,
                          file: StaticString = #filePath,
                          line: UInt = #line) -> String {
        do {
            _ = try ExternalConfig.parse(json)
            XCTFail("expected rejection for: \(json)", file: file, line: line)
            return "ACCEPTED"
        } catch let error as ConfigError {
            return error.reason
        } catch {
            XCTFail("expected ConfigError but got \(error)", file: file, line: line)
            return "WRONG_ERROR_TYPE"
        }
    }

    // MARK: defaults & happy path

    func testDefaultsAreBootStartTrueTwelveHourAndSystemZone() {
        let c = ExternalConfig.defaults()
        XCTAssertTrue(c.bootStart)
        XCTAssertFalse(c.use24Hour)
        XCTAssertTrue(c.showSeconds)
        XCTAssertNil(c.timeZone)
    }

    func testDefaultsKeepAcceptedRendererSelections() {
        let c = ExternalConfig.defaults()
        XCTAssertEqual("white", c.digitalColor)
        XCTAssertEqual("grey", c.dateColor)
        XCTAssertEqual("silver", c.tickColor)
        XCTAssertEqual("orange", c.accentColor)
        XCTAssertTrue(c.showDate)
        XCTAssertEqual(100, c.digitalSizePercent)
        XCTAssertEqual(100, c.secondarySizePercent)
        XCTAssertTrue(c.burnInEnabled)
        XCTAssertEqual(ExternalConfig.maxBurnInShiftPx, c.burnInMaxShiftPx)
        XCTAssertEqual(8, ExternalConfig.maxBurnInShiftPx)
    }

    func testDefaultTickSoundIsMinute() {
        XCTAssertEqual(TickSound.minute, ExternalConfig.defaults().tickSound)
    }

    func testEmptyObjectYieldsDefaults() throws {
        XCTAssertEqual(ExternalConfig.defaults(), try ExternalConfig.parse("{}"))
    }

    func testFullValidDocumentParses() throws {
        let c = try ExternalConfig.parse(
            "{\"schemaVersion\":1,\"bootStart\":false,\"use24Hour\":false,"
                + "\"showSeconds\":false,\"timeZone\":\"America/New_York\"}")
        XCTAssertFalse(c.bootStart)
        XCTAssertFalse(c.use24Hour)
        XCTAssertFalse(c.showSeconds)
        XCTAssertEqual("America/New_York", c.timeZone)
        XCTAssertEqual(TimeZone(identifier: "America/New_York"),
                       c.resolveZone(fallback: TimeZone(identifier: "UTC")!))
    }

    func testPartialDocumentMergesWithDefaults() throws {
        let c = try ExternalConfig.parse("{\"bootStart\":false}")
        XCTAssertFalse(c.bootStart)
        XCTAssertFalse(c.use24Hour)  // 12-hour default retained
        XCTAssertTrue(c.showSeconds) // default retained
        XCTAssertNil(c.timeZone)
        XCTAssertEqual(TickSound.minute, c.tickSound) // default retained
    }

    func testFullRendererSelectionDocumentParses() throws {
        let c = try ExternalConfig.parse(
            "{\"digitalColor\":\"silver\",\"dateColor\":\"white\","
                + "\"tickColor\":\"grey\",\"accentColor\":\"white\","
                + "\"showDate\":false,\"digitalSizePercent\":75,"
                + "\"secondarySizePercent\":50,"
                + "\"burnInEnabled\":false,\"burnInMaxShiftPx\":4}")
        XCTAssertEqual("silver", c.digitalColor)
        XCTAssertEqual("white", c.dateColor)
        XCTAssertEqual("grey", c.tickColor)
        XCTAssertEqual("white", c.accentColor)
        XCTAssertFalse(c.showDate)
        XCTAssertEqual(75, c.digitalSizePercent)
        XCTAssertEqual(50, c.secondarySizePercent)
        XCTAssertFalse(c.burnInEnabled)
        XCTAssertEqual(4, c.burnInMaxShiftPx)
    }

    func testTickSoundAcceptsApprovedValues() throws {
        XCTAssertEqual(TickSound.off,
                       try ExternalConfig.parse("{\"tickSound\":\"off\"}").tickSound)
        XCTAssertEqual(TickSound.minute,
                       try ExternalConfig.parse("{\"tickSound\":\"minute\"}").tickSound)
        XCTAssertEqual(TickSound.second,
                       try ExternalConfig.parse("{\"tickSound\":\"second\"}").tickSound)
    }

    func testSizeAndBurnInBoundsAreInclusive() throws {
        let low = try ExternalConfig.parse(
            "{\"digitalSizePercent\":50,\"secondarySizePercent\":50,\"burnInMaxShiftPx\":0}")
        XCTAssertEqual(50, low.digitalSizePercent)
        XCTAssertEqual(50, low.secondarySizePercent)
        XCTAssertEqual(0, low.burnInMaxShiftPx)

        let high = try ExternalConfig.parse(
            "{\"digitalSizePercent\":100,\"secondarySizePercent\":100,\"burnInMaxShiftPx\":8}")
        XCTAssertEqual(100, high.digitalSizePercent)
        XCTAssertEqual(100, high.secondarySizePercent)
        XCTAssertEqual(8, high.burnInMaxShiftPx)
    }

    func testResolveZoneFallsBackWhenUnset() throws {
        let c = try ExternalConfig.parse("{}")
        XCTAssertEqual(TimeZone(identifier: "UTC")!,
                       c.resolveZone(fallback: TimeZone(identifier: "UTC")!))
    }

    func testWhitespaceAroundDocumentIsAccepted() throws {
        XCTAssertEqual(ExternalConfig.defaults(),
                       try ExternalConfig.parse("  \n\t {}\n "))
    }

    // MARK: structural rejections

    func testMalformedJsonRejected() {
        XCTAssertEqual(ConfigError.malformed, reasonOf("{"))
        XCTAssertEqual(ConfigError.malformed, reasonOf("{\"bootStart\":}"))
        XCTAssertEqual(ConfigError.malformed, reasonOf("not json"))
    }

    func testTrailingContentRejected() {
        XCTAssertEqual(ConfigError.malformed, reasonOf("{} garbage"))
        XCTAssertEqual(ConfigError.malformed, reasonOf("{}{}"))
    }

    func testNonObjectTopLevelRejected() {
        XCTAssertEqual(ConfigError.notObject, reasonOf("[]"))
        XCTAssertEqual(ConfigError.notObject, reasonOf("42"))
        XCTAssertEqual(ConfigError.notObject, reasonOf("\"hi\""))
        XCTAssertEqual(ConfigError.notObject, reasonOf("true"))
    }

    func testDuplicateKeyRejected() {
        XCTAssertEqual(ConfigError.duplicateKey,
                       reasonOf("{\"bootStart\":true,\"bootStart\":false}"))
    }

    func testUnknownKeyRejected() {
        XCTAssertEqual(ConfigError.unknownKey, reasonOf("{\"colorHex\":\"#fff\"}"))
        XCTAssertEqual(ConfigError.unknownKey, reasonOf("{\"bootStart\":true,\"extra\":1}"))
    }

    func testDeeplyNestedDocumentRejected() {
        let nested = String(repeating: "[", count: Json.maxDepth + 5)
        XCTAssertEqual(ConfigError.malformed, reasonOf(nested))
    }

    // MARK: type & range rejections

    func testWrongTypeRejected() {
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"bootStart\":\"true\"}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"showSeconds\":1}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"timeZone\":42}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"schemaVersion\":\"1\"}"))
        // A fractional number is not an acceptable integer schemaVersion.
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"schemaVersion\":1.5}"))
    }

    func testWrongTypeRendererSelectionsRejected() {
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"digitalColor\":7}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"accentColor\":true}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"showDate\":\"yes\"}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"digitalSizePercent\":\"90\"}"))
        // A fractional size or shift is not an acceptable integer.
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"digitalSizePercent\":75.5}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"burnInMaxShiftPx\":4.2}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"burnInEnabled\":0}"))
    }

    func testWrongTypeTickSoundRejected() {
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"tickSound\":1}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"tickSound\":true}"))
        XCTAssertEqual(ConfigError.wrongType, reasonOf("{\"tickSound\":null}"))
    }

    func testUnapprovedColorNameRejected() {
        // Only names in the approved palette are accepted — never raw values.
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"digitalColor\":\"red\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"dateColor\":\"#FF0000\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"tickColor\":\"0xFF00FF00\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"accentColor\":\"ORANGE\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"accentColor\":\"black\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"digitalColor\":\"\"}"))
    }

    func testUnknownTickSoundValueRejected() {
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"tickSound\":\"loud\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"tickSound\":\"OFF\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"tickSound\":\"hour\"}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"tickSound\":\"\"}"))
    }

    func testOutOfRangeSizesRejected() {
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"digitalSizePercent\":49}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"digitalSizePercent\":101}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"secondarySizePercent\":0}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"secondarySizePercent\":200}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"digitalSizePercent\":-75}"))
    }

    func testOutOfRangeBurnInShiftRejected() {
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"burnInMaxShiftPx\":9}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"burnInMaxShiftPx\":-1}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"burnInMaxShiftPx\":800}"))
    }

    func testDuplicateRendererKeyRejected() {
        XCTAssertEqual(ConfigError.duplicateKey,
                       reasonOf("{\"accentColor\":\"orange\",\"accentColor\":\"white\"}"))
    }

    func testDuplicateTickSoundKeyRejected() {
        XCTAssertEqual(ConfigError.duplicateKey,
                       reasonOf("{\"tickSound\":\"off\",\"tickSound\":\"second\"}"))
    }

    func testOutOfRangeSchemaVersionRejected() {
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"schemaVersion\":2}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"schemaVersion\":0}"))
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"schemaVersion\":-1}"))
    }

    func testInvalidTimeZoneRejected() {
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"timeZone\":\"Mars/Olympus\"}"))
    }

    func testOversizedTimeZoneRejected() {
        let zone = String(repeating: "A", count: 100)
        XCTAssertEqual(ConfigError.outOfRange, reasonOf("{\"timeZone\":\"\(zone)\"}"))
    }

    // MARK: oversized document

    func testOversizedDocumentRejected() {
        let big = Data(repeating: UInt8(ascii: " "), count: ExternalConfig.maxBytes + 1)
        do {
            _ = try ExternalConfig.parse(big)
            XCTFail("expected rejection")
        } catch let error as ConfigError {
            XCTAssertEqual(ConfigError.oversized, error.reason)
        } catch {
            XCTFail("expected ConfigError but got \(error)")
        }
    }

    func testByteAndStringParseAgree() throws {
        let json = "{\"use24Hour\":false}"
        XCTAssertEqual(try ExternalConfig.parse(json),
                       try ExternalConfig.parse(Data(json.utf8)))
    }

    // MARK: value semantics

    func testEqualsAndHashByValue() throws {
        let a = try ExternalConfig.parse("{\"timeZone\":\"UTC\"}")
        let b = try ExternalConfig.parse("{\"timeZone\":\"UTC\"}")
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.hashValue, b.hashValue)
        XCTAssertNotEqual(a, ExternalConfig.defaults())
    }
}
