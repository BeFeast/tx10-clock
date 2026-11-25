import CoreGraphics
import CoreText
import Foundation
import TX10ClockCore
import UIKit

/// Native CoreGraphics implementation of the accepted clock + hybrid-calendar
/// composition. Port of the Android ClockRenderer; all geometry stays in the
/// binding 1280x720 design coordinates.
final class ClockRenderer {

    private static let DESIGN_WIDTH: CGFloat = 1280
    private static let DESIGN_HEIGHT: CGFloat = 720
    private static let ANALOG_X: CGFloat = 318
    private static let ANALOG_Y: CGFloat = 338
    private static let DIGITAL_LEFT: CGFloat = 666
    private static let DIGITAL_RIGHT: CGFloat = 1214
    private static let DIGITAL_GROUP_SCALE: CGFloat = 1.025
    private static let DIGITAL_GROUP_PIVOT_X: CGFloat = (DIGITAL_LEFT + DIGITAL_RIGHT) / 2
    private static let DIGITAL_GROUP_PIVOT_Y: CGFloat = 350
    private static let CALENDAR_TOP: CGFloat = 357
    private static let CALENDAR_MUTED: UInt32 = 0xFF636366
    private static let CALENDAR_ADJACENT: UInt32 = 0xFF48484A
    private static let CALENDAR_CARD: UInt32 = 0xFF17120A
    static let CALENDAR_ROLE_ADJACENT = 0
    static let CALENDAR_ROLE_CURRENT_MONTH = 1
    static let CALENDAR_ROLE_TODAY = 2
    private static let WEEKDAY_INITIALS = ["S", "M", "T", "W", "T", "F", "S"]
    // java pattern "MMMM uuuu": DateFormatter has no era-less 'u' year, and
    // 'yyyy' is identical for all AD dates this clock can display.
    private static let MONTH_YEAR = makeFormatter("MMMM yyyy")
    private static let WEEKDAY = makeFormatter("EEEE")
    private static let POSIX = Locale(identifier: "en_US_POSIX")

    let config: ClockConfig

    init(config: ClockConfig) {
        self.config = config
    }

    /// Draw one frame, letterboxed from the binding 1280x720 design surface.
    func render(_ ctx: CGContext, width: CGFloat, height: CGFloat, now: ZonedInstant) {
        ctx.setFillColor(srgbColor(config.backgroundColor))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        guard width > 0, height > 0 else {
            return
        }

        let scale = min(width / Self.DESIGN_WIDTH, height / Self.DESIGN_HEIGHT)
        let offsetX = (width - Self.DESIGN_WIDTH * scale) / 2
        let offsetY = (height - Self.DESIGN_HEIGHT * scale) / 2

        ctx.saveGState()
        ctx.translateBy(x: offsetX, y: offsetY)
        ctx.scaleBy(x: scale, y: scale)
        drawTicks(ctx)
        drawNumerals(ctx)
        drawHands(ctx, now)
        drawHub(ctx)
        ctx.saveGState()
        ctx.translateBy(x: Self.DIGITAL_GROUP_PIVOT_X, y: Self.DIGITAL_GROUP_PIVOT_Y)
        ctx.scaleBy(x: Self.DIGITAL_GROUP_SCALE, y: Self.DIGITAL_GROUP_SCALE)
        ctx.translateBy(x: -Self.DIGITAL_GROUP_PIVOT_X, y: -Self.DIGITAL_GROUP_PIVOT_Y)
        drawDigital(ctx, now)
        ctx.restoreGState()
        ctx.restoreGState()
    }

    private func drawTicks(_ ctx: CGContext) {
        for i in 0..<60 {
            let major = i % 5 == 0
            let angle = toRadians(Double(i) * 6.0)
            let sinA = CGFloat(sin(angle))
            let cosA = CGFloat(cos(angle))
            let inner: CGFloat = major ? 243 : 253
            let outer: CGFloat = 267

            let color = srgbColor(major ? config.digitalColor : config.tickColor,
                                  alpha: major ? 255 : 184)
            strokeLine(
                ctx,
                from: CGPoint(x: Self.ANALOG_X + sinA * inner, y: Self.ANALOG_Y - cosA * inner),
                to: CGPoint(x: Self.ANALOG_X + sinA * outer, y: Self.ANALOG_Y - cosA * outer),
                width: major ? 6 : 3,
                color: color)
        }
    }

