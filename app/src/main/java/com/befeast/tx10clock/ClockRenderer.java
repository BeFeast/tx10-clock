package com.befeast.tx10clock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.time.ZonedDateTime;

/**
 * Draws the analog + digital clock onto a {@link Canvas}.
 *
 * <p>This class is the production renderer. It is a pure function of
 * ({@link ClockConfig}, size, {@link ZonedDateTime}) &rarr; pixels, holding no
 * mutable frame state beyond reusable {@link Paint} objects. That purity is
 * what lets the golden harness draw it offscreen into a fixed-size
 * {@code ARGB_8888} bitmap and diff the result deterministically.
 */
public final class ClockRenderer {

    private final ClockConfig config;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

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

        final float faceRadius = Math.min(width, height) * 0.33f;
        final float cx = width * 0.5f;
        final float cy = height * 0.40f;

        drawFace(canvas, cx, cy, faceRadius);
        drawTicks(canvas, cx, cy, faceRadius);
        drawHands(canvas, cx, cy, faceRadius, now);
        drawHub(canvas, cx, cy, faceRadius);
        drawDigital(canvas, width, height, now);
    }

    private void drawFace(Canvas canvas, float cx, float cy, float r) {
        fill.setColor(config.faceColor);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, r, fill);

        stroke.setColor(config.tickColor);
        stroke.setStrokeWidth(r * 0.02f);
        canvas.drawCircle(cx, cy, r, stroke);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float r) {
        stroke.setColor(config.tickColor);
        for (int i = 0; i < 60; i++) {
            final boolean hour = (i % 5) == 0;
            final float inner = hour ? r * 0.84f : r * 0.90f;
            final float outer = r * 0.96f;
            stroke.setStrokeWidth(hour ? r * 0.025f : r * 0.010f);

            final double a = Math.toRadians(i * 6.0);
            final float sin = (float) Math.sin(a);
            final float cos = (float) Math.cos(a);
            canvas.drawLine(
                    cx + sin * inner, cy - cos * inner,
                    cx + sin * outer, cy - cos * outer,
                    stroke);
        }
    }

    private void drawHands(Canvas canvas, float cx, float cy, float r, ZonedDateTime now) {
        final int hour = now.getHour() % 12;
        final int minute = now.getMinute();
        final int second = now.getSecond();

        final double hourAngle = Math.toRadians((hour + minute / 60.0) * 30.0);
        final double minuteAngle = Math.toRadians((minute + second / 60.0) * 6.0);
        final double secondAngle = Math.toRadians(second * 6.0);

        stroke.setColor(config.hourHandColor);
        stroke.setStrokeWidth(r * 0.045f);
        drawHand(canvas, cx, cy, hourAngle, r * 0.52f, r * 0.12f);

        stroke.setColor(config.minuteHandColor);
        stroke.setStrokeWidth(r * 0.030f);
        drawHand(canvas, cx, cy, minuteAngle, r * 0.78f, r * 0.16f);

        if (config.showSeconds) {
            stroke.setColor(config.secondHandColor);
            stroke.setStrokeWidth(r * 0.015f);
            drawHand(canvas, cx, cy, secondAngle, r * 0.86f, r * 0.20f);
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
        canvas.drawCircle(cx, cy, r * 0.045f, fill);
        fill.setColor(config.backgroundColor);
        canvas.drawCircle(cx, cy, r * 0.020f, fill);
    }

    private void drawDigital(Canvas canvas, int width, int height, ZonedDateTime now) {
        final float timeSize = height * 0.135f;
        final float dateSize = height * 0.052f;
        final float cx = width * 0.5f;

        text.setColor(config.digitalColor);
        text.setTextSize(timeSize);
        text.setFakeBoldText(true);
        canvas.drawText(ClockFormat.time(now, config), cx, height * 0.88f, text);

        text.setColor(config.dateColor);
        text.setTextSize(dateSize);
        text.setFakeBoldText(false);
        canvas.drawText(ClockFormat.date(now), cx, height * 0.96f, text);
    }
}
