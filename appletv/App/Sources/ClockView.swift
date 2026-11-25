import CoreGraphics
import Foundation
import TX10ClockCore
import UIKit

/// Full-frame clock surface with display-synchronised smooth animation.
final class ClockView: UIView {

    /// Atomically applied behavioral config; setting rebuilds the renderer.
    var config: ClockConfig {
        didSet {
            renderer = ClockRenderer(config: config)
            setNeedsDisplay()
        }
    }

    var timeSource: TimeSource {
        didSet {
            setNeedsDisplay()
        }
    }

    private var renderer: ClockRenderer
    private var displayLink: CADisplayLink?

    init(config: ClockConfig, timeSource: TimeSource) {
        self.config = config
        self.timeSource = timeSource
        self.renderer = ClockRenderer(config: config)
        super.init(frame: .zero)
        isOpaque = true
        contentMode = .redraw
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    deinit {
        displayLink?.invalidate()
    }

    /// Atomically apply a newly accepted behavioral config on the UI thread.
    func apply(_ config: ClockConfig, timeSource: TimeSource) {
        self.timeSource = timeSource
        self.config = config
    }

    func start() {
        guard displayLink == nil else {
            return
        }
        let link = CADisplayLink(target: DisplayLinkProxy(self),
                                 selector: #selector(DisplayLinkProxy.tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
        setNeedsDisplay()
    }

    func stop() {
        displayLink?.invalidate()
        displayLink = nil
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else {
            return
        }
        let now = timeSource.now()
        // Burn-in protection shifts the whole composition by a small, bounded,
        // per-minute amount, applied here (in view pixels, before the
        // renderer's letterbox) exactly as the Android ClockView does. Android
        // Canvas.drawColor fills the clip ignoring the matrix; a CG fill obeys
        // the CTM, so the full bounds are painted before any transform to keep
        // the shifted composition from exposing an unpainted edge.
        ctx.setFillColor(srgbColor(renderer.config.backgroundColor))
        ctx.fill(bounds)
        let shift = ClockView.burnInTranslation(config: renderer.config, now: now)
        ctx.saveGState()
        ctx.translateBy(x: CGFloat(shift.x), y: CGFloat(shift.y))
        renderer.render(ctx, width: bounds.width, height: bounds.height, now: now)
        ctx.restoreGState()
    }

    /// The bounded burn-in translation to apply for `now`: (0, 0) when burn-in
    /// is disabled or its amplitude is zero, otherwise the deterministic
    /// BurnInOffset with each axis clamped to the configured burnInMaxShiftPx.
    static func burnInTranslation(config: ClockConfig, now: ZonedInstant) -> (x: Int, y: Int) {
        if !config.burnInEnabled || config.burnInMaxShiftPx <= 0 {
            return (0, 0)
        }
        let offset = BurnInOffset.at(now, boundPx: config.burnInMaxShiftPx)
        return (offset.x, offset.y)
    }
}

/// CADisplayLink retains its target and the run loop retains the link; the
/// weak proxy breaks the would-be view retain cycle.
private final class DisplayLinkProxy: NSObject {

    private weak var view: ClockView?

    init(_ view: ClockView) {
        self.view = view
    }

    @objc func tick() {
        view?.setNeedsDisplay()
    }
}
