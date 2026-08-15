package com.joeljoseph.carcues;

import android.view.Surface;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MotionEstimatorTest {
    @Test
    public void sensitivityScalesEachDirectionIndependently() {
        MotionEstimator baseline = new MotionEstimator();
        MotionEstimator adjusted = new MotionEstimator();
        adjusted.setSensitivity(0.5f, 2f);

        baseline.sample(1_000_000_000L, 2f, -2f, Surface.ROTATION_0);
        adjusted.sample(1_000_000_000L, 2f, -2f, Surface.ROTATION_0);

        MotionEstimator.NormalizedVehicleAccelerationEstimate baselineEstimate =
                baseline.sample(1_100_000_000L, 2f, -2f, Surface.ROTATION_0);
        MotionEstimator.NormalizedVehicleAccelerationEstimate adjustedEstimate =
                adjusted.sample(1_100_000_000L, 2f, -2f, Surface.ROTATION_0);

        assertEquals(
                baselineEstimate.normalizedRight * 0.5f,
                adjustedEstimate.normalizedRight,
                0.0001f
        );
        assertTrue(adjustedEstimate.normalizedDown > baselineEstimate.normalizedDown);
        assertTrue(adjustedEstimate.normalizedDown <= 1f);
    }
}
