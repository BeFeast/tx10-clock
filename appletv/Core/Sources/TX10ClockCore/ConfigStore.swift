import Foundation
#if canImport(Glibc)
import Glibc
#endif

/// File-backed store for the app-owned external configuration and the
/// verifier-safe `status.json`.
///
/// The store operates on a plain directory URL — on tvOS the app layer points
/// it at Library/Caches, the app data container path reachable by
/// `xcrun devicectl copy` — but the class itself has no UIKit dependency, so
/// its last-known-good, reload, atomic-replacement, and status behaviours are
/// exercised deterministically under plain `swift test`. Because Caches is
/// purgeable, mirroring the accepted last-known-good JSON into UserDefaults is
/// the app layer's job, not this class's.
///
/// ## Update protocol
/// A well-behaved external updater publishes a new config by writing a
/// same-directory temporary file and then doing an atomic rename over
/// `config.json`. The app never observes a partially written file: it always
/// reads a whole prior or whole new document. The store uses the identical
/// temp-plus-atomic-replace dance for its own `status.json` writes (see
/// ``atomicWrite(to:content:)``).
///
/// ## Last-known-good
/// ``reload()`` re-reads `config.json`. A document that passes strict
/// ``ExternalConfig`` validation becomes the new last-known-good. Anything
/// rejected (or an absent/unreadable file) leaves the previously accepted
/// config in force; the rejection reason is recorded for `status.json`.
public final class ConfigStore {

    public static let configFileName = "config.json"
    public static let statusFileName = "status.json"
    public static let statusSchemaVersion = 1

    /// Where the effective config came from.
    public enum Source: String {
        case `default`
        case external
    }

    private let dir: URL
    private let maxBytes: Int64
    private let now: () -> Date

    /// The effective (last-known-good) configuration currently in force.
    public private(set) var current: ExternalConfig = .defaults()

    /// Where ``current`` came from.
    public private(set) var source: Source = .default

    /// The reason (a ``ConfigError`` reason code) the most recent reload
    /// rejected a document, or `nil`.
    public private(set) var lastRejectReason: String?

    public convenience init(dir: URL) {
        self.init(dir: dir, maxBytes: Int64(ExternalConfig.maxBytes), now: Date.init)
    }

    public init(dir: URL, maxBytes: Int64, now: @escaping () -> Date) {
        self.dir = dir
        self.maxBytes = maxBytes
        self.now = now
    }

    public var configFile: URL {
        dir.appendingPathComponent(Self.configFileName)
    }

    public var statusFile: URL {
        dir.appendingPathComponent(Self.statusFileName)
    }

    /// Re-read `config.json` and update the last-known-good copy iff the
    /// document is accepted. Never throws for bad input: a rejected, absent,
    /// or unreadable file leaves the prior accepted config in force and
    /// returns it.
    @discardableResult
    public func reload() -> ExternalConfig {
        let file = configFile
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: file.path, isDirectory: &isDirectory),
              !isDirectory.boolValue else {
            // Absence is not a rejection: there is simply no external override.
            return current
        }
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
            let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
            if size > maxBytes {
                lastRejectReason = ConfigError.oversized
                return current
            }
            let raw = try Data(contentsOf: file)
            let parsed = try ExternalConfig.parse(raw)
            current = parsed
            source = .external
            lastRejectReason = nil
            return current
        } catch let error as ConfigError {
            lastRejectReason = error.reason
            return current
        } catch {
            // Transient read failure — keep the last-known-good, note it coarsely.
            lastRejectReason = ConfigError.malformed
            return current
        }
    }

    /// Atomically write the verifier-safe `status.json`. It exposes only
    /// coarse runtime/config state and the effective (operator-supplied)
    /// config values — never device identifiers, absolute paths, raw input, or
    /// secrets.
    ///
    /// - Parameter launchedFromBoot: whether the current run was started
    ///   automatically at boot rather than by the user.
    public func writeStatus(launchedFromBoot: Bool) throws {
        try Self.atomicWrite(to: statusFile,
                             content: Data(renderStatus(launchedFromBoot: launchedFromBoot).utf8))
    }

    /// The exact `status.json` body, exposed for deterministic testing.
    func renderStatus(launchedFromBoot: Bool) -> String {
        let epochMillis = Int64((now().timeIntervalSince1970 * 1000.0).rounded())
        var sb = ""
        sb += "{\n"
        sb += "  \"statusSchemaVersion\": \(Self.statusSchemaVersion),\n"
        sb += "  \"configSource\": \(Self.quote(source.rawValue)),\n"
        sb += "  \"lastReloadRejected\": \(lastRejectReason != nil),\n"
        sb += "  \"lastRejectReason\": \(lastRejectReason.map(Self.quote) ?? "null"),\n"
        sb += "  \"bootLaunch\": \(launchedFromBoot),\n"
        sb += "  \"updatedAtEpochMillis\": \(epochMillis),\n"
        sb += "  \"effectiveConfig\": {\n"
        sb += "    \"schemaVersion\": \(ExternalConfig.schemaVersion),\n"
        sb += "    \"bootStart\": \(current.bootStart),\n"
        sb += "    \"use24Hour\": \(current.use24Hour),\n"
        sb += "    \"showSeconds\": \(current.showSeconds),\n"
        sb += "    \"timeZone\": \(current.timeZone.map(Self.quote) ?? "null"),\n"
        sb += "    \"digitalColor\": \(Self.quote(current.digitalColor)),\n"
        sb += "    \"dateColor\": \(Self.quote(current.dateColor)),\n"
        sb += "    \"tickColor\": \(Self.quote(current.tickColor)),\n"
        sb += "    \"accentColor\": \(Self.quote(current.accentColor)),\n"
        sb += "    \"showDate\": \(current.showDate),\n"
        sb += "    \"digitalSizePercent\": \(current.digitalSizePercent),\n"
        sb += "    \"secondarySizePercent\": \(current.secondarySizePercent),\n"
        sb += "    \"burnInEnabled\": \(current.burnInEnabled),\n"
        sb += "    \"burnInMaxShiftPx\": \(current.burnInMaxShiftPx),\n"
        sb += "    \"tickSound\": \(Self.quote(current.tickSound.rawValue))\n"
        sb += "  }\n"
        sb += "}\n"
        return sb
    }

    /// Write `content` to `target` via a same-directory temporary file and an
    /// atomic replacement, so a concurrent reader never sees a partial
    /// document. This is the exact protocol external updaters use for
    /// `config.json`; a representative round-trip is covered by the tests.
    public static func atomicWrite(to target: URL, content: Data) throws {
        let parent = target.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        let tmp = parent.appendingPathComponent(
            target.lastPathComponent + "." + UUID().uuidString + ".tmp")
        try content.write(to: tmp)
        do {
            #if canImport(Darwin)
            if FileManager.default.fileExists(atPath: target.path) {
                _ = try FileManager.default.replaceItemAt(target, withItemAt: tmp)
            } else {
                // replaceItemAt requires an existing original; a plain move of
                // the same-directory temp file is equally atomic here.
                try FileManager.default.moveItem(at: tmp, to: target)
            }
            #else
            // swift-corelibs-foundation's replaceItemAt cannot replace an
            // existing file; POSIX rename is the same single-volume atomic
            // replace for the Linux test runs.
            guard rename(tmp.path, target.path) == 0 else {
                throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
            }
            #endif
        } catch {
            try? FileManager.default.removeItem(at: tmp)
            throw error
        }
    }

    private static func quote(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    let hex = String(scalar.value, radix: 16)
                    out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
        return out
    }
}
