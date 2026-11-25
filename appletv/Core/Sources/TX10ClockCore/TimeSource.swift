import Foundation

/// An instant paired with the time zone it should be read in — the port's
/// stand-in for `java.time.ZonedDateTime` (Foundation's `Date` carries no zone).
public struct ZonedInstant {

    public let date: Date
    public let timeZone: TimeZone

    private let components: DateComponents

    public init(date: Date, timeZone: TimeZone) {
        self.date = date
        self.timeZone = timeZone
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        self.components = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second, .nanosecond, .weekday],
            from: date)
    }

    /// Builds the instant from wall-clock fields, like `ZonedDateTime.of`.
    /// Returns `nil` when the calendar cannot resolve the fields.
    public init?(year: Int, month: Int, dayOfMonth: Int,
                 hour: Int, minute: Int, second: Int, nano: Int = 0,
                 timeZone: TimeZone) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        var fields = DateComponents()
        fields.year = year
        fields.month = month
        fields.day = dayOfMonth
        fields.hour = hour
        fields.minute = minute
        fields.second = second
        guard let base = calendar.date(from: fields) else {
            return nil
        }
        // Nanoseconds ride on Date's Double seconds, so sub-microsecond values
        // are approximate; whole-second fields stay exact.
        let date = nano == 0
            ? base
            : base.addingTimeInterval(Double(nano) / 1_000_000_000)
        self.init(date: date, timeZone: timeZone)
    }

    public var year: Int { components.year! }

    public var month: Int { components.month! }

    public var dayOfMonth: Int { components.day! }

    public var hour: Int { components.hour! }

    public var minute: Int { components.minute! }

    public var second: Int { components.second! }

    public var nano: Int { components.nanosecond! }

    /// Day of week in the `java.time` convention, Monday=1...Sunday=7
    /// (Foundation's `weekday` is Sunday=1...Saturday=7).
    public var dayOfWeekMon1: Int { ((components.weekday! + 5) % 7) + 1 }

    /// The wall-clock reading as epoch seconds, equal to Java's
    /// `toLocalDateTime().toEpochSecond(ZoneOffset.UTC)`. Computed from the
    /// calendar fields, exactly like Java, rather than as instant + zone
    /// offset: on Linux, `TimeZone.secondsFromGMT(for:)` and the ICU-backed
    /// calendar disagree about DST beyond tzdata's last pre-computed
    /// transition (~2037), and the fields are what the viewer sees. The
    /// calendar floors sub-second instants (pre-1970 included), matching
    /// Java's floored epoch second.
    public func localEpochSeconds() -> Int64 {
        Self.epochDay(year: year, month: month, day: dayOfMonth) * 86_400
            + Int64(hour) * 3_600 + Int64(minute) * 60 + Int64(second)
    }

    // days_from_civil (proleptic Gregorian); bit-exact with java.time
    // LocalDate.toEpochDay for any year the calendar can produce.
    private static func epochDay(year: Int, month: Int, day: Int) -> Int64 {
        let y = Int64(year) - (month <= 2 ? 1 : 0)
        let era = (y >= 0 ? y : y - 399) / 400
        let yearOfEra = y - era * 400
        let dayOfYear = Int64((153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1)
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }
}

/// The clock's single source of "now". Injecting this lets the golden harness
/// pin the renderer to a fixed instant so offscreen frames are deterministic,
/// while production uses the real system clock.
public protocol TimeSource {

    /// The current moment, already resolved to the desired time zone.
    func now() -> ZonedInstant
}

/// A time source backed by the device's real clock, in either the current
/// default time zone (resolved per call, like `ZonedDateTime.now()`) or a
/// pinned zone.
public struct SystemTimeSource: TimeSource {

    private let pinnedZone: TimeZone?

    public init() {
        self.pinnedZone = nil
    }

    public init(timeZone: TimeZone) {
        self.pinnedZone = timeZone
    }

    public func now() -> ZonedInstant {
        ZonedInstant(date: Date(), timeZone: pinnedZone ?? .current)
    }
}

/// A time source that always returns the same instant. Used by tests.
public struct FixedTimeSource: TimeSource {

    private let instant: ZonedInstant

    public init(_ instant: ZonedInstant) {
        self.instant = instant
    }

    public func now() -> ZonedInstant {
        instant
    }
}

extension TimeSource where Self == SystemTimeSource {

    public static func system() -> SystemTimeSource {
        SystemTimeSource()
    }

    public static func systemInZone(_ zone: TimeZone) -> SystemTimeSource {
        SystemTimeSource(timeZone: zone)
    }
}

extension TimeSource where Self == FixedTimeSource {

    public static func fixed(_ instant: ZonedInstant) -> FixedTimeSource {
        FixedTimeSource(instant)
    }
}
