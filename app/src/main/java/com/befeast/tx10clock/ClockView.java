package com.befeast.tx10clock;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.time.ZonedDateTime;

/**
 * The on-screen surface. It delegates all drawing to {@link ClockRenderer} and
 * simply invalidates itself once per second (aligned to the wall-clock second)
 * so the second hand and digital readout stay in step with real time.
 */
public final class ClockView extends View {

    private final ClockRenderer renderer;
    private final TimeSource timeSource;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            scheduleNextTick();
        }
    };

    public ClockView(Context context) {
        this(context, null);
    }

    public ClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.renderer = new ClockRenderer(ClockConfig.defaultConfig());
        this.timeSource = TimeSource.system();
    }

    /** Begin the once-per-second redraw loop. Idempotent. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        scheduleNextTick();
    }

    /** Stop redrawing (e.g. when the Activity is paused). */
    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private void scheduleNextTick() {
        if (!running) {
            return;
        }
        // Fire on the next whole second to keep the ticking crisp.
        final long delay = 1000L - (System.currentTimeMillis() % 1000L);
        handler.postDelayed(tick, delay);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final ZonedDateTime now = timeSource.now();
        renderer.render(canvas, getWidth(), getHeight(), now);
    }
}