    private func drawNumerals(_ ctx: CGContext) {
        let style = TextStyle(
            font: .systemFont(ofSize: 48, weight: .regular),
            color: srgbColor(config.digitalColor),
            kern: 0,
            align: .center)

        let metrics = FontMetricsA(font: style.font)
        let baselineOffset = -(metrics.ascent + metrics.descent) / 2
        for hour in 1...12 {
            let index = hour % 12
            let angle = toRadians(Double(index) * 30.0)
            let x = Self.ANALOG_X + CGFloat(sin(angle)) * 216
            let y = Self.ANALOG_Y - CGFloat(cos(angle)) * 216
            DrawText.draw(ctx, String(hour), x: x, y: y + baselineOffset, style: style)
        }
    }

    private func drawHands(_ ctx: CGContext, _ now: ZonedInstant) {
        let seconds = Double(now.second) + Double(now.nano) / 1_000_000_000.0
        let minutes = Double(now.minute) + seconds / 60.0
        let hours = Double(now.hour % 12) + minutes / 60.0

        drawHand(ctx, angle: toRadians(hours * 30.0), length: 116, tail: 19,
                 width: 18, color: config.hourHandColor)
        drawHand(ctx, angle: toRadians(minutes * 6.0), length: 180, tail: 24,
                 width: 13, color: config.minuteHandColor)
        if config.showSeconds {
            drawHand(ctx, angle: toRadians(seconds * 6.0), length: 216, tail: 30,
                     width: 4, color: config.secondHandColor)
        }
    }

    private func drawHand(_ ctx: CGContext, angle: Double, length: CGFloat, tail: CGFloat,
                          width: CGFloat, color: UInt32) {
        let sinA = CGFloat(sin(angle))
        let cosA = CGFloat(cos(angle))
        strokeLine(
            ctx,
            from: CGPoint(x: Self.ANALOG_X - sinA * tail, y: Self.ANALOG_Y + cosA * tail),
            to: CGPoint(x: Self.ANALOG_X + sinA * length, y: Self.ANALOG_Y - cosA * length),
            width: width,
            color: srgbColor(color))
    }

    private func drawHub(_ ctx: CGContext) {
        fillCircle(ctx, center: CGPoint(x: Self.ANALOG_X, y: Self.ANALOG_Y),
                   radius: 11, color: srgbColor(config.secondHandColor))
        fillCircle(ctx, center: CGPoint(x: Self.ANALOG_X, y: Self.ANALOG_Y),
                   radius: 4, color: srgbColor(config.digitalColor))
    }

    private func drawDigital(_ ctx: CGContext, _ now: ZonedInstant) {
        let main = ClockFormat.main(now, use24Hour: config.use24Hour)
        let mainStyle = mainTextStyle()
        DrawText.draw(ctx, main, x: Self.DIGITAL_LEFT, y: 311, style: mainStyle)

        let metaX = Self.DIGITAL_LEFT + DrawText.measure(main, style: mainStyle) + 16
        if config.showSeconds {
            DrawText.draw(ctx, ClockFormat.seconds(now), x: metaX, y: 188,
                          style: metaTextStyle(color: config.secondHandColor))
        }
        if !config.use24Hour {
            DrawText.draw(ctx, ClockFormat.amPm(now), x: metaX, y: 309,
                          style: metaTextStyle(color: config.dateColor))
        }

        if config.showDate {
            drawHybridCalendar(ctx, now)
        }
    }

    private func mainTextStyle() -> TextStyle {
        let scale = Self.sizeScale(config.digitalSizePercent)
        // java: setLetterSpacing(-8f / 182f) em at text size 182 * scale.
        return TextStyle(
            font: .systemFont(ofSize: 182 * scale, weight: .light),
            color: srgbColor(config.digitalColor),
            kern: -8 * scale,
            align: .left)
    }

    private func metaTextStyle(color: UInt32) -> TextStyle {
        let scale = Self.sizeScale(config.secondarySizePercent)
        // java: setLetterSpacing(2f / 36f) em at text size 36 * scale.
        return TextStyle(
            font: .systemFont(ofSize: 36 * scale, weight: .medium),
            color: srgbColor(color),
            kern: 2 * scale,
            align: .left)
    }

