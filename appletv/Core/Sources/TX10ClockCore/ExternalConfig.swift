import Foundation

/// Tick-sound behaviour selection.
///
/// tvOS-only extension to the Android external-config contract: the Android
/// schema has no audio key; this build accepts an optional `"tickSound"`
/// string with the values below, defaulting to `.minute`.
public enum TickSound: String, Equatable, Hashable, CaseIterable {
    case off
    case minute
    case second
}

/// The validated runtime configuration model.
///
/// It carries behavioural preferences (auto-start on boot, 12/24-hour readout,
/// digital seconds, an optional display time zone, the tick sound) plus a small
/// set of strictly bounded renderer selections: approved colour-role names,
/// clipping-safe digital text sizes, the compact-date toggle, and the burn-in
/// shift enable/range. It encodes no raw visual values; resolving an approved
/// colour name to an actual colour value is ``ClockConfig/fromExternal(_:)``'s
/// job.
///
/// Instances are immutable and are only ever produced by ``defaults()`` or by
/// ``parse(_:)-swift.type.method``, which reject anything oversized, malformed,
/// with duplicate or unknown keys, or holding an out-of-range value. A
/// successfully parsed instance is what a ``ConfigStore`` retains as its
/// internal last-known-good copy.
public struct ExternalConfig: Equatable, Hashable {

    /// Hard upper bound on an accepted document; anything larger is rejected.
    public static let maxBytes = 8 * 1024

    /// The only configuration schema version this build accepts.
    public static let schemaVersion: Int64 = 1

    /// The approved colour-role names an external document may select. These
    /// are names, not values: the actual colour values live on the renderer
    /// side and are resolved by ``ClockConfig/fromExternal(_:)``. The set
    /// deliberately excludes any name that would render foreground content
    /// invisible on the fixed pure-black background.
    public static let approvedColorNames = ["white", "silver", "grey", "orange"]

    /// Inclusive lower bound for the digital text size percentages.
    public static let minSizePercent = 50

    /// Inclusive upper bound (clipping-safe design size) for text percentages.
    public static let maxSizePercent = 100

    /// Inclusive upper bound on the burn-in shift amplitude, per the contract.
    public static let maxBurnInShiftPx = 8

    private static let maxZoneLength = 64

    // Accepted keys. Any other key is rejected as UNKNOWN_KEY.
    private static let kSchemaVersion = "schemaVersion"
    private static let kBootStart = "bootStart"
    private static let kUse24Hour = "use24Hour"
    private static let kShowSeconds = "showSeconds"
    private static let kTimeZone = "timeZone"
    private static let kDigitalColor = "digitalColor"
    private static let kDateColor = "dateColor"
    private static let kTickColor = "tickColor"
    private static let kAccentColor = "accentColor"
    private static let kShowDate = "showDate"
    private static let kDigitalSizePercent = "digitalSizePercent"
    private static let kSecondarySizePercent = "secondarySizePercent"
    private static let kBurnInEnabled = "burnInEnabled"
    private static let kBurnInMaxShiftPx = "burnInMaxShiftPx"
    private static let kTickSound = "tickSound"

    /// Whether the app auto-starts after boot. Defaults true.
    public let bootStart: Bool

    /// Whether the digital readout uses 24-hour form. Defaults false.
    public let use24Hour: Bool

    /// Whether the second hand and seconds field are shown. Defaults true.
    public let showSeconds: Bool

    /// IANA zone id for the displayed time, or `nil` to follow the device's
    /// default zone. When set it is guaranteed to be a valid id.
    public let timeZone: String?

    /// Approved name for the digital/hands/numerals role. Defaults "white".
    public let digitalColor: String

    /// Approved name for the compact-date role. Defaults "grey".
    public let dateColor: String

    /// Approved name for the minor-tick role. Defaults "silver".
    public let tickColor: String

    /// Approved name for the accent (second hand/seconds) role. Defaults "orange".
    public let accentColor: String

    /// Whether the compact English date is shown. Defaults true.
    public let showDate: Bool

    /// Main digital line size, percent of design size (50..100). Defaults 100.
    public let digitalSizePercent: Int

    /// Secondary digital line size, percent of design size (50..100). Defaults 100.
    public let secondarySizePercent: Int

    /// Whether the periodic whole-composition burn-in shift runs. Defaults true.
    public let burnInEnabled: Bool

    /// Maximum burn-in shift amplitude in design pixels (0..8). Defaults 8.
    public let burnInMaxShiftPx: Int

    /// Tick-sound behaviour (tvOS-only key). Defaults `.minute`.
    public let tickSound: TickSound

