package com.joeljoseph.carcues;

import android.view.Surface;

final class MotionEstimator {
    // ponytail: provisional calibration for emulator validation; the physical-phone baseline
    // and passenger road validation decide the release values.
    private static final float FILTER_TIME_CONSTANT_SECONDS = 0.15f;
    private static final float DEAD_BAND_METERS_PER_SECOND_SQUARED = 0.12f;
    private static final float FULL_SCALE_METERS_PER_SECOND_SQUARED = 3.0f;
    private static final float MAX_SAMPLE_GAP_SECONDS = 0.5f;

    private long lastTimestampNanos;
    private int lastDisplayRotation = -1;
    private float filteredRight;
    private float filteredDown;

    NormalizedCueOffset sample(
            long timestampNanos,
            float deviceX,
            float deviceY,
            int displayRotation
    ) {
        float screenRight;
        float screenDown;

        switch (displayRotation) {
            case Surface.ROTATION_90:
                screenRight = deviceY;
                screenDown = deviceX;
                break;
            case Surface.ROTATION_180:
                screenRight = -deviceX;
                screenDown = deviceY;
                break;
            case Surface.ROTATION_270:
                screenRight = -deviceY;
                screenDown = -deviceX;
                break;
            case Surface.ROTATION_0:
            default:
                screenRight = deviceX;
                screenDown = -deviceY;
                break;
        }

        if (lastTimestampNanos == 0L || displayRotation != lastDisplayRotation) {
            resetAt(timestampNanos, displayRotation);
            return NormalizedCueOffset.NEUTRAL;
        }

        float elapsedSeconds = (timestampNanos - lastTimestampNanos) / 1_000_000_000f;
        lastTimestampNanos = timestampNanos;
        if (elapsedSeconds <= 0f || elapsedSeconds > MAX_SAMPLE_GAP_SECONDS) {
            filteredRight = 0f;
            filteredDown = 0f;
            return NormalizedCueOffset.NEUTRAL;
        }

        float alpha = 1f - (float) Math.exp(-elapsedSeconds / FILTER_TIME_CONSTANT_SECONDS);
        filteredRight += alpha * (screenRight - filteredRight);
        filteredDown += alpha * (screenDown - filteredDown);

        return new NormalizedCueOffset(normalize(filteredRight), normalize(filteredDown));
    }

    void reset() {
        lastTimestampNanos = 0L;
        lastDisplayRotation = -1;
        filteredRight = 0f;
        filteredDown = 0f;
    }

    private void resetAt(long timestampNanos, int displayRotation) {
        lastTimestampNanos = timestampNanos;
        lastDisplayRotation = displayRotation;
        filteredRight = 0f;
        filteredDown = 0f;
    }

    private static float normalize(float acceleration) {
        float magnitude = Math.abs(acceleration);
        if (magnitude <= DEAD_BAND_METERS_PER_SECOND_SQUARED) {
            return 0f;
        }

        float normalized = (magnitude - DEAD_BAND_METERS_PER_SECOND_SQUARED)
                / (FULL_SCALE_METERS_PER_SECOND_SQUARED
                - DEAD_BAND_METERS_PER_SECOND_SQUARED);
        return Math.copySign(Math.min(normalized, 1f), acceleration);
    }

    static final class NormalizedCueOffset {
        static final NormalizedCueOffset NEUTRAL = new NormalizedCueOffset(0f, 0f);

        final float normalizedRight;
        final float normalizedDown;

        NormalizedCueOffset(float normalizedRight, float normalizedDown) {
            this.normalizedRight = normalizedRight;
            this.normalizedDown = normalizedDown;
        }
    }
}
