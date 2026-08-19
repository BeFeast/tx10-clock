import Foundation

/// Immutable visual/behavioural configuration for the clock renderer.
///
/// All colours are packed 0xAARRGGBB values so the type has no UIKit
/// dependency and can be constructed and asserted on in plain `swift test`
/// runs. The renderer is a pure function of a ``ClockConfig`` plus a
/// timestamp, which is what makes the golden harness deterministic.
///
/// The Java Builder collapses to this memberwise initializer; every default
/// is the dark theme.
public struct ClockConfig: Equatable {

    public let backgroundColor: UInt32
    public let faceColor: UInt32
    public let tickColor: UInt32
    public let hourHandColor: UInt32
    public let minuteHandColor: UInt32
    public let secondHandColor: UInt32
    public let digitalColor: UInt32
    public let dateColor: UInt32

    /// Whether the digital readout uses 24-hour (true) or 12-hour (false) form.
    public let use24Hour: Bool

    /// Whether the sweeping second hand is drawn.
    public let showSeconds: Bool

    /// Whether the compact English date is drawn on the secondary line.
    public let showDate: Bool

    /// Main digital line size as a percentage of the design size (50..100).
    public let digitalSizePercent: Int

    /// Secondary digital line size as a percentage of the design size (50..100).
    public let secondarySizePercent: Int

    /// Whether the periodic whole-composition burn-in shift is enabled.
    public let burnInEnabled: Bool

    /// Maximum burn-in shift amplitude in design pixels (0..8).
    public let burnInMaxShiftPx: Int

    public init(backgroundColor: UInt32 = 0xFF000000,
                faceColor: UInt32 = 0xFF000000,
                tickColor: UInt32 = 0xFFD1D1D6,
                hourHandColor: UInt32 = 0xFFF5F5F7,
                minuteHandColor: UInt32 = 0xFFF5F5F7,
                secondHandColor: UInt32 = 0xFFFF9F0A,
                digitalColor: UInt32 = 0xFFF5F5F7,
                dateColor: UInt32 = 0xFFA1A1A6,
                use24Hour: Bool = false,
                showSeconds: Bool = true,
                showDate: Bool = true,
                digitalSizePercent: Int = 100,
                secondarySizePercent: Int = 100,
                burnInEnabled: Bool = true,
                burnInMaxShiftPx: Int = 8) {
        self.backgroundColor = backgroundColor
        self.faceColor = faceColor
        self.tickColor = tickColor
        self.hourHandColor = hourHandColor
        self.minuteHandColor = minuteHandColor
        self.secondHandColor = secondHandColor
        self.digitalColor = digitalColor
        self.dateColor = dateColor
        self.use24Hour = use24Hour
        self.showSeconds = showSeconds
        self.showDate = showDate
        self.digitalSizePercent = digitalSizePercent
        self.secondarySizePercent = secondarySizePercent
        self.burnInEnabled = burnInEnabled
        self.burnInMaxShiftPx = burnInMaxShiftPx
    }

    /// The default elegant dark theme used by the shipping app and the golden
    /// harness. Kept as a single source of truth so a golden regenerated from
    /// this config matches what the device renders.
    public static func defaultConfig() -> ClockConfig {
        ClockConfig()
    }

    /// Map a strictly validated ``ExternalConfig`` onto the renderer model.
    ///
    /// This is the single place where an approved colour-role name resolves to
    /// an accepted-contract colour value. The mapping never invents colours,
    /// the background stays pure black, and geometry/typography remain
    /// untouched. Because the input has already passed strict parsing, its
    /// bounded fields (sizes, burn-in range) are threaded through as-is. The
    /// behavioural, non-visual `tickSound` deliberately does not enter the
    /// renderer model.
    public static func fromExternal(_ external: ExternalConfig) -> ClockConfig {
        let primary = resolveColor(external.digitalColor)
        return ClockConfig(tickColor: resolveColor(external.tickColor),
                           hourHandColor: primary,
                           minuteHandColor: primary,
                           secondHandColor: resolveColor(external.accentColor),
                           digitalColor: primary,
                           dateColor: resolveColor(external.dateColor),
                           use24Hour: external.use24Hour,
                           showSeconds: external.showSeconds,
                           showDate: external.showDate,
                           digitalSizePercent: external.digitalSizePercent,
                           secondarySizePercent: external.secondarySizePercent,
                           burnInEnabled: external.burnInEnabled,
                           burnInMaxShiftPx: external.burnInMaxShiftPx)
    }

    /// Resolve an approved colour-role name to its accepted-contract packed
    /// value. The name set is closed by strict parsing, so an unknown name
    /// here is a programming error, not user input.
    private static func resolveColor(_ name: String) -> UInt32 {
        switch name {
        case "white": return 0xFFF5F5F7  // contract primary
        case "silver": return 0xFFD1D1D6 // contract ticks
        case "grey": return 0xFFA1A1A6   // contract secondary
        case "orange": return 0xFFFF9F0A // contract accent
        default:
            preconditionFailure("unapproved colour name: \(name)")
        }
    }
}
