package com.befeast.tx10clock;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import java.time.ZonedDateTime;

/** Full-frame clock surface with display-synchronised smooth animation. */
public final class ClockView extends View {

    private final ClockRenderer renderer;
    private final TimeSource timeSource;
    private boolean running;

    public ClockView(Context context) {
        this(context, null);
    }

    public ClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        renderer = new ClockRenderer(ClockConfig.defaultConfig());
        timeSource = TimeSource.system();
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
        renderer.render(canvas, getWidth(), getHeight(), now);
        if (running) {
            postInvalidateOnAnimation();
        }
    }
}
