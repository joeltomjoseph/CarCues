package com.joeljoseph.carcues;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;

final class CueFieldView extends View {
    private static final int CUES_PER_RAIL = 5;
    // 8dp renders as 21px on the 420dpi baseline emulator, the closest whole-dp match to the
    // original 20px radius. Physical-phone validation decides the release value.
    private static final float CUE_RADIUS_DP = 8f;
    // Provisional until the cue-motion semantics and acceptance-envelope tickets are resolved.
    private static final float MAX_DISPLACEMENT_DP = 48f;

    private final Paint cuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float cueRadius;
    private final float maxDisplacement;
    private float cueRight;
    private float cueDown;

    CueFieldView(Context context) {
        super(context);
        cueRadius = dp(CUE_RADIUS_DP);
        maxDisplacement = dp(MAX_DISPLACEMENT_DP);
        cuePaint.setColor(Color.WHITE);
        cuePaint.setStyle(Paint.Style.FILL);
    }

    void setCueOffset(MotionEstimator.NormalizedCueOffset offset) {
        if (cueRight == offset.normalizedRight && cueDown == offset.normalizedDown) {
            return;
        }
        cueRight = offset.normalizedRight;
        cueDown = offset.normalizedDown;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float horizontalOffset = -cueRight * maxDisplacement;
        float verticalOffset = -cueDown * maxDisplacement;
        float railInset = cueRadius + maxDisplacement;
        float leftX = clamp(railInset + horizontalOffset, cueRadius, getWidth() - cueRadius);
        float rightX = clamp(
                getWidth() - railInset + horizontalOffset,
                cueRadius,
                getWidth() - cueRadius
        );
        float rowSpacing = getHeight() / (float) (CUES_PER_RAIL + 1);

        for (int row = 1; row <= CUES_PER_RAIL; row++) {
            float y = clamp(rowSpacing * row + verticalOffset, cueRadius, getHeight() - cueRadius);
            canvas.drawCircle(leftX, y, cueRadius, cuePaint);
            canvas.drawCircle(rightX, y, cueRadius, cuePaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
