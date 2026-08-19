import UIKit

// Classic UIApplicationDelegate lifecycle (no scene manifest), mirroring the
// Android port's single full-screen Activity shell.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = ClockViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
