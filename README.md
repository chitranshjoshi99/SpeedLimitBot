# SpeedLimitBot

Offline speed-camera warner. Minimal UI, Android Auto aware. Release APK: **811 KB**.

## Behaviour

| Trigger | Action |
|---|---|
| ~60 s from a camera ahead | TTS: "Speed camera ahead. Limit 50" (once per camera) |
| ~10 s from the camera | slow beep (1/s) |
| over the limit inside the warning zone | fast beep (4/s), overrides the slow beep |
| car Bluetooth connects | service auto-starts |
| car Bluetooth disconnects / Auto projection ends | service stops |

Thresholds live in `AlertEngine` (`ANNOUNCE_S`, `CLOSE_S`, `OVER_TOLERANCE_KMH`, `RANGE_M`).
Only cameras inside a 55° cone ahead of the direction of travel count, so the opposite
carriageway stays silent.

`Track` turns raw fixes into speed and heading, and exists because providers misbehave in
three ways that all showed up on a real run: they repeat the last position verbatim, they
resume after a gap with a jump that reads as 180000 km/h, and many report neither speed nor
bearing at all. It ignores implausible pairs, holds state through duplicates, and low-passes
the speed so the over-limit beep does not chatter on jittery fix intervals.

## Camera data

`app/src/main/assets/cameras.csv` — `lat,lon,speed_limit_kmh`, one per line, `#` comments.
Ten sample rows ship with the repo; drop in your own export (an OSM Overpass query for
`highway=speed_camera` works) and rebuild. Tens of thousands of rows are fine — the lookup
is a bbox-rejected linear scan, sub-millisecond at that size.

## Build

Needs JDK 17. The Gradle wrapper is checked in.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleRelease
```

Unit tests (alert state machine, track filtering, geo math — 12 tests, no device):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:test
```

`local.properties` points at `~/Library/Android/sdk`. `gradle.properties` pins
`org.gradle.java.home` to the Homebrew JDK 17 — change it if yours lives elsewhere.

## Emulator end-to-end

`tools/drive.sh` feeds a synthetic track past the sample cameras over `adb emu geo fix`.

```bash
adb logcat -s Radar:I -v time
```

```bash
tools/drive.sh
```

Last verified run:

```
SPEAK limit=50 dist=579  speed=34  result=0    # 61 s out, spoken once
BEEP SLOW      dist=89   speed=35  limit=50    # 9.2 s out
BEEP NONE      dist=923  speed=35  limit=60    # passed it, retargeted the next camera
SPEAK limit=50 dist=1758 speed=139 result=0    # second pass, way over the limit
BEEP FAST      dist=1758 speed=139 limit=50    # held all the way in, no chatter
SPEAK limit=60 dist=923  speed=140 result=0
BEEP NONE      dist=807  speed=0   limit=60    # stopped
```

Not covered by that run: the Bluetooth auto-start path. An emulator has no car head unit,
and `ACL_CONNECTED` is a protected broadcast that `adb` cannot fake, so `CarReceiver` needs
a real car to exercise.

## Runtime setup on the phone

1. Grant location — choose **Allow all the time** (background location).
2. Allow notifications and Bluetooth.
3. Accept the battery-optimisation exemption prompt. Without it Android 12+ refuses to let
   the Bluetooth broadcast start a location foreground service, and auto-start silently fails.

Tap anywhere on screen to start/stop manually.

## Optimisation notes

R8 full mode, resource shrinking, no BuildConfig/resValues/shaders, Compose foundation only
(no Material), zero Google Play Services — location comes from the platform `LocationManager`.
