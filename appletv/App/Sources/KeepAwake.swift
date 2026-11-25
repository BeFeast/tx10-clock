import UIKit

/// tvOS counterpart of Android's FLAG_KEEP_SCREEN_ON: a clock must never let
/// the TV blank out.
///
/// `isIdleTimerDisabled` has regressed across tvOS releases (the flag is
/// sometimes silently dropped after route/focus changes); the documented
/// workaround is to periodically toggle it off and back on.
final class KeepAwake {

    private var timer: Timer?

    func enable() {
        UIApplication.shared.isIdleTimerDisabled = true
        guard timer == nil else { return }
        let timer = Timer(timeInterval: 60, repeats: true) { _ in
            UIApplication.shared.isIdleTimerDisabled = false
            UIApplication.shared.isIdleTimerDisabled = true
        }
        timer.tolerance = 5
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func disable() {
        timer?.invalidate()
        timer = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }
}
