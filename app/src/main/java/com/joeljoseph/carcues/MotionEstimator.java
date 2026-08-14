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
    private float rightSensitivity = 1f;
    private float downSensitivity = 1f;

    void setSensitivity(float rightSensitivity, float downSensitivity) {
        this.rightSensitivity = clamp(rightSensitivity, 0.5f, 3f);
        this.downSensitivity = clamp(downSensitivity, 0.5f, 3f);
    }

    NormalizedVehicleAccelerationEstimate sample(
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
            return NormalizedVehicleAccelerationEstimate.NEUTRAL;
        }

        float elapsedSeconds = (timestampNanos - lastTimestampNanos) / 1_000_000_000f;
        lastTimestampNanos = timestampNanos;
        if (elapsedSeconds <= 0f || elapsedSeconds > MAX_SAMPLE_GAP_SECONDS) {
            filteredRight = 0f;
            filteredDown = 0f;
            return NormalizedVehicleAccelerationEstimate.NEUTRAL;
        }

        float alpha = 1f - (float) Math.exp(-elapsedSeconds / FILTER_TIME_CONSTANT_SECONDS);
        filteredRight += alpha * (screenRight - filteredRight);
        filteredDown += alpha * (screenDown - filteredDown);

        return new NormalizedVehicleAccelerationEstimate(
                clamp(normalize(filteredRight) * rightSensitivity, -1f, 1f),
                clamp(normalize(filteredDown) * downSensitivity, -1f, 1f)
        );
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

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    static final class NormalizedVehicleAccelerationEstimate {
        static final NormalizedVehicleAccelerationEstimate NEUTRAL =
                new NormalizedVehicleAccelerationEstimate(0f, 0f);

        final float normalizedRight;
        final float normalizedDown;

        NormalizedVehicleAccelerationEstimate(float normalizedRight, float normalizedDown) {
            this.normalizedRight = normalizedRight;
            this.normalizedDown = normalizedDown;
        }
    }
}
