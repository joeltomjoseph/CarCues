# Android motion-sensing basis

Research for [Determine the Android motion-sensing basis](https://github.com/joeltomjoseph/CarCues/issues/2), reviewed 12 August 2026.

## Decision

CarCues should drive its rails from a continuous, gravity-free acceleration vector in the phone's sensor frame, remapped into the current screen frame:

1. Prefer `Sensor.TYPE_LINEAR_ACCELERATION`.
2. If that is absent, accept `TYPE_ACCELEROMETER` plus `TYPE_GRAVITY` and subtract the most recent gravity vector from the accelerometer vector.
3. If neither combination is available, do not start a cue session. A raw-accelerometer-only high-pass fallback is too sensitive to phone rotation to satisfy the requirement for stable cues.
4. Register an optional `TYPE_GYROSCOPE` stream to identify active device handling. While angular speed or jerk indicates handling, attenuate the cue toward neutral instead of presenting the motion as vehicle acceleration.
5. Sample at about 50 Hz with immediate delivery, filter using `SensorEvent.timestamp`, and publish a smoothed screen-right/screen-down vector to the view at display cadence.

This is deliberately a **best-effort low-frequency phone-acceleration estimate under a reasonably stable hold or mount**, not a claim that a phone IMU can identify vehicle motion perfectly. Pure translational hand motion and vehicle translation are physically indistinguishable to the phone's accelerometer. A gyroscope identifies rotation, and filtering rejects some short handling impulses, but neither can separate two translations that overlap in time and frequency.

## Why this sensor basis

Android defines `TYPE_LINEAR_ACCELERATION` as acceleration in the device/sensor frame with gravity removed. It is a continuous, non-wake-up composite sensor whose output is conceptually accelerometer minus gravity. When a gyroscope exists, the Android sensor specification requires the composite sensor to use it; otherwise an implementation may use the magnetometer. [`TYPE_GRAVITY` reports the corresponding gravity vector](https://source.android.com/docs/core/interaction/sensors/sensor-types#linear_acceleration), so explicitly subtracting it is the closest platform-defined fallback.

The Android SDK notes that accelerometers and gyroscopes are physical sensors while gravity and linear acceleration can be hardware- or software-derived, and that software-sensor availability varies by device. It also documents the raw-accelerometer low-pass/high-pass technique, but that estimate lags when the phone rotates because the apparent gravity direction changes. That makes it a useful demonstration, not a suitable CarCues fallback for a phone being held by a passenger. [Android motion-sensor guidance](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)

Availability must therefore be checked at runtime with `getDefaultSensor(...)`, followed by checking the boolean returned by `registerListener(...)`. Android-compatible devices are only strongly recommended, not universally required, to contain a three-axis accelerometer or gyroscope. On Android 10 through 14, a device that contains both must expose gravity and linear-acceleration composites, but the hardware itself is not guaranteed. [Android 10 CDD](https://source.android.com/docs/compatibility/10/android-10-cdd#7_3_1_accelerometer), [Android 14 CDD](https://source.android.com/docs/compatibility/14/android-14-cdd#7_3_1_accelerometer)

### Runtime capability order

| Available sensors | Result | Reason |
| --- | --- | --- |
| Linear acceleration, with optional gyroscope | Supported; preferred | Uses the device manufacturer's sensor fusion and exposes the exact quantity required. Gyroscope events add handling suppression. |
| Accelerometer + gravity, with optional gyroscope | Supported fallback | Reconstructs the platform-defined linear-acceleration relationship without inventing a gravity model. |
| Accelerometer only | Unsupported | A low-pass gravity estimate turns phone rotations into false acceleration during the filter's settling period. |
| No accelerometer-derived usable stream | Unsupported | Static cues would falsely imply an active session. |

`TYPE_ROTATION_VECTOR` is not required for v1. The desired output is relative to the visible screen, not magnetic north or an Earth-fixed heading. A normal rotation vector also uses a magnetometer, which adds no value for screen-relative cues and can be disturbed in a vehicle. `TYPE_GAME_ROTATION_VECTOR` avoids the magnetometer, but requiring it would add a second fused orientation path when display rotation plus gravity-free acceleration already defines the needed two-dimensional input. Android describes the game rotation vector as accelerometer-and-gyroscope fusion with allowed yaw drift. [AOSP composite sensor definitions](https://source.android.com/docs/core/interaction/sensors/sensor-types#game_rotation_vector)

## Device-to-screen transformation

Android motion-sensor axes are fixed to the device's **natural** orientation: +X points right, +Y points up, and +Z points out of the screen. They do not swap when the display rotates. Android explicitly directs display-facing applications to combine `Display.getRotation()` with coordinate remapping. [Sensor coordinate system](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-coords), [`SensorManager.remapCoordinateSystem`](https://developer.android.com/reference/android/hardware/SensorManager#remapCoordinateSystem(float%5B%5D,int,int,float%5B%5D))

For a sensor-frame vector `(x, y, z)`, CarCues can implement the equivalent two-dimensional transform directly. The output below uses Canvas convention: +right and +down.

| `Display.getRotation()` | screen right | screen down |
| --- | ---: | ---: |
| `Surface.ROTATION_0` | `x` | `-y` |
| `Surface.ROTATION_90` | `y` | `x` |
| `Surface.ROTATION_180` | `-x` | `y` |
| `Surface.ROTATION_270` | `-y` | `-x` |

The 90-degree row agrees with Android's official `AXIS_Y, AXIS_MINUS_X` remapping example; Canvas then negates the remapped +Y-up axis to obtain +Y-down. The transform should use the display rotation associated with the overlay's display and be refreshed after a configuration/display change. It must not infer orientation from width versus height, because reverse portrait and reverse landscape have different signs.

Keep acceleration and cue displacement as separate concepts. The estimator should output signed screen acceleration. If the visual design makes the dots behave like points fixed outside the vehicle, the view layer negates that vector when converting it to pixel displacement. This sign boundary is easy to verify with a controlled push and avoids burying visual semantics in sensor code.

## Gravity, handling, and noise

The processing order should be:

1. **Gravity removal:** take `TYPE_LINEAR_ACCELERATION`, or calculate `accelerometer - gravity` for the supported fallback. Do not subtract a fixed `9.81` from one axis; gravity changes across all device axes as the phone tilts.
2. **Screen remap:** apply the rotation table to the gravity-free device vector.
3. **Handling detection:** use optional gyroscope magnitude and acceleration jerk to recognize active rotation or a sharp pickup/reposition gesture. Attenuate toward neutral during the event and recover smoothly afterward. A gyroscope measures angular rate in radians/second in the same local axes as the acceleration sensor. [Sensor event definitions](https://developer.android.com/reference/android/hardware/SensorEvent#sensor.type_gyroscope)
4. **Noise filtering:** apply a time-based one-pole low-pass filter to the two screen components, followed by a small dead band and a bounded nonlinear mapping to cue displacement. Compute the coefficient from event delta time (for example `alpha = 1 - exp(-dt / tau)`) rather than assuming a fixed callback rate.
5. **Rendering:** retain the latest filtered value and invalidate/render at display cadence. Sensor callbacks should not mutate individual dot positions independently.

A starting filter time constant around 100–200 ms is appropriate for controlled phone evaluation: it rejects high-frequency sensor noise without building a long lag into braking/turning cues. The final time constant, handling thresholds, dead band, and acceleration-to-pixel scale remain calibration values, not facts supplied by Android. They should be fixed only after deterministic trace tests and controlled physical-phone checks. Reset filter state after startup, a display-rotation change, or an abnormally long timestamp gap so a stale vector cannot jump the rails.

Do not maintain a freely adapting acceleration baseline while cues are active: enabling during real acceleration or a long turn could teach the estimator that a genuine cue is “zero.” The platform composite should already settle near zero when immobile. A dead band handles small residual bias more safely.

## Sampling and Android 10–14 behavior

Use `registerListener(listener, sensor, 20_000)` (or `SENSOR_DELAY_GAME`) for a requested period of roughly 20 ms/50 Hz. Use zero batching latency for cue responsiveness. Android documents 20,000 μs for `SENSOR_DELAY_GAME`, but also states that the requested period is only a hint and events may arrive faster or slower; actual timing must come from sensor-event timestamps. [`registerListener` contract](https://developer.android.com/reference/android/hardware/SensorManager#registerListener(android.hardware.SensorEventListener,android.hardware.Sensor,int,int))

Fifty hertz is also a portable ceiling to design around: the Android 10–14 compatibility definitions require accelerometers and gyroscopes that are present to report at least 50 Hz, while only recommending 200 Hz. [Android 14 accelerometer requirements](https://source.android.com/docs/compatibility/14/android-14-cdd#7_3_1_accelerometer)

Relevant platform behavior across the supported range:

- **Android 10–11:** continuous sensors do not deliver to an ordinary background app on Android 9 and later. Since cues must continue over other apps, sensor registration belongs to the user-visible foreground service, and listeners must be unregistered when the cue session ends. [Android sensor best practices](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-best-practices)
- **Android 12 (API 31):** apps targeting API 31+ are limited to 200 Hz through `registerListener` unless they request `HIGH_SAMPLING_RATE_SENSORS`. CarCues needs only about 50 Hz, so it should not request that permission. Android 12 also restricts starting a foreground service from the background; the agreed manual start from a visible activity fits the supported path. [Sensor rate limiting](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-rate-limiting), [foreground-service changes](https://developer.android.com/develop/background-work/services/fgs/changes#android_12)
- **Android 13 (API 33):** `POST_NOTIFICATIONS` controls whether the foreground-service notification appears in the notification drawer, although it is not required merely to launch the service and the service must still supply a notification. This affects the Stop-cues affordance, not sensor access. [Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- **Android 14 (API 34):** an app targeting 34 must declare a foreground-service type and its type-specific permission. This cue-overlay use does not match a standard category; if it remains a long-running foreground service, `specialUse` is the documented catch-all, with `FOREGROUND_SERVICE_SPECIAL_USE` and a service-level subtype explanation. That declaration is subject to Play review. [Android 14 foreground-service types](https://developer.android.com/about/versions/14/changes/fgs-types-required#special-use)

The accelerometer, gravity, linear-acceleration, and gyroscope APIs themselves require no runtime permission at the proposed sampling rate. `ACTIVITY_RECOGNITION` is for activity/step-related APIs and is not needed by this estimator.

## Testability contract

Keep Android callbacks in a thin adapter and put all estimation in a plain Java component with an input shaped like:

```text
sample(timestampNanos, deviceLinearX, deviceLinearY, deviceLinearZ,
       displayRotation, optionalAngularRate)
    -> screenAcceleration(right, down, confidence)
```

That boundary permits local unit tests without constructing framework-owned `SensorEvent` objects:

- all four rotation mappings, including reverse portrait/landscape;
- rest/noise settling to zero and no drift;
- step and impulse response within the selected latency bound;
- identical behavior at regular and jittered callback intervals;
- dead-band, clamp, and displacement sign;
- high angular-rate/jerk attenuation and smooth recovery;
- reset after rotation or a long event gap;
- unsupported capability combinations.

Instrumented tests should verify the device inventory, successful listener registration, monotonic/lively event delivery, and lifecycle cleanup. The Android Emulator's Virtual sensors > Device Pose control can generate accelerometer and magnetometer values and its rotation buttons cover display rotations, which is useful for adapter and axis smoke tests. It does not substitute for vendor composite-sensor behavior. [Android Emulator virtual sensors](https://developer.android.com/studio/run/emulator-extended-controls#virtual-sensors)

Physical-phone verification remains essential. In portrait and both landscape directions, verify that a gentle controlled translation produces the same logical screen direction, rotation in place is suppressed/returns to neutral, stationary cues settle, app switching does not stop samples, and Stop cues unregisters them. A passenger-only road test must then judge whether low-frequency vehicle acceleration dominates normal hand motion; that is the one material fact Android's contracts cannot guarantee.

## Remaining uncertainty

- Vendor implementations differ in fusion latency, bias, and noise despite sharing the same sensor type. The chosen filter and handling thresholds require measurement on the target phone.
- Pure hand translation cannot be separated from vehicle translation with these sensors alone. If normal passenger use makes this ambiguity unacceptable, the product must later add a stronger usage constraint (for example, a stable mount) or another independent signal; filtering alone cannot solve it.
- Android 14 `specialUse` is the closest platform foreground-service type, but store-policy acceptance is a release/policy question rather than a sensor question.
