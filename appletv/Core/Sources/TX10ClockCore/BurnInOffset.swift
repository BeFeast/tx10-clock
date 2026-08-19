import Foundation

/// Deterministic burn-in protection offset for the whole composition.
///
/// Implements the accepted visual contract v0.1.0 burn-in clauses
/// (`geometry.json#burn_in`, `motion.json#burn_in`): a discrete
/// whole-composition translation whose x and y components each stay within
/// ±`boundPx` px, holding one position for a full local wall-clock minute and
/// moving instantly to the next position at the minute boundary.
///
/// The schedule is a boustrophedon (serpentine) scan of the full 17×17 offset
/// grid, one cell per minute, wrapping after `cycleMinutes` minutes (~4.8 h).
/// This gives:
///
/// - **Determinism** — the offset is a pure function of the local wall-clock
///   minute; equal minutes always yield equal offsets.
/// - **Guaranteed change** — consecutive scan cells differ by exactly one
///   pixel on one axis, and the cycle wrap jumps from (+8,+8) back to (-8,-8),
///   so the offset changes at every minute boundary.
/// - **Uniform coverage** — every representable position, including the
///   centered frame (0,0) and the contract's corner-extreme frames (-8,-8)
///   and (+8,+8), is occupied exactly once per cycle.
///
/// This type is intentionally pure: no randomness, no persistence, no UIKit
/// dependency. Applying the translation to the rendered frame is the
/// renderer's concern and is out of this type's scope.
public struct BurnInOffset: Hashable, CustomStringConvertible {

    /// Maximum magnitude of each offset component, per geometry.json.
    public static let boundPx = 8

    /// How long one position is held, per motion.json.
    public static let cadenceSeconds = 60

    /// Grid side length: every integer offset in [-boundPx, +boundPx].
    private static let span = 2 * boundPx + 1

    /// Minutes until the scan revisits a position (17 × 17 cells).
    public static let cycleMinutes = span * span

    /// Horizontal translation in pixels, within [-boundPx, +boundPx].
    public let x: Int

    /// Vertical translation in pixels, within [-boundPx, +boundPx].
    public let y: Int

    private init(x: Int, y: Int) {
        self.x = x
        self.y = y
    }

    /// The offset in effect at `now`, constant for the whole local wall-clock
    /// minute containing it. Each configured bound gets its own complete
    /// serpentine grid, so reducing the amplitude still changes position at
    /// every minute boundary instead of clamping several consecutive default
    /// positions to the same edge value.
    public static func at(_ now: ZonedInstant, boundPx: Int = BurnInOffset.boundPx) -> BurnInOffset {
        forMinuteIndex(minuteIndexOf(now), boundPx: boundPx)
    }

    /// The local wall-clock minute `now` falls in, counted from the local
    /// epoch. Uses the wall-clock fields (not the instant) so the schedule
    /// follows what the viewer sees on screen.
    public static func minuteIndexOf(_ now: ZonedInstant) -> Int64 {
        floorDiv(now.localEpochSeconds(), Int64(cadenceSeconds))
    }

    /// Offset for one minute and a caller-selected bound in `[0, boundPx]`;
    /// pure and total for any minute index. The default bound is the exact
    /// accepted 17x17 contract; smaller bounds traverse their own full grid.
    public static func forMinuteIndex(_ minuteIndex: Int64,
                                      boundPx: Int = BurnInOffset.boundPx) -> BurnInOffset {
        precondition(boundPx >= 0 && boundPx <= Self.boundPx,
                     "boundPx must be between 0 and \(Self.boundPx)")
        let span = 2 * boundPx + 1
        let cycleMinutes = span * span
        let cell = Int(floorMod(minuteIndex, Int64(cycleMinutes)))
        let row = cell / span
        let col = cell % span
        // Even rows scan left-to-right, odd rows right-to-left, so each step
        // within the cycle moves exactly one pixel on exactly one axis; the
        // wrap jumps from the positive corner back to the negative corner.
        let x = (row % 2 == 0 ? col : span - 1 - col) - boundPx
        let y = row - boundPx
        return BurnInOffset(x: x, y: y)
    }

    public var description: String {
        "BurnInOffset(\(x), \(y))"
    }

    // Swift / and % truncate toward zero; Java Math.floorDiv/floorMod floor
    // toward negative infinity, which the pre-epoch schedule relies on.
    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    private static func floorMod(_ a: Int64, _ b: Int64) -> Int64 {
        let r = a % b
        return (r != 0 && (r < 0) != (b < 0)) ? r + b : r
    }
}
