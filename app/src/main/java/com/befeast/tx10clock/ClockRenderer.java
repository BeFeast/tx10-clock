package com.befeast.tx10clock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Native Canvas implementation of the accepted clock + hybrid-calendar composition. */
public final class ClockRenderer {

    private static final float DESIGN_WIDTH = 1280f;
    private static final float DESIGN_HEIGHT = 720f;
    private static final float ANALOG_X = 318f;
    private static final float ANALOG_Y = 338f;
    private static final float DIGITAL_LEFT = 666f;
    private static final float DIGITAL_RIGHT = 1214f;
    private static final float CALENDAR_TOP = 357f;
    private static final int CALENDAR_MUTED = 0xFF636366;
    private static final int CALENDAR_CARD = 0xFF17120A;
    private static final String[] WEEKDAY_INITIALS = {"S", "M", "T", "W", "T", "F", "S"};
    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("MMMM uuuu", Locale.US);
    private static final DateTimeFormatter WEEKDAY =
            DateTimeFormatter.ofPattern("EEEE", Locale.US);

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
        fill.setColor(config.digitalColor);
        canvas.drawCircle(ANALOG_X, ANALOG_Y, 4f, fill);
    }

    private void drawDigital(Canvas canvas, ZonedDateTime now) {
        String main = ClockFormat.main(now, config.use24Hour);
        configureMainText();
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(main, DIGITAL_LEFT, 311f, text);

        float metaX = DIGITAL_LEFT + text.measureText(main) + 16f;
        configureMetaText();
        text.setTextAlign(Paint.Align.LEFT);
        if (config.showSeconds) {
            canvas.drawText(ClockFormat.seconds(now), metaX, 188f, text);
        }
        if (!config.use24Hour) {
            canvas.drawText(ClockFormat.amPm(now), metaX, 309f, text);
        }

        if (config.showDate) {
            drawHybridCalendar(canvas, now);
        }

        text.setTextScaleX(1f);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void configureMainText() {
        text.setColor(config.digitalColor);
        text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        text.setFakeBoldText(false);
        text.setTextSize(182f * sizeScale(config.digitalSizePercent));
        text.setTextScaleX(1f);
        text.setLetterSpacing(-8f / 182f);
    }

    private void configureMetaText() {
        text.setColor(config.dateColor);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setFakeBoldText(false);
        text.setTextSize(36f * sizeScale(config.secondarySizePercent));
        text.setTextScaleX(1f);
        text.setLetterSpacing(2f / 36f);
    }

    private void drawHybridCalendar(Canvas canvas, ZonedDateTime now) {
        stroke.setColor(0xFF2C2C2E);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(1f);
        canvas.drawLine(DIGITAL_LEFT, CALENDAR_TOP, DIGITAL_RIGHT, CALENDAR_TOP, stroke);

        configureCalendarText(17f, true, config.digitalColor, 3f, Paint.Align.LEFT);
        canvas.drawText(now.format(MONTH_YEAR).toUpperCase(Locale.US),
                DIGITAL_LEFT, 391f, text);

        configureCalendarText(12f, true, CALENDAR_MUTED, 2f, Paint.Align.RIGHT);
        canvas.drawText("DAY " + now.getDayOfYear(), DIGITAL_RIGHT, 391f, text);

        final float contentTop = 408f;
        YearMonth month = YearMonth.from(now);
        int firstColumn = LocalDate.of(month.getYear(), month.getMonth(), 1)
                .getDayOfWeek().getValue() % 7;
        int rowCount = (firstColumn + month.lengthOfMonth() + 6) / 7;
        final float contentBottom = contentTop + 21f + rowCount * 20f;
        final float cardRight = 778f;
        RectF card = new RectF(DIGITAL_LEFT, contentTop, cardRight, contentBottom);
        fill.setColor(CALENDAR_CARD);
        canvas.drawRoundRect(card, 14f, 14f, fill);
        stroke.setColor(config.secondHandColor);
        stroke.setStrokeWidth(1f);
        canvas.drawRoundRect(card, 14f, 14f, stroke);

        float cardCenter = (DIGITAL_LEFT + cardRight) / 2f;
        configureCalendarText(9f, true, config.secondHandColor, 1.4f, Paint.Align.CENTER);
        canvas.drawText(now.format(WEEKDAY).toUpperCase(Locale.US), cardCenter, 451f, text);
        configureCalendarText(48f, false, config.secondHandColor, 0f, Paint.Align.CENTER);
        canvas.drawText(Integer.toString(now.getDayOfMonth()), cardCenter, 502f, text);

        drawMiniMonth(canvas, now, month, firstColumn,
                800f, contentTop, DIGITAL_RIGHT - 800f);
    }

    private void drawMiniMonth(Canvas canvas, ZonedDateTime now, YearMonth month,
                               int firstColumn,
                               float left, float top, float width) {
        float columnWidth = width / 7f;
        configureCalendarText(11f, true, CALENDAR_MUTED, 1f, Paint.Align.CENTER);
        for (int column = 0; column < 7; column++) {
            canvas.drawText(WEEKDAY_INITIALS[column],
                    left + columnWidth * (column + 0.5f), top + 13f, text);
        }

        int days = month.lengthOfMonth();
        float firstRowTop = top + 21f;
        for (int day = 1; day <= days; day++) {
            int cell = firstColumn + day - 1;
            int column = cell % 7;
            int row = cell / 7;
            float centerX = left + columnWidth * (column + 0.5f);
            float centerY = firstRowTop + row * 20f + 10f;
            boolean today = day == now.getDayOfMonth();
            if (today) {
                fill.setColor(config.secondHandColor);
                canvas.drawCircle(centerX, centerY, 10.5f, fill);
            }
            configureCalendarText(14f, today, today ? config.backgroundColor : config.dateColor,
                    0f, Paint.Align.CENTER);
            canvas.drawText(Integer.toString(day), centerX,
                    centeredBaseline(centerY, text), text);
        }
    }

    private void configureCalendarText(float size, boolean bold, int color,
                                       float letterSpacingPx, Paint.Align align) {
        text.setColor(color);
        text.setTypeface(Typeface.create("sans-serif",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setFakeBoldText(false);
        text.setTextSize(size);
        text.setTextScaleX(1f);
        text.setLetterSpacing(size == 0f ? 0f : letterSpacingPx / size);
        text.setTextAlign(align);
    }

    private static float centeredBaseline(float centerY, Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        return centerY - (metrics.ascent + metrics.descent) / 2f;
    }

    /**
     * The multiplier for a bounded (50..100) text-size percentage. The default
     * 100 yields 1.0, preserving the accepted design size.
     */
    private static float sizeScale(int percent) {
        return percent / 100f;
    }
}