    private func drawHybridCalendar(_ ctx: CGContext, _ now: ZonedInstant) {
        strokeLine(ctx,
                   from: CGPoint(x: Self.DIGITAL_LEFT, y: Self.CALENDAR_TOP),
                   to: CGPoint(x: Self.DIGITAL_RIGHT, y: Self.CALENDAR_TOP),
                   width: 1, color: srgbColor(0xFF2C2C2E))

        DrawText.draw(ctx, format(Self.MONTH_YEAR, now), x: Self.DIGITAL_LEFT, y: 391,
                      style: calendarStyle(size: 17, bold: true, color: config.digitalColor,
                                           letterSpacingPx: 3, align: .left))

        DrawText.draw(ctx, "DAY \(dayOfYear(now))", x: Self.DIGITAL_RIGHT, y: 391,
                      style: calendarStyle(size: 12, bold: true, color: Self.CALENDAR_MUTED,
                                           letterSpacingPx: 2, align: .right))

        let contentTop: CGFloat = 408
        let month = YearMonth(year: now.year, month: now.month)
        let contentBottom = contentTop + 21 + 6 * 20
        let cardRight: CGFloat = 778
        let card = CGRect(x: Self.DIGITAL_LEFT, y: contentTop,
                          width: cardRight - Self.DIGITAL_LEFT,
                          height: contentBottom - contentTop)
        let cardPath = CGPath(roundedRect: card, cornerWidth: 14, cornerHeight: 14,
                              transform: nil)
        ctx.addPath(cardPath)
        ctx.setFillColor(srgbColor(Self.CALENDAR_CARD))
        ctx.fillPath()
        ctx.addPath(cardPath)
        ctx.setStrokeColor(srgbColor(config.secondHandColor))
        ctx.setLineWidth(1)
        ctx.setLineCap(.round)
        ctx.strokePath()

        let cardCenter = (Self.DIGITAL_LEFT + cardRight) / 2
        DrawText.draw(ctx, format(Self.WEEKDAY, now), x: cardCenter, y: 451,
                      style: calendarStyle(size: 9, bold: true, color: config.secondHandColor,
                                           letterSpacingPx: 1.4, align: .center))
        DrawText.draw(ctx, String(now.dayOfMonth), x: cardCenter, y: 502,
                      style: calendarStyle(size: 48, bold: false, color: config.secondHandColor,
                                           letterSpacingPx: 0, align: .center))

        drawMiniMonth(ctx, now, month: month, left: 800, top: contentTop,
                      width: Self.DIGITAL_RIGHT - 800)
    }

    private func drawMiniMonth(_ ctx: CGContext, _ now: ZonedInstant, month: YearMonth,
                               left: CGFloat, top: CGFloat, width: CGFloat) {
        let columnWidth = width / 7
        let headerStyle = calendarStyle(size: 11, bold: true, color: Self.CALENDAR_MUTED,
                                        letterSpacingPx: 1, align: .center)
        for column in 0..<7 {
            DrawText.draw(ctx, Self.WEEKDAY_INITIALS[column],
                          x: left + columnWidth * (CGFloat(column) + 0.5),
                          y: top + 13, style: headerStyle)
        }

        let firstRowTop = top + 21
        let dates = Self.calendarDates(month: month, timeZone: now.timeZone)
        let todayDate = GridDate(year: now.year, month: now.month, day: now.dayOfMonth)
        for cell in 0..<dates.count {
            let date = dates[cell]
            let column = cell % 7
            let row = cell / 7
            let centerX = left + columnWidth * (CGFloat(column) + 0.5)
            let centerY = firstRowTop + CGFloat(row) * 20 + 10
            let role = Self.calendarCellRole(date, displayedMonth: month, today: todayDate)
            if role == Self.CALENDAR_ROLE_TODAY {
                fillCircle(ctx, center: CGPoint(x: centerX, y: centerY), radius: 10.5,
                           color: srgbColor(config.secondHandColor))
            }
            let color = role == Self.CALENDAR_ROLE_TODAY
                ? config.backgroundColor
                : role == Self.CALENDAR_ROLE_ADJACENT ? Self.CALENDAR_ADJACENT : config.dateColor
            let style = calendarStyle(size: 14, bold: role == Self.CALENDAR_ROLE_TODAY,
                                      color: color, letterSpacingPx: 0, align: .center)
            DrawText.draw(ctx, String(date.day), x: centerX,
                          y: Self.centeredBaseline(centerY, font: style.font), style: style)
        }
    }

