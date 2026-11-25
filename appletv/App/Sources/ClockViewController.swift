import UIKit
import TX10ClockCore

/// Hosts the full-screen `ClockView` and wires config, tick sound, and
/// keep-awake, following MainActivity's lifecycle: reload + apply + start on
/// every resume, stop on pause.
final class ClockViewController: UIViewController {

    private let clockView = ClockView(config: .defaultConfig(),
                                      timeSource: SystemTimeSource(timeZone: .current))
    private let configProvider = ConfigProvider()
    private let keepAwake = KeepAwake()
    private let tickSound = TickSoundPlayer()
    private var lifecycleObservers: [NSObjectProtocol] = []

    override func loadView() {
        view = clockView
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        configProvider.onReload = { [weak self] config in
            self?.apply(config)
        }

        // tvOS keeps the view controller "appeared" while the app is
        // backgrounded, so foreground/background notifications stand in for
        // Android's onResume/onPause.
        let center = NotificationCenter.default
        lifecycleObservers.append(center.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.resume()
        })
        lifecycleObservers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.pause()
        })
    }

    deinit {
        lifecycleObservers.forEach(NotificationCenter.default.removeObserver)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        resume()
    }

    override func viewWillDisappear(_ animated: Bool) {
        pause()
        super.viewWillDisappear(animated)
    }

    private func resume() {
        configProvider.start()
        tickSound.setActive(true)
        keepAwake.enable()
        clockView.start()
    }

    private func pause() {
        clockView.stop()
        keepAwake.disable()
        tickSound.setActive(false)
        configProvider.stop()
    }

    private func apply(_ config: ExternalConfig) {
        clockView.config = ClockConfig.fromExternal(config)
        clockView.timeSource = SystemTimeSource(timeZone: Self.timeZone(for: config))
        tickSound.apply(mode: config.tickSound)
    }

    private static func timeZone(for config: ExternalConfig) -> TimeZone {
        // Java resolveZone(systemDefault): an accepted timeZone id is always
        // valid, so the fallback only covers the follow-the-device case.
        config.timeZone.flatMap(TimeZone.init(identifier:)) ?? .current
    }
}
