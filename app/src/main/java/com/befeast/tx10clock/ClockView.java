package com.befeast.tx10clock;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import java.time.ZonedDateTime;

/** Full-frame clock surface with display-synchronised smooth animation. */
public final class ClockView extends View {

    private ClockRenderer renderer;
    private TimeSource timeSource;
    private boolean running;

    public ClockView(Context context) {
        this(context, null);
    }

    public ClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        renderer = new ClockRenderer(ClockConfig.defaultConfig());
        timeSource = TimeSource.system();
    }

    /** Atomically apply a newly accepted behavioral config on the UI thread. */
    public void apply(ClockConfig config, TimeSource source) {
        renderer = new ClockRenderer(config);
        timeSource = source;
        postInvalidateOnAnimation();
    }

    public void start() {
        if (!running) {
            running = true;
            postInvalidateOnAnimation();
        }
    }

    public void stop() {
        running = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ZonedDateTime now = timeSource.now();
        // Burn-in protection shifts the whole composition by a small, bounded,
        // per-minute amount. Keeping it here (not in the renderer) leaves
        // ClockRenderer.render a pure function of (config, time) for the golden
        // harness. The background fill inside render() ignores the matrix, so
        // the shifted composition never exposes an unpainted edge.
        int[] shift = burnInTranslation(renderer.config(), now);
        int save = canvas.save();
        canvas.translate(shift[0], shift[1]);
        renderer.render(canvas, getWidth(), getHeight(), now);
        canvas.restoreToCount(save);
        if (running) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * The bounded burn-in translation to apply for {@code now}: {@code (0, 0)}
     * when burn-in is disabled or its amplitude is zero, otherwise the
     * deterministic {@link BurnInOffset} with each axis clamped to the
     * configured {@code burnInMaxShiftPx}.
     */
    static int[] burnInTranslation(ClockConfig config, ZonedDateTime now) {
        if (!config.burnInEnabled || config.burnInMaxShiftPx <= 0) {
            return new int[]{0, 0};
        }
        BurnInOffset offset = BurnInOffset.at(now);
        return new int[]{
                boundedShift(offset.x, config.burnInMaxShiftPx),
                boundedShift(offset.y, config.burnInMaxShiftPx)};
    }

    /** Clamp a raw offset component to +/- the configured maximum amplitude. */
    static int boundedShift(int raw, int maxPx) {
        if (raw > maxPx) {
            return maxPx;
        }
        if (raw < -maxPx) {
            return -maxPx;
        }
        return raw;
    }
}
