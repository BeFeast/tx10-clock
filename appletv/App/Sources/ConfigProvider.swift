import Foundation
import TX10ClockCore

/// tvOS replacement for the Android `ConfigDir`/`MainActivity` config plumbing.
///
/// `config.json` and `status.json` live in `Library/Caches` — the app data
/// container path reachable by
/// `xcrun devicectl device copy --domain-type appDataContainer` — playing the
/// role of Android's app-scoped external files dir. Caches is purgeable, so
/// every accepted document is also mirrored (raw JSON string) into
/// `UserDefaults` and re-seeded from there whenever the file has vanished.
///
/// `BootReceiver` has no tvOS counterpart (apps cannot self-start at boot):
/// `bootStart` is still accepted for contract parity but has no effect, and
/// `status.json` always reports `"bootLaunch": false`.
final class ConfigProvider {

    static let mirrorKey = "tx10.lastKnownGoodConfig"
    static let reloadInterval: TimeInterval = 30

    // Contract MAX_BYTES: never mirror a document the store would reject as oversized.
    private static let maxConfigBytes = 8 * 1024

    private let store: ConfigStore
    private let configFileURL: URL
    private let defaults: UserDefaults
    private var timer: Timer?

    /// Fired on the main run loop after every load/reload with the effective
    /// (last-known-good) configuration; consumers must apply idempotently.
    var onReload: ((ExternalConfig) -> Void)?

    init(defaults: UserDefaults = .standard) {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        self.store = ConfigStore(dir: caches)
        self.configFileURL = caches.appendingPathComponent("config.json")
        self.defaults = defaults
    }

    /// MainActivity reloads on every Activity resume; a foregrounded tvOS app
    /// can run for months without one, so a main-run-loop timer adds the
    /// periodic pickup an atomic `config.json` replacement relies on.
    func start() {
        reloadNow()
        guard timer == nil else { return }
        let timer = Timer(timeInterval: Self.reloadInterval, repeats: true) { [weak self] _ in
            self?.reloadNow()
        }
        timer.tolerance = 5
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    func reloadNow() {
        seedFromMirrorIfNeeded()
        let config = store.reload()
        mirrorAcceptedDocument()
        publishStatus()
        onReload?(config)
    }

    private func seedFromMirrorIfNeeded() {
        guard !FileManager.default.fileExists(atPath: configFileURL.path),
              let mirrored = defaults.string(forKey: Self.mirrorKey),
              let data = mirrored.data(using: .utf8) else {
            return
        }
        // Data's .atomic is the same temp-plus-rename protocol the contract
        // requires of external updaters, so the store never sees a partial file.
        try? data.write(to: configFileURL, options: .atomic)
    }

    private func mirrorAcceptedDocument() {
        // Re-read and re-validate rather than trusting the store's outcome: an
        // atomic replacement may land between the store's read and ours, and
        // the mirror must only ever hold a document that parses as accepted.
        guard let data = try? Data(contentsOf: configFileURL),
              data.count <= Self.maxConfigBytes,
              let text = String(data: data, encoding: .utf8),
              (try? ExternalConfig.parse(text)) != nil else {
            return
        }
        defaults.set(text, forKey: Self.mirrorKey)
    }

    private func publishStatus() {
        // status.json is diagnostic only; never let a write failure disturb the
        // clock itself (MainActivity parity).
        try? store.writeStatus(launchedFromBoot: false)
    }
}