    /// Port of java.time.YearMonth as used by the grid math.
    struct YearMonth: Equatable {
        let year: Int
        let month: Int
    }

    /// Port of java.time.LocalDate as used by the grid roles: a zone-free
    /// calendar date compared field-by-field.
    struct GridDate: Equatable {
        let year: Int
        let month: Int
        let day: Int
    }

    /// Return the stable Sunday-first 7x6 date matrix for a displayed month.
    static func calendarDates(month: YearMonth, timeZone: TimeZone) -> [GridDate] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        // Noon keeps day arithmetic stable in zones where a DST jump removes
        // midnight from the calendar day.
        let first = calendar.date(
            from: DateComponents(year: month.year, month: month.month, day: 1, hour: 12))!
        // Foundation weekday is Sun=1..Sat=7; java DayOfWeek is Mon=1..Sun=7.
        let dayOfWeekMon1 = ((calendar.component(.weekday, from: first) + 5) % 7) + 1
        let leadingDays = dayOfWeekMon1 % 7
        return (0..<42).map { cell in
            let date = calendar.date(byAdding: .day, value: cell - leadingDays, to: first)!
            let parts = calendar.dateComponents([.year, .month, .day], from: date)
            return GridDate(year: parts.year!, month: parts.month!, day: parts.day!)
        }
    }

    /// Adjacent-month cells are always muted and can never receive today's accent.
    static func calendarCellRole(_ date: GridDate, displayedMonth: YearMonth,
                                 today: GridDate) -> Int {
        if YearMonth(year: date.year, month: date.month) != displayedMonth {
            return CALENDAR_ROLE_ADJACENT
        }
        return date == today ? CALENDAR_ROLE_TODAY : CALENDAR_ROLE_CURRENT_MONTH
    }

    private func calendarStyle(size: CGFloat, bold: Bool, color: UInt32,
                               letterSpacingPx: CGFloat, align: TextAlign) -> TextStyle {
        TextStyle(
            font: .systemFont(ofSize: size, weight: bold ? .bold : .regular),
            color: srgbColor(color),
            kern: letterSpacingPx,
            align: align)
    }

    private static func centeredBaseline(_ centerY: CGFloat, font: UIFont) -> CGFloat {
        let metrics = FontMetricsA(font: font)
        return centerY - (metrics.ascent + metrics.descent) / 2
    }

    /// The multiplier for a bounded (50..100) text-size percentage. The default
    /// 100 yields 1.0, preserving the accepted design size.
    private static func sizeScale(_ percent: Int) -> CGFloat {
        CGFloat(percent) / 100
    }

    private func toRadians(_ degrees: Double) -> Double {
        degrees * .pi / 180
    }

    private func strokeLine(_ ctx: CGContext, from: CGPoint, to: CGPoint,
                            width: CGFloat, color: CGColor) {
        ctx.setStrokeColor(color)
        ctx.setLineWidth(width)
        ctx.setLineCap(.round)
        ctx.move(to: from)
        ctx.addLine(to: to)
        ctx.strokePath()
    }

    private func fillCircle(_ ctx: CGContext, center: CGPoint, radius: CGFloat,
                            color: CGColor) {
        ctx.setFillColor(color)
        ctx.fillEllipse(in: CGRect(x: center.x - radius, y: center.y - radius,
                                   width: radius * 2, height: radius * 2))
    }

    private func format(_ formatter: DateFormatter, _ now: ZonedInstant) -> String {
        formatter.timeZone = now.timeZone
        return formatter.string(from: now.date).uppercased(with: Self.POSIX)
    }

    private func dayOfYear(_ now: ZonedInstant) -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = now.timeZone
        return calendar.ordinality(of: .day, in: .year, for: now.date) ?? 1
    }

    private static func makeFormatter(_ pattern: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = pattern
        return formatter
    }
}
