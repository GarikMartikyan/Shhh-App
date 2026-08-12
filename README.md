# Shhh

Put the phone face down on a table and it goes quiet. Pick it up and the sound comes back.

This is Pixel's **Flip to Shhh** rebuilt for a Samsung phone, which does not ship it — One UI's
Modes and Routines has no orientation or face-down condition, Good Lock's Routines+ adds only
fingerprint, S Pen and button triggers, and "Mute with gestures" only silences a call or alarm that
is already ringing. So it is an app.

Two quick haptic ticks confirm each transition, because the screen is against the table and there is
nothing else to tell you it worked.

## Why it watches the accelerometer and not the proximity sensor

The obvious implementation — "screen covered plus face down" — is not available. **Samsung does not
expose the real proximity sensor to third-party apps.** The sensor that is exposed is the palm
sensor, and on this device it was measured reporting both *near* and *far* in the same face-down
position, so it cannot be trusted to tell a desk from a pocket.

What is left is "flat and still": gravity Z at or below a threshold *and* non-gravity acceleration
below a threshold, both held continuously for a debounce window. A phone in a worn pocket is
essentially never both within a few degrees of horizontal and motionless for over a second, so that
pair is the pocket guard. Release uses a separate, shallower threshold, so a phone that jiggles on
the table does not chatter between states.

The thresholds are not invented. Real placements measured on the device landed at gravity Z between
−9.53 and −9.73 with motion between 0.002 and 0.072; Balanced keeps roughly 3× headroom over the
worst of those while still tolerating an imperfect table.

| Profile  | Face down at | Released past | Still below | Hold  |
| -------- | ------------ | ------------- | ----------- | ----- |
| Strict   | z ≤ −9.5     | z > −7.5      | 0.12        | 2.0 s |
| Balanced | z ≤ −9.0     | z > −7.0      | 0.25        | 1.5 s |
| Relaxed  | z ≤ −8.3     | z > −6.3      | 0.45        | 1.0 s |

Balanced is the default. Strict rejects a surface that is not properly flat; Relaxed accepts a
slope, a cushion or a quick set-down, and is the most likely to fire in a pocket.

## How the silencing works

Since Android 15 an app cannot set the device's global Do Not Disturb state — `setInterruptionFilter`
creates an implicit rule instead, and an app may only clear a rule it owns. So Shhh owns exactly one
`AutomaticZenRule` and drives both edges of it. Owning the *off* transition as well as the *on* one
is what makes face-up reliably un-silence.

The rule is created with no `ZenPolicy`, so it inherits whatever Do Not Disturb configuration you
already have — starred contacts, repeat callers, alarm exceptions. That is what Flip to Shhh does.

Two things keep the phone from getting stranded in Do Not Disturb:

- **A screen-on backstop.** Picking the phone up almost always turns the screen on, which wakes the
  service even if the CPU had suspended and accelerometer samples were missed. `ACTION_SCREEN_ON`
  while engaged forces a release.
- **Reconciliation.** On every service start and every screen-on, the zen rule is driven back to
  whatever the detector currently believes, which bounds how long any drift — a process kill
  mid-engage, a stale rule from a previous install — can survive to "until you next look at your
  phone".

The service samples at ~10 Hz on a wake-up accelerometer where the device exposes one, and takes a
short, self-timing-out partial wake lock only while a candidate placement is finishing its debounce.

## The app

A single screen: a tilt gauge that fills as the hold completes, the on/off pill, the three
sensitivity profiles, today's silences as a bar chart, and a collapsible diagnostics readout
(service state, sensor name, whether it is a wake-up sensor, sample rate, live gravity Z / motion /
held-ms against the current thresholds, and the raw proximity reading for the record).

Android requires a notification for every foreground service, so it can never be absent. It can be
unobtrusive: it is dismissible by swipe, and turning it off in the app strips it of text and defers
it out of the way.

**Permissions:** Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`), notifications, and — for the
service to survive idle — Battery → Unrestricted. The app links straight to each settings screen.

## Modules

- **`:app`** (`com.shhh`) — Shhh itself.
- **`:torch`** (`com.shhh.torch`) — an unrelated one-tap flashlight, built to be a target for
  Samsung RegiStar's back-tap action. It exists because the torch is scoped to the process that
  asked for it: a bare activity finishes in milliseconds and leaves an empty process, the first
  thing a low-memory kill reclaims, holding the beam. A foreground service keeps the process at a
  priority that is not casually reclaimed and buys a shade entry to switch the light off without
  picking the phone up.

## Build

Requires JDK 17 (AGP will not run on the JDK 23 that is likely your default) and an Android SDK with
API 36.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:installDebug     # or :app:assembleDebug for just the APK
./gradlew :torch:installDebug   # optional
```

`local.properties` is not committed; point `sdk.dir` at your SDK, or let Android Studio write it.

Minimum SDK 34, target and compile SDK 36. No dependencies beyond the Android platform — the UI is
plain views and hand-drawn `Canvas`, and there is no AndroidX, no Compose, no Kotlin plugin (AGP 9
registers the `kotlin` extension itself).

### Handy while tuning

```sh
adb shell am start -n com.shhh/.MainActivity --ez enable true   # start the service
adb shell am start -n com.shhh/.MainActivity --es force on      # force the zen rule on/off
adb logcat -s Shhh                                              # transitions + 15 s heartbeat
```

The heartbeat line is the only way to know whether the sensor keeps delivering once the screen is
off and the device idles.
