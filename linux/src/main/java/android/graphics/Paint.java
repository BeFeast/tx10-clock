package android.graphics;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;

/**
 * Linux shim: the android.graphics.Paint state ClockRenderer uses, resolved
 * over java.awt. Metric conventions follow Android — {@link FontMetrics#ascent}
 * is negative, {@code descent} positive — so renderer baseline math is
 * unchanged. {@link #letterSpacing} is in em units like Android and maps to
 * {@link TextAttribute#TRACKING}; {@code textScaleX} maps to a font transform.
 */
public final class Paint {

    public static final int ANTI_ALIAS_FLAG = 0x01;
    public static final int SUBPIXEL_TEXT_FLAG = 0x02;

    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    public enum Cap { BUTT, ROUND, SQUARE }

    public enum Align { LEFT, CENTER, RIGHT }

    /** Android-sign-convention metrics: {@code ascent} is negative. */
    public static final class FontMetrics {
        public float ascent;
        public float descent;
        public float leading;
        public float top;
        public float bottom;
    }

    private static final Graphics2D SCRATCH =
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
    private static final FontRenderContext FRC = SCRATCH.getFontRenderContext();

    private int color = 0xFF000000;
    private int alpha = 255;
    private Style style = Style.FILL;
    private Cap cap = Cap.BUTT;
    private float strokeWidth = 1f;
    private Typeface typeface = Typeface.create("sans-serif", Typeface.NORMAL);
    private boolean fakeBoldText;
    private float textSize = 12f;
    private float textScaleX = 1f;
    private float letterSpacing = 0f;
    private Align textAlign = Align.LEFT;

    public Paint() {
    }

    public Paint(int flags) {
        // ANTI_ALIAS / SUBPIXEL_TEXT are always-on in the shim via rendering hints.
    }

    public void setColor(int c) {
        color = c;
    }

    public void setAlpha(int a) {
        alpha = a;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public Style getStyle() {
        return style;
    }

    public void setStrokeCap(Cap cap) {
        this.cap = cap;
    }

    public Cap getStrokeCap() {
        return cap;
    }

    public void setStrokeWidth(float width) {
        strokeWidth = width;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setTypeface(Typeface typeface) {
        this.typeface = typeface == null
                ? Typeface.create("sans-serif", Typeface.NORMAL)
                : typeface;
    }

    public Typeface getTypeface() {
        return typeface;
    }

    public void setFakeBoldText(boolean fakeBoldText) {
        this.fakeBoldText = fakeBoldText;
    }

    public boolean isFakeBoldText() {
        return fakeBoldText;
    }

    public void setTextSize(float textSize) {
        this.textSize = textSize;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextScaleX(float textScaleX) {
        this.textScaleX = textScaleX;
    }

    public float getTextScaleX() {
        return textScaleX;
    }

    /** Letter spacing in em units, Android semantics (may be negative). */
    public void setLetterSpacing(float letterSpacing) {
        this.letterSpacing = letterSpacing;
    }

    public float getLetterSpacing() {
        return letterSpacing;
    }

    public void setTextAlign(Align align) {
        textAlign = align;
    }

    public Align getTextAlign() {
        return textAlign;
    }

    /** ARGB with the paint alpha channel applied, Android-style. */
    int argb() {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** The derived AWT font: family + horizontal scale + tracking + size. */
    Font awtFont() {
        Font base = typeface.awtBase();
        Map<TextAttribute, Object> attrs = new HashMap<>();
        if (textScaleX != 1f) {
            attrs.put(TextAttribute.TRANSFORM,
                    AffineTransform.getScaleInstance(textScaleX, 1d));
        }
        if (letterSpacing != 0f) {
            attrs.put(TextAttribute.TRACKING, (double) letterSpacing);
        }
        Font f = attrs.isEmpty() ? base : base.deriveFont(attrs);
        f = f.deriveFont(textSize);
        if (typeface.usesSyntheticBold()) {
            f = f.deriveFont(Font.BOLD);
        }
        return f;
    }

    static FontRenderContext frc() {
        return FRC;
    }

    AttributedString attributed(String text) {
        AttributedString as = new AttributedString(text);
        as.addAttribute(TextAttribute.FONT, awtFont());
        return as;
    }

    /** Android-style metrics with negative ascent. */
    public FontMetrics getFontMetrics() {
        java.awt.FontMetrics fm = SCRATCH.getFontMetrics(awtFont());
        FontMetrics m = new FontMetrics();
        m.ascent = -fm.getAscent();
        m.descent = fm.getDescent();
        m.leading = fm.getLeading();
        m.top = m.ascent - m.leading;
        m.bottom = m.descent;
        return m;
    }

    /** Floating-point advance including tracking and horizontal scale. */
    public float measureText(String text) {
        if (text.isEmpty()) {
            return 0f;
        }
        return new TextLayout(attributed(text).getIterator(), FRC).getAdvance();
    }
}
