import CoreGraphics
import CoreText
import Foundation
import UIKit

enum TextAlign {
    case left
    case center
    case right
}

struct TextStyle {
    let font: UIFont
    let color: CGColor
    /// Letter spacing in design pixels. Java sets Paint.setLetterSpacing in em
    /// (px / textSize); an NSKern value in points at that font size collapses
    /// back to the same pixel amount in design space.
    let kern: CGFloat
    let align: TextAlign
}

/// Font metrics in the Android Paint.FontMetrics sign convention: ascent is
/// NEGATIVE, descent positive. Formulas transliterated from Java depend on it.
struct FontMetricsA {
    let ascent: CGFloat
    let descent: CGFloat

    init(font: UIFont) {
        let ctFont = font as CTFont
        ascent = -CTFontGetAscent(ctFont)
        descent = CTFontGetDescent(ctFont)
    }
}

private let srgbColorSpace = CGColorSpace(name: CGColorSpace.sRGB)!

/// Packed 0xAARRGGBB (the Android colour int layout) to an explicit-sRGB CGColor.
func srgbColor(_ argb: UInt32) -> CGColor {
    let components: [CGFloat] = [
        CGFloat((argb >> 16) & 0xFF) / 255,
        CGFloat((argb >> 8) & 0xFF) / 255,
        CGFloat(argb & 0xFF) / 255,
        CGFloat((argb >> 24) & 0xFF) / 255,
    ]
    return CGColor(colorSpace: srgbColorSpace, components: components)!
}

/// Paint.setAlpha semantics: replace the packed colour's alpha byte.
func srgbColor(_ argb: UInt32, alpha: UInt32) -> CGColor {
    srgbColor((argb & 0x00FF_FFFF) | ((alpha & 0xFF) << 24))
}

/// The single text path for every Canvas.drawText/measureText call site.
enum DrawText {

    /// Android Paint.measureText parity: letter spacing sits between glyphs
    /// only, while a kerned CTLine advance carries one extra trailing kern.
    static func measure(_ text: String, style: TextStyle) -> CGFloat {
        guard !text.isEmpty else { return 0 }
        let line = makeLine(text, style: style)
        let advance = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        return style.kern != 0 ? advance - style.kern : advance
    }

    /// Draw `text` with its BASELINE at `y`, exactly like Canvas.drawText.
    static func draw(_ ctx: CGContext, _ text: String, x: CGFloat, y: CGFloat,
                     style: TextStyle) {
        guard !text.isEmpty else { return }
        var originX = x
        switch style.align {
        case .left:
            break
        case .center:
            originX -= measure(text, style: style) / 2
        case .right:
            originX -= measure(text, style: style)
        }
        // The UIKit context is flipped; without this text draws mirrored.
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: originX, y: y)
        CTLineDraw(makeLine(text, style: style), ctx)
    }

    private static func makeLine(_ text: String, style: TextStyle) -> CTLine {
        var attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): style.font as CTFont,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): style.color,
        ]
        if style.kern != 0 {
            attributes[NSAttributedString.Key(kCTKernAttributeName as String)] =
                NSNumber(value: Double(style.kern))
        }
        return CTLineCreateWithAttributedString(
            NSAttributedString(string: text, attributes: attributes))
    }
}