    public init(bootStart: Bool = true,
                use24Hour: Bool = false,
                showSeconds: Bool = true,
                timeZone: String? = nil,
                digitalColor: String = "white",
                dateColor: String = "grey",
                tickColor: String = "silver",
                accentColor: String = "orange",
                showDate: Bool = true,
                digitalSizePercent: Int = 100,
                secondarySizePercent: Int = 100,
                burnInEnabled: Bool = true,
                burnInMaxShiftPx: Int = ExternalConfig.maxBurnInShiftPx,
                tickSound: TickSound = .minute) {
        self.bootStart = bootStart
        self.use24Hour = use24Hour
        self.showSeconds = showSeconds
        self.timeZone = timeZone
        self.digitalColor = digitalColor
        self.dateColor = dateColor
        self.tickColor = tickColor
        self.accentColor = accentColor
        self.showDate = showDate
        self.digitalSizePercent = digitalSizePercent
        self.secondarySizePercent = secondarySizePercent
        self.burnInEnabled = burnInEnabled
        self.burnInMaxShiftPx = burnInMaxShiftPx
        self.tickSound = tickSound
    }

    /// The built-in defaults used when no accepted external config exists.
    public static func defaults() -> ExternalConfig {
        ExternalConfig()
    }

    /// Resolve the configured time zone, or the supplied fallback when no
    /// explicit zone is set. The stored ``timeZone`` is always valid, so the
    /// force-unwrap-free lookup cannot fail in practice.
    public func resolveZone(fallback: TimeZone) -> TimeZone {
        guard let timeZone else { return fallback }
        return TimeZone(identifier: timeZone) ?? fallback
    }

    /// Parse and strictly validate raw UTF-8 config bytes.
    ///
    /// Throws ``ConfigError`` if the bytes are oversized, malformed, contain a
    /// duplicate or unknown key, are not a JSON object, or hold a value of the
    /// wrong type or outside its accepted range.
    public static func parse(_ raw: Data) throws -> ExternalConfig {
        if raw.count > maxBytes {
            throw ConfigError(reason: ConfigError.oversized,
                              message: "config exceeds \(maxBytes) byte limit (\(raw.count))")
        }
        // Java decodes bytes with replacement (never throws); String(decoding:)
        // matches that, unlike the failable String(data:encoding:).
        return try parse(String(decoding: raw, as: UTF8.self))
    }

    /// Parse and strictly validate a config document already decoded to text.
    public static func parse(_ text: String) throws -> ExternalConfig {
        let root = try Json.parse(text)
        guard case .object(let pairs) = root else {
            throw ConfigError(reason: ConfigError.notObject,
                              message: "top-level JSON value must be an object")
        }

        for (key, _) in pairs where !isKnownKey(key) {
            throw ConfigError(reason: ConfigError.unknownKey,
                              message: "unknown key '\(key)'")
        }

        // Duplicate keys were already rejected by the parser.
        let map = Dictionary(pairs, uniquingKeysWith: { first, _ in first })

        // schemaVersion: optional; when present must be exactly schemaVersion.
        if map[kSchemaVersion] != nil {
            let version = try requireInt(map, kSchemaVersion)
            if version != schemaVersion {
                throw ConfigError(reason: ConfigError.outOfRange,
                                  message: "unsupported schemaVersion \(version)"
                                    + " (this build accepts \(schemaVersion))")
            }
        }

        var bootStart = true
        var use24Hour = false
        var showSeconds = true
        var timeZone: String? = nil
        var digitalColor = "white"
        var dateColor = "grey"
        var tickColor = "silver"
        var accentColor = "orange"
        var showDate = true
        var digitalSizePercent = 100
        var secondarySizePercent = 100
        var burnInEnabled = true
        var burnInMaxShiftPx = maxBurnInShiftPx
        var tickSound = TickSound.minute

        if map[kBootStart] != nil {
            bootStart = try requireBoolean(map, kBootStart)
        }
        if map[kUse24Hour] != nil {
            use24Hour = try requireBoolean(map, kUse24Hour)
        }
        if map[kShowSeconds] != nil {
            showSeconds = try requireBoolean(map, kShowSeconds)
        }
        if map[kTimeZone] != nil {
            timeZone = try requireZone(map, kTimeZone)
        }
        if map[kDigitalColor] != nil {
            digitalColor = try requireColorName(map, kDigitalColor)
        }
        if map[kDateColor] != nil {
            dateColor = try requireColorName(map, kDateColor)
        }
        if map[kTickColor] != nil {
            tickColor = try requireColorName(map, kTickColor)
        }
        if map[kAccentColor] != nil {
            accentColor = try requireColorName(map, kAccentColor)
        }
        if map[kShowDate] != nil {
            showDate = try requireBoolean(map, kShowDate)
        }
        if map[kDigitalSizePercent] != nil {
            digitalSizePercent = try requireIntInRange(map, kDigitalSizePercent,
                                                       min: minSizePercent, max: maxSizePercent)
        }
        if map[kSecondarySizePercent] != nil {
            secondarySizePercent = try requireIntInRange(map, kSecondarySizePercent,
                                                         min: minSizePercent, max: maxSizePercent)
        }
        if map[kBurnInEnabled] != nil {
            burnInEnabled = try requireBoolean(map, kBurnInEnabled)
        }
        if map[kBurnInMaxShiftPx] != nil {
            burnInMaxShiftPx = try requireIntInRange(map, kBurnInMaxShiftPx,
                                                     min: 0, max: maxBurnInShiftPx)
        }
        if map[kTickSound] != nil {
            tickSound = try requireTickSound(map, kTickSound)
        }

        return ExternalConfig(bootStart: bootStart,
                              use24Hour: use24Hour,
                              showSeconds: showSeconds,
                              timeZone: timeZone,
                              digitalColor: digitalColor,
                              dateColor: dateColor,
                              tickColor: tickColor,
                              accentColor: accentColor,
                              showDate: showDate,
                              digitalSizePercent: digitalSizePercent,
                              secondarySizePercent: secondarySizePercent,
                              burnInEnabled: burnInEnabled,
                              burnInMaxShiftPx: burnInMaxShiftPx,
                              tickSound: tickSound)
    }

