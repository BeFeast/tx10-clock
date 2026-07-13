package com.befeast.tx10clock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the accepted analog + digital clock onto a {@link Canvas}.
 *
 * <p>The layout is the operator-accepted geometry: an elegant, minimal analog
 * face on the left and a maximally large digital time on the right, all over
 * pure black. The analog second hand sweeps continuously (its angle uses the
 * sub-second nanos), while the digital seconds stay visible in a smaller second
 * line under the large {@code hours:minutes}. The default readout is 12-hour
 * with a small AM/PM marker and an English compact date.
 *
 * <p>This class is a pure function of ({@link ClockConfig}, size,
 * {@link ZonedDateTime}) &rarr; pixels, holding no mutable frame state beyond
 * reusable {@link Paint} objects. That purity is what lets the golden harness
 * draw it offscreen into a fixed-size {@code ARGB_8888} bitmap and diff the
 * result deterministically.
 */
public final class ClockRenderer {

    private final ClockConfig config;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();

    public ClockRenderer(ClockConfig config) {
        this.config = config;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    public ClockConfig config() {
        return config;
    }

    /**
     * Render one frame. Safe to call repeatedly with any size; all geometry is
     * derived from {@code width}/{@code height}.
     */
    public void render(Canvas canvas, int width, int height, ZonedDateTime now) {
        canvas.drawColor(config.backgroundColor);

        // Analog face: centred in the left band, sized to fill it with margin.
        final float cx = width * 0.255f;
        final float cy = height * 0.5f;
        final float faceRadius = Math.min(width * 0.22f, height * 0.405f);

        drawRing(canvas, cx, cy, faceRadius);
        drawTicks(canvas, cx, cy, faceRadius);
        drawHands(canvas, cx, cy, faceRadius, now);
        drawHub(canvas, cx, cy, faceRadius);

        // Digital readout fills the right band.
        drawDigital(canvas, width, height, now);
    }

    /** A barely-there ring gives the minimal face structure without a heavy disk. */
    private void drawRing(Canvas canvas, float cx, float cy, float r) {
        stroke.setColor(config.faceColor);
        stroke.setStrokeWidth(r * 0.006f);
        canvas.drawCircle(cx, cy, r, stroke);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float r) {
        final float outer = r * 0.99f;
        for (int i = 0; i < 60; i++) {
            final boolean hour = (i % 5) == 0;
            stroke.setColor(config.tickColor);
            stroke.setAlpha(hour ? 0xFF : 0x70);
            stroke.setStrokeWidth(hour ? r * 0.018f : r * 0.006f);
            final float inner = hour ? r * 0.86f : r * 0.94f;

            final double a = Math.toRadians(i * 6.0);
            final float sin = (float) Math.sin(a);
            final float cos = (float) Math.cos(a);
            canvas.drawLine(
                    cx + sin * inner, cy - cos * inner,
                    cx + sin * outer, cy - cos * outer,
                    stroke);
        }
        stroke.setAlpha(0xFF);
    }

    private void drawHands(Canvas canvas, float cx, float cy, float r, ZonedDateTime now) {
        final int hour = now.getHour() % 12;
        final int minute = now.getMinute();
        // Continuous seconds (integer + nanos) give the second hand a smooth sweep.
        final double second = now.getSecond() + now.getNano() / 1_000_000_000.0;

        final double hourAngle = Math.toRadians((hour + minute / 60.0 + second / 3600.0) * 30.0);
        final double minuteAngle = Math.toRadians((minute + second / 60.0) * 6.0);
        final double secondAngle = Math.toRadians(second * 6.0);

        stroke.setColor(config.hourHandColor);
        stroke.setStrokeWidth(r * 0.042f);
        drawHand(canvas, cx, cy, hourAngle, r * 0.52f, r * 0.06f);

        stroke.setColor(config.minuteHandColor);
        stroke.setStrokeWidth(r * 0.028f);
        drawHand(canvas, cx, cy, minuteAngle, r * 0.80f, r * 0.06f);

        if (config.showSeconds) {
            stroke.setColor(config.secondHandColor);
            stroke.setStrokeWidth(r * 0.012f);
            drawHand(canvas, cx, cy, secondAngle, r * 0.90f, r * 0.22f);
        }
    }

    /** Draws a hand of {@code length} that overshoots the hub by {@code tail}. */
    private void drawHand(Canvas canvas, float cx, float cy, double angle,
                          float length, float tail) {
        final float sin = (float) Math.sin(angle);
        final float cos = (float) Math.cos(angle);
        canvas.drawLine(
                cx - sin * tail, cy + cos * tail,
                cx + sin * length, cy - cos * length,
                stroke);
    }

    private void drawHub(Canvas canvas, float cx, float cy, float r) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(config.secondHandColor);
        canvas.drawCircle(cx, cy, r * 0.035f, fill);
        fill.setColor(config.backgroundColor);
        canvas.drawCircle(cx, cy, r * 0.016f, fill);
    }

