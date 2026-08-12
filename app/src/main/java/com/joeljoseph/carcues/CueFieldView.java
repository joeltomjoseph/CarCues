package com.joeljoseph.carcues;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;

final class CueFieldView extends View {
    private static final int LATTICE_ROWS = 8;
    // 8dp renders as 21px on the 420dpi baseline emulator, the closest whole-dp match to the
    // original 20px radius. Physical-phone validation decides the release value.
    private static final float CUE_RADIUS_DP = 8f;
    private static final float RAIL_INNER_EDGE_DP = 72f;
    private static final float EDGE_FADE_DP = 20f;
    private static final float LATTICE_SPACING_X_DP = 48f;
    private static final float FLOW_DRIVE_DP_PER_SECOND_SQUARED = 320f;
    private static final float FLOW_DAMPING_PER_SECOND = 3.2f;
    private static final float MAX_FRAME_GAP_SECONDS = 0.1f;
    private static final long MOTION_SAMPLE_TIMEOUT_NANOS = 500_000_000L;

    private final Paint cuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float cueRadius;
    private final float railInnerEdge;
    private final float edgeFade;
    private final float latticeSpacingX;
    private float accelerationRight;
    private float accelerationDown;
    private float velocityRight;
    private float velocityDown;
    private float phaseRight;
    private float phaseDown;
    private long lastFrameNanos;
    private long lastMotionSampleNanos;
    private boolean attached;
    private boolean frameScheduled;

    private final Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (!attached) {
                return;
            }

            if (lastMotionSampleNanos != 0L
                    && frameTimeNanos - lastMotionSampleNanos > MOTION_SAMPLE_TIMEOUT_NANOS) {
                accelerationRight = 0f;
                accelerationDown = 0f;
                lastMotionSampleNanos = 0L;
            }

            if (lastFrameNanos != 0L) {
                float elapsedSeconds = Math.min(
                        (frameTimeNanos - lastFrameNanos) / 1_000_000_000f,
                        MAX_FRAME_GAP_SECONDS
                );
                stepFlow(elapsedSeconds);
            }
            lastFrameNanos = frameTimeNanos;
            invalidate();
            if (isFlowing()) {
                scheduleFrame();
            } else {
                lastFrameNanos = 0L;
            }
        }
    };

    CueFieldView(Context context) {
        super(context);
        cueRadius = dp(CUE_RADIUS_DP);
        railInnerEdge = dp(RAIL_INNER_EDGE_DP);
        edgeFade = dp(EDGE_FADE_DP);
        latticeSpacingX = dp(LATTICE_SPACING_X_DP);
        cuePaint.setColor(Color.WHITE);
        cuePaint.setStyle(Paint.Style.FILL);
    }

    void setVehicleAccelerationEstimate(
            MotionEstimator.NormalizedVehicleAccelerationEstimate estimate
    ) {
        accelerationRight = estimate.normalizedRight;
        accelerationDown = estimate.normalizedDown;
        lastMotionSampleNanos = System.nanoTime();
        if (isFlowing()) {
            scheduleFrame();
        }
    }

    void resetCueFlow() {
        accelerationRight = 0f;
        accelerationDown = 0f;
        velocityRight = 0f;
        velocityDown = 0f;
        phaseRight = 0f;
        phaseDown = 0f;
        lastFrameNanos = 0L;
        lastMotionSampleNanos = 0L;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrameNanos = 0L;
        scheduleFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        frameScheduled = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetCueFlow();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float verticalSpan = getHeight() + cueRadius * 2f;
        float rowSpacing = verticalSpan / LATTICE_ROWS;
        drawRail(canvas, true, rowSpacing, verticalSpan);
        drawRail(canvas, false, rowSpacing, verticalSpan);
    }

    private void stepFlow(float elapsedSeconds) {
        float drive = dp(FLOW_DRIVE_DP_PER_SECOND_SQUARED);
        velocityRight += -accelerationRight * drive * elapsedSeconds;
        velocityDown += -accelerationDown * drive * elapsedSeconds;

        float damping = (float) Math.exp(-FLOW_DAMPING_PER_SECOND * elapsedSeconds);
        velocityRight *= damping;
        velocityDown *= damping;
        phaseRight += velocityRight * elapsedSeconds;
        phaseDown += velocityDown * elapsedSeconds;

        float verticalSpan = getHeight() + cueRadius * 2f;
        phaseRight = centeredWrap(phaseRight, latticeSpacingX);
        phaseDown = centeredWrap(phaseDown, verticalSpan);
    }

    private boolean isFlowing() {
        return accelerationRight != 0f
                || accelerationDown != 0f
                || Math.abs(velocityRight) >= 0.5f
                || Math.abs(velocityDown) >= 0.5f;
    }

    private void scheduleFrame() {
        if (!attached || frameScheduled) {
            return;
        }
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void drawRail(Canvas canvas, boolean left, float rowSpacing, float verticalSpan) {
        float railStart = left ? 0f : getWidth() - railInnerEdge;
        float railEnd = left ? railInnerEdge : getWidth();

        int saveCount = canvas.save();
        canvas.clipRect(railStart, 0f, railEnd, getHeight());
        for (int row = 0; row < LATTICE_ROWS; row++) {
            float rowOffset = row % 2 == 0 ? 0f : latticeSpacingX / 2f;
            float boundary = railStart - cueRadius;
            float firstX = boundary + wrap(phaseRight + rowOffset - boundary, latticeSpacingX);
            for (float x = firstX; x <= railEnd + cueRadius; x += latticeSpacingX) {
                float y = -cueRadius + wrap(rowSpacing * (row + 0.5f) + phaseDown, verticalSpan);
                cuePaint.setAlpha(Math.round(255f * edgeOpacity(x, y, railStart, railEnd)));
                canvas.drawCircle(x, y, cueRadius, cuePaint);
            }
        }
        canvas.restoreToCount(saveCount);
    }

    private float edgeOpacity(float x, float y, float railStart, float railEnd) {
        float horizontalDistance = Math.min(x - railStart, railEnd - x) - cueRadius;
        float verticalDistance = Math.min(y, getHeight() - y) - cueRadius;
        float progress = clamp(Math.min(horizontalDistance, verticalDistance) / edgeFade, 0f, 1f);
        return progress * progress * (3f - 2f * progress);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float wrap(float value, float span) {
        float wrapped = value % span;
        return wrapped < 0f ? wrapped + span : wrapped;
    }

    private static float centeredWrap(float value, float span) {
        return wrap(value + span / 2f, span) - span / 2f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

}
