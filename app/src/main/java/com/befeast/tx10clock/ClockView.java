package com.befeast.tx10clock;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import java.time.ZonedDateTime;

/**
 * The on-screen surface. It delegates all drawing to {@link ClockRenderer} and
 * redraws once per display frame while visible, driven by {@link Choreographer}
 * so the analog second hand sweeps smoothly rather than ticking. Each frame the
 * renderer reads the current instant (including sub-second nanos) from the
 * injected {@link TimeSource}, so the sweep and the digital readout stay locked
 * to real time with no accumulating drift.
 */
public final class ClockView extends View {

    private final ClockRenderer renderer;
    private final TimeSource timeSource;
    private final Choreographer choreographer = Choreographer.getInstance();

    private boolean running;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }
            invalidate();
            choreographer.postFrameCallback(this);
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

    /** Begin the per-frame redraw loop. Idempotent. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        choreographer.postFrameCallback(frameCallback);
    }

    /** Stop redrawing (e.g. when the Activity is paused). */
    public void stop() {
        running = false;
        choreographer.removeFrameCallback(frameCallback);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final ZonedDateTime now = timeSource.now();
        renderer.render(canvas, getWidth(), getHeight(), now);
    }
}
