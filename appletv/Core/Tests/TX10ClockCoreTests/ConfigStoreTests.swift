import XCTest
@testable import TX10ClockCore

/// Deterministic last-known-good / reload / atomic-replacement / status checks
/// for ``ConfigStore``. Pure Foundation: the store is URL-based, so a real
/// temporary directory stands in for the tvOS Library/Caches container.
final class ConfigStoreTests: XCTestCase {

    private static let fixedNow = Date(timeIntervalSince1970: 1_700_000_000)

    private var tmp: URL!

    override func setUpWithError() throws {
        tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("tx10-core-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tmp {
            try? FileManager.default.removeItem(at: tmp)
        }
    }

    private func store(_ dir: URL) -> ConfigStore {
        ConfigStore(dir: dir, maxBytes: Int64(ExternalConfig.maxBytes),
                    now: { Self.fixedNow })
    }

    private func write(_ dir: URL, _ name: String, _ content: String) throws {
        try ConfigStore.atomicWrite(to: dir.appendingPathComponent(name),
                                    content: Data(content.utf8))
    }

    private func directoryNames(_ dir: URL) throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: dir.path)
    }

    // MARK: reload / last-known-good

    func testReloadWithNoFileYieldsDefaults() {
        let s = store(tmp)
        let c = s.reload()
        XCTAssertEqual(ExternalConfig.defaults(), c)
        XCTAssertEqual(ConfigStore.Source.default, s.source)
        XCTAssertNil(s.lastRejectReason)
    }

    func testReloadAcceptsValidConfig() throws {
        try write(tmp, ConfigStore.configFileName, "{\"bootStart\":false,\"timeZone\":\"UTC\"}")
        let s = store(tmp)
        let c = s.reload()
        XCTAssertFalse(c.bootStart)
        XCTAssertEqual("UTC", c.timeZone)
        XCTAssertEqual(ConfigStore.Source.external, s.source)
        XCTAssertNil(s.lastRejectReason)
    }

    func testRejectedReloadRetainsLastKnownGood() throws {
        try write(tmp, ConfigStore.configFileName, "{\"bootStart\":false}")
        let s = store(tmp)
        XCTAssertFalse(s.reload().bootStart)

        // Replace with an invalid document (unknown key). The good copy stays.
        try write(tmp, ConfigStore.configFileName, "{\"faceColor\":\"#fff\"}")
        let after = s.reload()
        XCTAssertFalse(after.bootStart, "last-known-good must survive a rejected reload")
        XCTAssertEqual(ConfigStore.Source.external, s.source)
        XCTAssertEqual(ConfigError.unknownKey, s.lastRejectReason)
    }

    func testDeletedFileRetainsLastKnownGood() throws {
        try write(tmp, ConfigStore.configFileName, "{\"showSeconds\":false}")
        let s = store(tmp)
        XCTAssertFalse(s.reload().showSeconds)

        try FileManager.default.removeItem(
            at: tmp.appendingPathComponent(ConfigStore.configFileName))
        XCTAssertFalse(s.reload().showSeconds, "absence must not clobber last-known-good")
    }

    func testOversizedFileRejected() throws {
        var big = "{\"timeZone\":\"UTC\"}"
        big += String(repeating: " ", count: ExternalConfig.maxBytes + 1 - big.count)
        try Data(big.utf8).write(to: tmp.appendingPathComponent(ConfigStore.configFileName))
        let s = store(tmp)
        XCTAssertEqual(ExternalConfig.defaults(), s.reload())
        XCTAssertEqual(ConfigError.oversized, s.lastRejectReason)
    }

    // MARK: representative same-directory atomic replacement

    func testSameDirectoryAtomicReplacementIsPickedUpOnReload() throws {
        let s = store(tmp)

        try write(tmp, ConfigStore.configFileName, "{\"timeZone\":\"UTC\",\"use24Hour\":true}")
        let first = s.reload()
        XCTAssertEqual("UTC", first.timeZone)
        XCTAssertTrue(first.use24Hour)

        // Publish a new config exactly as an external updater would: a
        // same-directory temp file atomically renamed over config.json.
        try write(tmp, ConfigStore.configFileName, "{\"timeZone\":\"Asia/Tokyo\",\"use24Hour\":false}")
        let second = s.reload()
        XCTAssertEqual("Asia/Tokyo", second.timeZone)
        XCTAssertFalse(second.use24Hour)

        // The atomic dance leaves no temporary residue in the directory.
        for name in try directoryNames(tmp) {
            XCTAssertFalse(name.hasSuffix(".tmp"), "no leftover temp files: \(name)")
        }
    }

    // MARK: status.json

    func testStatusIsVerifierSafeAndReflectsConfig() throws {
        try write(tmp, ConfigStore.configFileName, "{\"bootStart\":false,\"timeZone\":\"UTC\"}")
        let s = store(tmp)
        s.reload()
        try s.writeStatus(launchedFromBoot: true)

        let statusFile = tmp.appendingPathComponent(ConfigStore.statusFileName)
        XCTAssertTrue(FileManager.default.fileExists(atPath: statusFile.path))
        let json = String(decoding: try Data(contentsOf: statusFile), as: UTF8.self)

        // It must be strict, well-formed JSON (round-trips through our parser).
        let parsed = try Json.parse(json)
        guard case .object = parsed else {
            XCTFail("status must be a JSON object")
            return
        }

        XCTAssertEqual(.int(1), parsed["statusSchemaVersion"])
        XCTAssertEqual(.string("external"), parsed["configSource"])
        XCTAssertEqual(.bool(false), parsed["lastReloadRejected"])
        XCTAssertEqual(.null, parsed["lastRejectReason"])
        XCTAssertEqual(.bool(true), parsed["bootLaunch"])
        XCTAssertEqual(.int(1_700_000_000_000), parsed["updatedAtEpochMillis"])

        let eff = parsed["effectiveConfig"]
        XCTAssertEqual(.bool(false), eff?["bootStart"])
        XCTAssertEqual(.string("UTC"), eff?["timeZone"])

        // Verifier-safety: no device identifiers, absolute paths, or raw input.
        XCTAssertFalse(json.contains("/home/"))
        XCTAssertFalse(json.contains("/var/"))
        XCTAssertFalse(json.contains("/private/"))
        XCTAssertFalse(json.lowercased().contains("serial"))
        XCTAssertFalse(json.lowercased().contains("udid"))
        XCTAssertFalse(json.lowercased().contains("secret"))
    }

    func testStatusCarriesEffectiveRendererSelections() throws {
        try write(tmp, ConfigStore.configFileName,
                  "{\"digitalColor\":\"silver\",\"showDate\":false,"
                    + "\"digitalSizePercent\":75,\"burnInEnabled\":false,"
                    + "\"burnInMaxShiftPx\":4,\"tickSound\":\"off\"}")
        let s = store(tmp)
        s.reload()

        let parsed = try Json.parse(s.renderStatus(launchedFromBoot: false))
        let eff = parsed["effectiveConfig"]

        // Approved names and bounded numbers only — never resolved colour values.
        XCTAssertEqual(.string("silver"), eff?["digitalColor"])
        XCTAssertEqual(.string("grey"), eff?["dateColor"])
        XCTAssertEqual(.string("silver"), eff?["tickColor"])
        XCTAssertEqual(.string("orange"), eff?["accentColor"])
        XCTAssertEqual(.bool(false), eff?["showDate"])
        XCTAssertEqual(.int(75), eff?["digitalSizePercent"])
        XCTAssertEqual(.int(100), eff?["secondarySizePercent"])
        XCTAssertEqual(.bool(false), eff?["burnInEnabled"])
        XCTAssertEqual(.int(4), eff?["burnInMaxShiftPx"])
        XCTAssertEqual(.string("off"), eff?["tickSound"])
    }

    func testStatusEffectiveConfigExposesOnlySchemaKeys() throws {
        let s = store(tmp)
        s.reload()
        let parsed = try Json.parse(s.renderStatus(launchedFromBoot: false))
        guard case .object(let pairs)? = parsed["effectiveConfig"] else {
            XCTFail("effectiveConfig must be a JSON object")
            return
        }
        // Everything the status publishes back must itself be a key the strict
        // schema accepts — nothing extra, nothing secret.
        for (key, value) in pairs {
            XCTAssertNoThrow(try ExternalConfig.parse("{\"\(key)\": \(jsonLiteral(value))}"),
                             "status key '\(key)' must round-trip through the schema")
        }
    }

    private func jsonLiteral(_ value: JSONValue) -> String {
        switch value {
        case .null:
            // The only nullable schema key is timeZone; round-trip a valid id.
            return "\"UTC\""
        case .string(let s):
            return "\"\(s)\""
        case .int(let n):
            return String(n)
        case .bool(let b):
            return String(b)
        default:
            XCTFail("unexpected status value type: \(value)")
            return "null"
        }
    }

    func testStatusRecordsRejectReasonButNotRawInput() throws {
        try write(tmp, ConfigStore.configFileName, "{\"timeZone\":\"Mars/Olympus\"}")
        let s = store(tmp)
        s.reload()

        let json = s.renderStatus(launchedFromBoot: false)
        XCTAssertTrue(json.contains("\"lastReloadRejected\": true"))
        XCTAssertTrue(json.contains("\"lastRejectReason\": \"OUT_OF_RANGE\""))
        // The offending raw value is never echoed into the status document.
        XCTAssertFalse(json.contains("Mars/Olympus"))
    }

    func testStatusWriteIsAtomicWithNoResidue() throws {
        let s = store(tmp)
        s.reload()
        try s.writeStatus(launchedFromBoot: false)
        for name in try directoryNames(tmp) {
            XCTAssertFalse(name.hasSuffix(".tmp"), "no leftover temp files: \(name)")
        }
    }

    func testStatusWriteReplacesExistingStatus() throws {
        let s = store(tmp)
        s.reload()
        try s.writeStatus(launchedFromBoot: false)
        try s.writeStatus(launchedFromBoot: true)

        let statusFile = tmp.appendingPathComponent(ConfigStore.statusFileName)
        let parsed = try Json.parse(String(decoding: try Data(contentsOf: statusFile),
                                           as: UTF8.self))
        XCTAssertEqual(.bool(true), parsed["bootLaunch"])
    }
}
