package android.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.font.TextLayout;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Linux shim: android.graphics.Canvas over a bound java.awt.Graphics2D. The
 * linux runtime binds one Graphics2D per frame, then ClockRenderer runs
 * unmodified against this surface. save()/restoreToCount() emulate the Android
 * matrix-stack semantics the renderer relies on.
 */
public final class Canvas {

    private Graphics2D g;
    private int width;
    private int height;
    private final Deque<AffineTransform> stack = new ArrayDeque<>();

    public Canvas() {
    }

    /** Bind the per-frame target surface and reset the matrix stack. */
    public void bind(Graphics2D graphics, int w, int h) {
        g = graphics;
        width = w;
        height = h;
        stack.clear();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public int save() {
        stack.push(g.getTransform());
        return stack.size();
    }

    public void restoreToCount(int count) {
        while (!stack.isEmpty() && stack.size() >= count) {
            g.setTransform(stack.pop());
        }
    }

    public void translate(float dx, float dy) {
        g.translate(dx, dy);
    }

    public void scale(float sx, float sy) {
        g.scale(sx, sy);
    }

    public void scale(float sx, float sy, float px, float py) {
        g.translate(px, py);
        g.scale(sx, sy);
        g.translate(-px, -py);
    }

    public void drawColor(int color) {
        // Android semantics: drawColor ignores the current matrix and fills the
        // whole bound surface in device space. ClockView's burn-in translation
        // relies on this ("the background fill inside render() ignores the
        // matrix"), so a shifted composition never exposes an unpainted edge.
        AffineTransform saved = g.getTransform();
        g.setTransform(new AffineTransform());
        g.setColor(new Color(color, true));
        g.fillRect(0, 0, width, height);
        g.setTransform(saved);
    }

    public void drawLine(float x1, float y1, float x2, float y2, Paint paint) {
        g.setColor(new Color(paint.argb(), true));
        g.setStroke(stroke(paint));
        g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        Shape s = new Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f);
        paintShape(s, paint);
    }

    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        // RoundRectangle2D takes arc diameters; Android takes radii.
        Shape s = new RoundRectangle2D.Float(rect.left, rect.top, rect.width(), rect.height(),
                rx * 2f, ry * 2f);
        paintShape(s, paint);
    }

    public void drawText(String text, float x, float y, Paint paint) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float advance = paint.measureText(text);
        float dx;
        switch (paint.getTextAlign()) {
            case CENTER:
                dx = x - advance / 2f;
                break;
            case RIGHT:
                dx = x - advance;
                break;
            default:
                dx = x;
                break;
        }
        g.setColor(new Color(paint.argb(), true));
        if (paint.getLetterSpacing() != 0f) {
            g.drawString(paint.attributed(text).getIterator(), dx, y);
        } else {
            g.setFont(paint.awtFont());
            g.drawString(text, dx, y);
        }
        if (paint.isFakeBoldText()) {
            TextLayout layout =
                    new TextLayout(paint.attributed(text).getIterator(), Paint.frc());
            Shape outline = layout.getOutline(new AffineTransform(1f, 0f, 0f, 1f, dx, y));
            g.setStroke(boldEmphasis(paint));
            g.draw(outline);
        }
    }

    private static Stroke boldEmphasis(Paint paint) {
        float emphasis = Math.max(0.5f, paint.getTextSize() / 48f);
        return new BasicStroke(emphasis, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private void paintShape(Shape shape, Paint paint) {
        g.setColor(new Color(paint.argb(), true));
        Paint.Style style = paint.getStyle();
        if (style == Paint.Style.FILL || style == Paint.Style.FILL_AND_STROKE) {
            g.fill(shape);
        }
        if (style == Paint.Style.STROKE || style == Paint.Style.FILL_AND_STROKE) {
            g.setStroke(stroke(paint));
            g.draw(shape);
        }
    }

    private static BasicStroke stroke(Paint paint) {
        int cap = paint.getStrokeCap() == Paint.Cap.ROUND
                ? BasicStroke.CAP_ROUND
                : paint.getStrokeCap() == Paint.Cap.SQUARE
                        ? BasicStroke.CAP_SQUARE
                        : BasicStroke.CAP_BUTT;
        float w = paint.getStrokeWidth() <= 0f ? 1f : paint.getStrokeWidth();
        return new BasicStroke(w, cap, BasicStroke.JOIN_MITER);
    }
}
