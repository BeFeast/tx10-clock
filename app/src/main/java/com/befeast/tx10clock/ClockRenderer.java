package com.befeast.tx10clock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.time.ZonedDateTime;

/** Native Canvas implementation of visual contract v0.1.0. */
public final class ClockRenderer {

    private static final float DESIGN_WIDTH = 1280f;
    private static final float DESIGN_HEIGHT = 720f;
    private static final float ANALOG_X = 318f;
    private static final float ANALOG_Y = 360f;
    private static final float DIGITAL_X = 941f;

    private final ClockConfig config;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

    public ClockRenderer(ClockConfig config) {
        this.config = config;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setStyle(Paint.Style.FILL);
    }

    public ClockConfig config() {
        return config;
    }

    /** Draw one frame, letterboxed from the binding 1280x720 design surface. */
    public void render(Canvas canvas, int width, int height, ZonedDateTime now) {
        canvas.drawColor(config.backgroundColor);
        if (width <= 0 || height <= 0) {
            return;
        }

        float scale = Math.min(width / DESIGN_WIDTH, height / DESIGN_HEIGHT);
        float offsetX = (width - DESIGN_WIDTH * scale) / 2f;
        float offsetY = (height - DESIGN_HEIGHT * scale) / 2f;

        int save = canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        drawTicks(canvas);
        drawNumerals(canvas);
        drawHands(canvas, now);
        drawHub(canvas);
        drawDigital(canvas, now);
        canvas.restoreToCount(save);
    }

    private void drawTicks(Canvas canvas) {
        for (int i = 0; i < 60; i++) {
            boolean major = i % 5 == 0;
            double angle = Math.toRadians(i * 6.0);
            float sin = (float) Math.sin(angle);
            float cos = (float) Math.cos(angle);
            float inner = major ? 243f : 253f;
            float outer = 267f;

            stroke.setColor(major ? config.digitalColor : config.tickColor);
            stroke.setAlpha(major ? 255 : 184);
            stroke.setStrokeWidth(major ? 6f : 3f);
            canvas.drawLine(
                    ANALOG_X + sin * inner, ANALOG_Y - cos * inner,
                    ANALOG_X + sin * outer, ANALOG_Y - cos * outer,
                    stroke);
        }
        stroke.setAlpha(255);
    }

    private void drawNumerals(Canvas canvas) {
        text.setColor(config.digitalColor);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setFakeBoldText(false);
        text.setTextSize(48f);
        text.setTextScaleX(1f);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics metrics = text.getFontMetrics();
        float baselineOffset = -(metrics.ascent + metrics.descent) / 2f;
        for (int hour = 1; hour <= 12; hour++) {
            int index = hour % 12;
            double angle = Math.toRadians(index * 30.0);
            float x = ANALOG_X + (float) Math.sin(angle) * 216f;
            float y = ANALOG_Y - (float) Math.cos(angle) * 216f;
            canvas.drawText(Integer.toString(hour), x, y + baselineOffset, text);
        }
    }

    private void drawHands(Canvas canvas, ZonedDateTime now) {
        double seconds = now.getSecond() + now.getNano() / 1_000_000_000.0;
        double minutes = now.getMinute() + seconds / 60.0;
        double hours = (now.getHour() % 12) + minutes / 60.0;

        drawHand(canvas, Math.toRadians(hours * 30.0), 116f, 19f,
                18f, config.hourHandColor);
        drawHand(canvas, Math.toRadians(minutes * 6.0), 180f, 24f,
                13f, config.minuteHandColor);
        if (config.showSeconds) {
            drawHand(canvas, Math.toRadians(seconds * 6.0), 216f, 30f,
                    4f, config.secondHandColor);
        }
    }

    private void drawHand(Canvas canvas, double angle, float length, float tail,
                          float width, int color) {
        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);
        stroke.setColor(color);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(width);
        canvas.drawLine(
                ANALOG_X - sin * tail, ANALOG_Y + cos * tail,
                ANALOG_X + sin * length, ANALOG_Y - cos * length,
                stroke);
    }

    private void drawHub(Canvas canvas) {
        fill.setColor(config.secondHandColor);
        canvas.drawCircle(ANALOG_X, ANALOG_Y, 11f, fill);
        fill.setColor(config.backgroundColor);
        canvas.drawCircle(ANALOG_X, ANALOG_Y, 4f, fill);
    }

    private void drawDigital(Canvas canvas, ZonedDateTime now) {
        String main = ClockFormat.main(now, config.use24Hour);
        configureMainText();
        float referenceWidth = text.measureText(config.use24Hour ? "22:09" : "10:09");
        if (referenceWidth > 0f) {
            text.setTextScaleX(535f * sizeScale(config.digitalSizePercent) / referenceWidth);
        }
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(main, DIGITAL_X, 377f, text);

        String prefix = ClockFormat.secondaryPrefix(now, config.use24Hour, config.showDate);
        String seconds = config.showSeconds ? ClockFormat.seconds(now) : "";
        configureSecondaryText();
        float naturalWidth = text.measureText(prefix) + text.measureText(seconds);
        float targetWidth = (config.use24Hour ? 495f : 535f) * sizeScale(config.secondarySizePercent);
        if (naturalWidth > 0f) {
            text.setTextScaleX(targetWidth / naturalWidth);
        }

        float prefixWidth = text.measureText(prefix);
        float secondsWidth = text.measureText(seconds);
        float startX = DIGITAL_X - (prefixWidth + secondsWidth) / 2f;
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(config.dateColor);
        canvas.drawText(prefix, startX, 447f, text);
        if (!seconds.isEmpty()) {
            text.setColor(config.secondHandColor);
            canvas.drawText(seconds, startX + prefixWidth, 447f, text);
        }

        text.setTextScaleX(1f);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void configureMainText() {
        text.setColor(config.digitalColor);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setFakeBoldText(false);
        text.setTextSize(190f * sizeScale(config.digitalSizePercent));
        text.setTextScaleX(1f);
        // Letter spacing is expressed in em units, so it tracks the size scale.
        text.setLetterSpacing(-7f / 190f);
    }

    private void configureSecondaryText() {
        text.setColor(config.dateColor);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setFakeBoldText(false);
        text.setTextSize(38f * sizeScale(config.secondarySizePercent));
        text.setTextScaleX(1f);
        // Letter spacing is expressed in em units, so it tracks the size scale.
        text.setLetterSpacing(2f / 38f);
    }

    /**
     * The multiplier for a bounded (50..100) size percentage. Both the text
     * size and the width the line is fitted into are scaled by this factor, so
     * a smaller percentage shrinks the whole line uniformly about its layout
     * anchor rather than distorting its glyphs. The default 100 yields 1.0, so
     * the accepted contract sizes are unchanged.
     */
    private static float sizeScale(int percent) {
        return percent / 100f;
    }
}
