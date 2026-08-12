# Android cue-session lifecycle

Research question: which Android 10 through Android 14 rules must CarCues satisfy so a manually started, touch-through cue session remains visible while other apps are used and ends when the user stops it?

## Decision

Use one started, sticky foreground `OverlayService` as the sole owner of the cue session. The activity requests special access, starts or stops the service, and reflects service-owned state; it must not own the overlay, motion listener, or foreground notification. A normal session follows this order:

1. From a visible activity, verify the motion sensor, overlay access, and notification visibility prerequisites.
2. Start `OverlayService` with `startForegroundService()` and an explicit start action.
3. In `onStartCommand()`, create the channel and call `startForeground()` immediately, then add the overlay and register the sensor listener. Android documents this two-step foreground-service start and requires a notification priority of `LOW` or higher. ([Launch a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch))
4. Return `START_STICKY` for an active session. If the process is killed, Android will try to recreate the service and may call `onStartCommand()` with a null intent; sticky foreground-service restarts are exempt from Android 12's background-start restriction. Setup therefore has to be null-safe and idempotent. ([`Service.START_STICKY`](https://developer.android.com/reference/android/app/Service#START_STICKY))
5. Keep the service independent of the activity and set `android:stopWithTask="false"` (also the default), so activity destruction, rotation, app switching, and removing the task from Recents do not stop the session. ([Services overview](https://developer.android.com/develop/background-work/services), [`<service android:stopWithTask>`](https://developer.android.com/guide/topics/manifest/service-element#stwt))
6. Stop only through an explicit app or notification command, or when a required resource/access fails. Teardown must be idempotent: mark inactive, stop watching app-ops, unregister the sensor listener, remove the overlay if attached, remove the foreground notification, then call `stopSelf()`. Android permits either `stopSelf()` or `stopService()` and removes the foreground notification when the service stops. ([Stop a foreground service](https://developer.android.com/develop/background-work/services/fgs/stop-fgs))

Do not register a boot receiver. Consequently, reboot ends the session as required. Force-stop, Android 13's Task Manager Stop action, overlay-access revocation, or unrecoverable setup failure also end it; these are user/platform overrides to “until explicitly stopped,” not states the app can defeat.

## Manifest contract for the current target SDK 34

The project targets API 34, so the manifest should declare:

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application ...>
    <service
        android:name=".OverlayService"
        android:exported="false"
        android:stopWithTask="false"
        android:foregroundServiceType="specialUse">
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="Keeps a user-started motion-cue overlay responsive while other apps are visible" />
    </service>
</application>
```

`FOREGROUND_SERVICE` is a normal permission required for regular apps to call `startForeground()`. Apps targeting API 34 must also declare a foreground-service type and its type-specific permission; an undeclared type produces `MissingForegroundServiceTypeException`, and a missing type permission produces `SecurityException`. CarCues does not fit the other enumerated types, so `specialUse` is the appropriate platform type. It has no runtime prerequisite, but its free-form subtype explanation is reviewed during Play submission. ([Foreground-service changes](https://developer.android.com/develop/background-work/services/fgs/changes), [Android 14 foreground-service types](https://developer.android.com/about/versions/14/changes/fgs-types-required#special-use))

When promoting the service, pass `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` to `ServiceCompat.startForeground()` on API 34; pass `0` on API 29–33. The runtime type must be a subset of the manifest declaration or Android throws `IllegalArgumentException`. ([Launch a foreground service](https://developer.android.com/develop/background-work/services/fgs/launch#promote))

Raw accelerometer access at ordinary UI/game rates needs neither `ACTIVITY_RECOGNITION` nor `HIGH_SAMPLING_RATE_SENSORS`. The latter is only needed above 200 Hz for apps targeting Android 12+, which this visual cue does not need. `ACTIVITY_RECOGNITION` should therefore be removed unless a later feature uses the activity-recognition API. Continuous sensors stop delivering to background apps on Android 9+, which is why the listener must live in the foreground service. ([Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-practices), [sensor rate limiting](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview#sensors-rate-limiting))

## Overlay permission and window

`SYSTEM_ALERT_WINDOW` is special access, not a normal runtime permission. Do not call `ActivityCompat.requestPermissions()` for it. Declare it, check `Settings.canDrawOverlays()`, and send `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` when false. Always recheck on returning to `onResume`; the Settings activity returns no permission result contract. On Android 10, a `package:` URI can open the app-specific page. Beginning with Android 11, the same action always opens the top-level list, so the package URI must not be relied upon. ([`Settings.canDrawOverlays`](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)), [Android 11 permission changes](https://developer.android.com/about/versions/11/privacy/permissions#manage-overlay))

The cue window must be `TYPE_APPLICATION_OVERLAY`, which requires that access and is above app windows but below critical system windows such as the status bar and IME. Android may independently alter its position, size, or visibility. ([`TYPE_APPLICATION_OVERLAY`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY))

Use a single overlay window drawing both rails, with:

- `MATCH_PARENT` dimensions and `PixelFormat.TRANSLUCENT`;
- `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`;
- no touch listener or interactive child;
- `LayoutParams.alpha` at or below `InputManager.getMaximumObscuringOpacityForTouch()` on Android 12+ (the documented value is currently `0.8`).

The alpha cap is mandatory for CarCues' touch-through guarantee. Starting with Android 12, `TYPE_APPLICATION_OVERLAY` is untrusted: when a `FLAG_NOT_TOUCHABLE` overlay exceeds the maximum obscuring opacity, Android drops the underlying touch and logs the failure. Multiple overlapping windows combine their opacity, so one window is safer. A fully opaque white dot is therefore incompatible with guaranteed pass-through on Android 12–14; apply the safe window alpha on all supported versions for consistent appearance. ([`FLAG_NOT_TOUCHABLE` and obscuring opacity](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE))

This is not a promise that cues appear above every screen. Starting with Android 12, sensitive apps can opt out of third-party application overlays with `HIDE_OVERLAY_WINDOWS`, and critical system windows remain above them. ([Secure sensitive activities](https://developer.android.com/security/fraud-prevention/activities#hide-overlay-windows))

Watch `AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW` with `startWatchingMode()` while active. When it changes, recheck `Settings.canDrawOverlays()` on the main thread; if access is gone, tear the session down instead of leaving a notification or static state that claims cues are active. Also catch `SecurityException`/window-add failures because permission can change between preflight and `addView()`. The public app-ops API permits an app to monitor operation changes for its own UID. ([`AppOpsManager.startWatchingMode`](https://developer.android.com/reference/android/app/AppOpsManager#startWatchingMode(java.lang.String,java.lang.String,android.app.AppOpsManager.OnOpChangedListener)))

## Foreground-service and process behavior

Android 10 and 11 allow this user-started foreground service with the base foreground-service permission. Android 12–14 disallow foreground-service starts while the app is in the background unless an exception applies, throwing `ForegroundServiceStartNotAllowedException`; starting directly from the visible Enable action is permitted. Do not defer startup until after the activity has gone to the background, and do not attempt an automatic background start. ([Background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start))

The service should promote itself before overlay or sensor initialization. If later setup fails, it should immediately tear itself down and expose a user-readable inactive reason. Repeated Enable commands must not add duplicate windows or listeners. A Stop command received during startup must win and must not fall through to activation.

Activity rotation normally destroys and recreates the activity, but it does not recreate other running components. `Service.onConfigurationChanged()` is called with updated resources, so the service/view must invalidate and recompute its geometry and screen-relative sensor mapping for the new display rotation rather than relying on activity callbacks or startup display metrics. ([Activity state changes](https://developer.android.com/guide/components/activities/state-changes#configuration-change), [`Service.onConfigurationChanged`](https://developer.android.com/reference/android/app/Service#onConfigurationChanged(android.content.res.Configuration)))

An ordinary foreground-service process is much less likely to be killed, but it is not immortal. The code must not depend on `onDestroy()` being called during process death. On a sticky restart, rebuild the notification, window, app-ops watcher, sensor listener, and in-memory active state from scratch. Explicitly stopping a started service removes it from the started state, so Android does not perform a sticky restart. ([Processes and app lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle), [`START_STICKY`](https://developer.android.com/reference/android/app/Service#START_STICKY))

The activity is only a controller. On every `onStart()`/`onResume()`, recheck special access and obtain the actual state from the running service or a process-local session-state owner. Never infer “active” merely from a saved button value. This keeps the Enable/Disable control correct after rotation, notification navigation, process recreation, or an external stop.

Android 13–14 expose an additional Task Manager Stop affordance for all foreground-service apps. It removes the entire app from memory, its activity back stack, and its foreground notification, and sends no callback. Treat the next launch as inactive; never trust a durable `active=true` flag after a fresh launch. The documented test command is `adb shell cmd activity stop-app com.joeljoseph.carcues`. ([User-initiated foreground-service stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping))

## Notification contract

Create a dedicated cue-session channel in the service before posting, at `IMPORTANCE_LOW`, and post a standard ongoing notification at `PRIORITY_LOW` or higher. Android 8+ requires a channel; after creation its behavior cannot be changed programmatically, so an existing development install may need a new channel ID or cleared app data to observe a changed importance. ([Notification channels](https://developer.android.com/develop/ui/views/notifications/channels), [foreground notification priority](https://developer.android.com/develop/background-work/services/fgs/launch#promote))

The notification body must use an immutable activity `PendingIntent` that reopens `MainActivity`. It must not stop the session. Add a distinct **Stop cues** action backed by an immutable, explicit service `PendingIntent` carrying `ACTION_STOP`; this action performs teardown directly and never launches an activity. Apps targeting Android 12+ must specify pending-intent mutability, and notification taps must not use an activity-launching service or receiver trampoline. ([Android 12 PendingIntent and notification changes](https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability), [notification trampoline restriction](https://developer.android.com/about/versions/12/behavior-changes-12#notification-trampolines))

On Android 13–14, `POST_NOTIFICATIONS` is a dangerous runtime permission. Android technically allows a foreground service to start without it, but then the service notice appears only in Task Manager and not in the notification drawer. Because CarCues requires a readily available **Stop cues** action, treat notification permission as a product prerequisite: request it from the visible activity and do not start if denied. On Android 10–12, and on 13–14 after grant, also detect app-level notification blocking and a cue channel at `IMPORTANCE_NONE`; if the stop notification cannot be visible, leave the session inactive and point the user to notification settings. ([Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission))

If a user disables notifications or the cue channel after activation, Android can hide the drawer action while the foreground service continues. The main-screen Disable control remains the fallback; Android 13–14 also retain Task Manager Stop. This mid-session user override cannot be made impossible by the app and belongs in manual testing.

## Version matrix

| OS | Required difference |
| --- | --- |
| Android 10 / API 29 | App-specific overlay Settings deep link can work. `FOREGROUND_SERVICE`, notification channel, `TYPE_APPLICATION_OVERLAY`, and foreground-service sensor ownership apply. |
| Android 11 / API 30 | Overlay Settings action opens the top-level app list; always recheck on return. |
| Android 12 / API 31–32 | Start only from visible user action; specify immutable pending intents; cap overlay-window alpha for touch-through; sensor requests above 200 Hz would require an additional permission. |
| Android 13 / API 33 | Request `POST_NOTIFICATIONS` for the drawer Stop action; Task Manager can stop the whole app without a callback. |
| Android 14 / API 34 | In addition to Android 13 behavior, target-34 builds must declare and start with `specialUse` plus `FOREGROUND_SERVICE_SPECIAL_USE` and the subtype property. |

## Acceptance and failure-path tests

Run the common cases on at least one Android 10/11 device or emulator, Android 12, Android 13, and Android 14; run the physical-phone cases in both portrait and landscape.

| Case | Expected result |
| --- | --- |
| Overlay access absent, denied, or cancelled | No service/window/active state. Android 10 may show the app page; 11+ may show the app list. Returning without a grant leaves Enable available with an explanation. |
| Notifications denied, globally blocked, or cue channel blocked | No cue session starts because the required Stop action would not be visible. |
| Service started from visible Enable | Foreground notification is established before window/sensor setup; no `ForegroundServiceDidNotStartInTimeException`, missing-type exception, or security exception. |
| Home, another app, activity Back, or Recents task removal | Overlay and sensor updates continue; reopening CarCues shows Disable. |
| Rotate while CarCues and while another app is foreground | One overlay remains, rails relayout for the new bounds, motion axes remain screen-relative, and no duplicate sensor listener appears. |
| Tap/gesture in the center, beneath a dot, and along both rails on Android 12+ | Underlying app receives every input; Logcat contains no untrusted-touch/occlusion rejection. Verify the actual window alpha is at or below the device-reported maximum. |
| Notification body | Opens CarCues and shows active/Disable without stopping or duplicating the session. |
| Notification **Stop cues** and main-screen Disable | Each independently removes overlay, sensor listener, notification, and active state. Repeated stop is harmless. |
| Overlay access revoked while active | App-ops watcher or platform termination removes/stops the session; no lingering active notification or false active UI on relaunch. |
| Normal process kill, not force-stop | Sticky service accepts a null restart intent and reconstructs exactly one notification/window/listener; document that restart may be delayed. Unit-test the null-intent/idempotent path even if a production device will not allow killing a foreground process from ADB. |
| `adb shell cmd activity stop-app com.joeljoseph.carcues` on Android 13+ | Whole app, overlay, and notification disappear without callbacks; relaunch is inactive. |
| Force-stop | Overlay and notification disappear and do not restart until the user launches/starts again. |
| Reboot | Session does not restart. |
| Switch to an app using `HIDE_OVERLAY_WINDOWS` | Cues may be hidden by platform policy; CarCues must not claim universal overlay coverage. |
| Permission revoked between preflight and `addView()`, missing service type/permission, or sensor registration failure | Exception/failure is caught, partial resources are torn down, state is inactive, and the activity shows an actionable reason. |

## Remaining platform uncertainty

- Android's documentation defines `specialUse` as the technical type for otherwise-unclassified valid foreground work, but it does not pre-approve CarCues' subtype explanation for Google Play. Store review is a separate release risk.
- OEM background-management policies can be stricter than AOSP. The lifecycle above is the Android 10–14 platform contract; physical-phone verification is still required.
- Overlay visibility is intentionally not universal: system windows, apps opting into `HIDE_OVERLAY_WINDOWS`, and system clutter/resource management can hide or reposition it.

## Primary sources

- [Android Settings overlay APIs](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context))
- [`WindowManager.LayoutParams`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- [Foreground services](https://developer.android.com/develop/background-work/services/fgs)
- [Android 14 foreground-service types](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [`Service` reference](https://developer.android.com/reference/android/app/Service)
- [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
