package com.mircowuffwuff.sharpdroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class TouchOverlayView extends View {

    // PlayStation Controller Bitmask Flags
    public static final int BUTTON_CROSS    = 1 << 0;
    public static final int BUTTON_CIRCLE   = 1 << 1;
    public static final int BUTTON_SQUARE   = 1 << 2;
    public static final int BUTTON_TRIANGLE = 1 << 3;
    public static final int BUTTON_L1       = 1 << 4;
    public static final int BUTTON_R1       = 1 << 5;
    public static final int BUTTON_START    = 1 << 6;
    public static final int BUTTON_SELECT   = 1 << 7;

    // Visual bounds for the Cross (X) button
    private final RectF crossBounds = new RectF();
    private final Paint buttonPaint = new Paint();

    private int activeButtons = 0;

    public TouchOverlayView(Context context) {
        super(context);
        init();
    }

    public TouchOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        buttonPaint.setColor(Color.argb(128, 255, 255, 255)); // Semi-transparent white
        buttonPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // Position the Cross button on the bottom right (85% X, 75% Y)
        float radius = w * 0.06f;
        float centerX = w * 0.85f;
        float centerY = h * 0.75f;
        
        crossBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // Render a visual guide circle for the Cross button on screen
        canvas.drawOval(crossBounds, buttonPaint);
    }

    @Override
    public bool onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();

        activeButtons = 0;

        // Loop over active finger pointers (Multi-Touch Support)
        for (int i = 0; i < event.getPointerCount(); i++) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == index) {
                continue; // Skip finger being lifted
            }

            float x = event.getX(i);
            float y = event.getY(i);

            // Check if current finger touches Cross button
            if (crossBounds.contains(x, y)) {
                activeButtons |= BUTTON_CROSS;
            }
        }

        // Push current touch state directly into the C++ host layer
        // Sticks default centered at 128, Triggers at 0, Connected set to true
        HostLayer.nativeSetPadState(
                activeButtons,
                128, 128, // Left stick (centered)
                128, 128, // Right stick (centered)
                0, 0,     // L2 / R2 Triggers
                true      // Connected
        );

        invalidate(); // Redraw UI
        return true;
    }
}
