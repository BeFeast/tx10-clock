import Foundation

/// Pure formatting for the digital time, metadata, and calendar labels.
///
/// The cached formatters are mutated (time zone) on every call, so this type
/// is deliberately not thread-safe; the clock renders on the main thread only,
/// matching the Android renderer's single-threaded use.
public enum ClockFormat {

    private static let main24 = makeFormatter("HH:mm")
    private static let main12 = makeFormatter("h:mm")
    private static let amPmFormatter = makeFormatter("a")
    private static let dateFormatter = makeFormatter("EEE, MMM d")
    private static let secondsFormatter = makeFormatter("ss")

    private static func makeFormatter(_ pattern: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = pattern
        return formatter
    }

    private static func format(_ formatter: DateFormatter, _ now: ZonedInstant) -> String {
        // Date carries no zone, so the ZonedInstant's zone must be re-applied
        // on every call.
        formatter.timeZone = now.timeZone
        return formatter.string(from: now.date)
    }

    /// Large first line, e.g. `10:09` or `22:09`.
    public static func main(_ now: ZonedInstant, use24Hour: Bool) -> String {
        format(use24Hour ? main24 : main12, now)
    }

    /// Compact English date without a year, e.g. `SUN, JUL 12`.
    public static func compactDate(_ now: ZonedInstant) -> String {
        format(dateFormatter, now).uppercased()
    }

    public static func amPm(_ now: ZonedInstant) -> String {
        format(amPmFormatter, now).uppercased()
    }

    public static func seconds(_ now: ZonedInstant) -> String {
        format(secondsFormatter, now)
    }

    /// Legacy compact-line fixture, honouring the compact-date toggle. When
    /// `showDate` is false the date is dropped, leaving the 12-hour AM/PM
    /// marker on its own (and nothing at all in 24-hour form). The orange
    /// seconds field is appended separately. The current renderer draws the
    /// fields in a side metadata column and uses `showDate` for the hybrid
    /// calendar.
    public static func secondaryPrefix(_ now: ZonedInstant, use24Hour: Bool,
                                       showDate: Bool = true) -> String {
        var text = ""
        if !use24Hour {
            text += amPm(now) + " "
        }
        if showDate {
            text += compactDate(now) + " "
        }
        return text
    }

    /// Second-line fixture that also honours the compact-date toggle.
    public static func secondary(_ now: ZonedInstant, use24Hour: Bool,
                                 showSeconds: Bool, showDate: Bool = true) -> String {
        let prefix = secondaryPrefix(now, use24Hour: use24Hour, showDate: showDate)
            .trimmingCharacters(in: .whitespaces)
        if !showSeconds {
            return prefix
        }
        return prefix.isEmpty ? seconds(now) : prefix + " " + seconds(now)
    }

    /// Compatibility helper retained for callers from the initial scaffold.
    public static func time(_ now: ZonedInstant, config: ClockConfig) -> String {
        main(now, use24Hour: config.use24Hour)
    }

    /// Compatibility helper now returns the accepted compact uppercase date.
    public static func date(_ now: ZonedInstant) -> String {
        compactDate(now)
    }
}