    private static func isKnownKey(_ key: String) -> Bool {
        switch key {
        case kSchemaVersion, kBootStart, kUse24Hour, kShowSeconds, kTimeZone,
             kDigitalColor, kDateColor, kTickColor, kAccentColor, kShowDate,
             kDigitalSizePercent, kSecondarySizePercent,
             kBurnInEnabled, kBurnInMaxShiftPx, kTickSound:
            return true
        default:
            return false
        }
    }

    private static func requireBoolean(_ map: [String: JSONValue], _ key: String) throws -> Bool {
        guard case .bool(let value)? = map[key] else {
            throw wrongType(key, expected: "boolean", actual: map[key])
        }
        return value
    }

    private static func requireInt(_ map: [String: JSONValue], _ key: String) throws -> Int64 {
        // Json yields .int for integral numbers and .double for fractional
        // ones; a fractional value is not an acceptable integer.
        guard case .int(let value)? = map[key] else {
            throw wrongType(key, expected: "integer", actual: map[key])
        }
        return value
    }

    private static func requireZone(_ map: [String: JSONValue], _ key: String) throws -> String {
        guard case .string(let zone)? = map[key] else {
            throw wrongType(key, expected: "string", actual: map[key])
        }
        if zone.utf16.count > maxZoneLength {
            throw ConfigError(reason: ConfigError.outOfRange,
                              message: "timeZone exceeds \(maxZoneLength) chars")
        }
        // Deviation from the Android build: TimeZone(identifier:) accepts IANA
        // ids only, so bare offset forms like "+05:00" that java.time ZoneId
        // would accept are rejected on tvOS.
        guard TimeZone(identifier: zone) != nil else {
            throw ConfigError(reason: ConfigError.outOfRange,
                              message: "unknown timeZone '\(zone)'")
        }
        // Keep the operator's string verbatim (java ZoneId.of(z).getId() returns
        // the input for valid IANA ids). Foundation's own normalization is
        // platform-dependent — Darwin rewrites "UTC" to "GMT", Linux does not.
        return zone
    }

    private static func requireColorName(_ map: [String: JSONValue], _ key: String) throws -> String {
        guard case .string(let name)? = map[key] else {
            throw wrongType(key, expected: "string", actual: map[key])
        }
        guard approvedColorNames.contains(name) else {
            throw ConfigError(reason: ConfigError.outOfRange,
                              message: "key '\(key)' must be one of the approved colour names "
                                + "[\(approvedColorNames.joined(separator: ", "))]")
        }
        return name
    }

    private static func requireTickSound(_ map: [String: JSONValue], _ key: String) throws -> TickSound {
        guard case .string(let name)? = map[key] else {
            throw wrongType(key, expected: "string", actual: map[key])
        }
        guard let sound = TickSound(rawValue: name) else {
            let allowed = TickSound.allCases.map(\.rawValue).joined(separator: ", ")
            throw ConfigError(reason: ConfigError.outOfRange,
                              message: "key '\(key)' must be one of [\(allowed)]")
        }
        return sound
    }

    private static func requireIntInRange(_ map: [String: JSONValue], _ key: String,
                                          min: Int, max: Int) throws -> Int {
        // Json yields .int for integral numbers and .double for fractional
        // ones; a fractional value is not an acceptable integer.
        guard case .int(let value)? = map[key] else {
            throw wrongType(key, expected: "integer", actual: map[key])
        }
        if value < Int64(min) || value > Int64(max) {
            throw ConfigError(reason: ConfigError.outOfRange,
                              message: "key '\(key)' must be in [\(min)..\(max)] but was \(value)")
        }
        return Int(value)
    }

    private static func wrongType(_ key: String, expected: String, actual: JSONValue?) -> ConfigError {
        ConfigError(reason: ConfigError.wrongType,
                    message: "key '\(key)' expected \(expected) but got \(typeName(actual))")
    }

    private static func typeName(_ value: JSONValue?) -> String {
        switch value {
        case .object: return "Object"
        case .array: return "Array"
        case .string: return "String"
        case .int: return "Long"
        case .double: return "Double"
        case .bool: return "Boolean"
        case .null, nil: return "null"
        }
    }
}