    /**
     * The right-hand digital block: a maximally large {@code hours:minutes}
     * sized to the right band, with a small AM/PM marker above (12-hour only),
     * the seconds in a smaller line below, and the compact date beneath that.
     * The whole stack is centred vertically in the frame.
     */
    private void drawDigital(Canvas canvas, int width, int height, ZonedDateTime now) {
        final float panelCx = width * 0.735f;
        final float availWidth = width * 0.46f;

        // Fit the large time to the available width using the widest reference
        // ("00:00") so the block never resizes as the digits change.
        text.setFakeBoldText(true);
        text.setTextSize(100f);
        final float refWidth = text.measureText("00:00");
        final float timeSize = 100f * availWidth / refWidth;

        final List<Line> lines = new ArrayList<>(4);
        if (!config.use24Hour) {
            lines.add(new Line(ClockFormat.amPm(now), timeSize * 0.20f,
                    config.dateColor, true));
        }
        lines.add(new Line(ClockFormat.hoursMinutes(now, config.use24Hour), timeSize,
                config.digitalColor, true));
        if (config.showSeconds) {
            lines.add(new Line(ClockFormat.seconds(now), timeSize * 0.34f,
                    config.secondHandColor, true));
        }
        lines.add(new Line(ClockFormat.date(now), timeSize * 0.15f,
                config.dateColor, false));

        drawCenteredStack(canvas, panelCx, height * 0.5f, lines, timeSize * 0.10f);
    }

    /** Vertically stacks {@code lines}, centred as a block on {@code centerY}. */
    private void drawCenteredStack(Canvas canvas, float cx, float centerY,
                                   List<Line> lines, float gap) {
        float total = 0f;
        final float[] heights = new float[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            applyLinePaint(lines.get(i));
            final Line line = lines.get(i);
            text.getTextBounds(line.text, 0, line.text.length(), textBounds);
            heights[i] = textBounds.height();
            total += heights[i];
            if (i > 0) {
                total += gap;
            }
        }

        float top = centerY - total / 2f;
        for (int i = 0; i < lines.size(); i++) {
            final Line line = lines.get(i);
            applyLinePaint(line);
            text.getTextBounds(line.text, 0, line.text.length(), textBounds);
            // textBounds.top is negative (extent above the baseline).
            canvas.drawText(line.text, cx, top - textBounds.top, text);
            top += heights[i] + gap;
        }
    }

    private void applyLinePaint(Line line) {
        text.setColor(line.color);
        text.setTextSize(line.size);
        text.setFakeBoldText(line.bold);
    }

    /** One line of the digital stack. */
    private static final class Line {
        final String text;
        final float size;
        final int color;
        final boolean bold;

        Line(String text, float size, int color, boolean bold) {
            this.text = text;
            this.size = size;
            this.color = color;
            this.bold = bold;
        }
    }
}
