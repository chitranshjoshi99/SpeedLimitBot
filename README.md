# SpeedLimitBot

Offline speed-camera warner. Minimal UI, real Android Auto app. Release APK: **837 KB**.

## Behaviour

| Trigger | Action |
|---|---|
| ~60 s from a camera ahead | TTS: "Speed camera ahead. Limit 50" (once per camera) |
| ~10 s from the camera | slow beep (1/s) |
| over the limit inside the warning zone | fast beep (4/s), overrides the slow beep |
| Android Auto session starts | service auto-starts, car screen appears |
| car Bluetooth connects (no Auto) | service auto-starts |
| Auto session ends / Bluetooth disconnects | service stops |

Thresholds live in `AlertEngine` (`ANNOUNCE_S`, `CLOSE_S`, `OVER_TOLERANCE_KMH`, `RANGE_M`).
Only cameras inside a 55° cone ahead of the direction of travel count, so the opposite
carriageway stays silent.

`Track` turns raw fixes into speed and heading, and exists because providers misbehave in
three ways that all showed up on a real run: they repeat the last position verbatim, they
resume after a gap with a jump that reads as 180000 km/h, and many report neither speed nor
bearing at all. It ignores implausible pairs, holds state through duplicates, and low-passes
the speed so the over-limit beep does not chatter on jittery fix intervals.

## Camera data

`app/src/main/assets/cameras.csv` — `lat,lon,limit_kmh`, one per line, `#` comments.
**522 cameras from OpenStreetMap (ODbL), 441 of them with a known speed limit**, covering a
bounding box around India (which also catches border areas of Nepal and Sri Lanka).

Refresh it, or retarget it at another country, with:

```bash
tools/fetch_cameras.sh
```

Cameras frequently carry no `maxspeed` tag of their own, so the script inherits the limit
from the road the camera node sits on, then from any `type=enforcement` relation it belongs
to. That recovers 189 of them. What is still unknown is written as `0`, and the app treats
that as "warn about the camera, say no number, and never claim the driver is speeding" —
it cannot know.

**Coverage is very uneven, and this is the honest limit of open data:**

| Area | Cameras |
|---|---|
| Kerala | 303 |
| Mumbai / Pune | 85 |
| Bengaluru | 48 |
| Delhi NCR | 9 |
| Hyderabad | 1 |
| Chennai | 1 |
| elsewhere in the bbox | 75 |

So it is genuinely useful in Kerala, partially useful around Mumbai, Pune and Bengaluru, and
close to blind in Delhi, Hyderabad and Chennai. RadarBot's advantage is not its code, it is a
crowdsourced database with paid data partnerships behind it. Matching that needs a data
pipeline, not a better algorithm. The lookup itself scales fine — a bbox-rejected linear scan
stays sub-millisecond into the tens of thousands of rows.

## Build

Needs JDK 17. The Gradle wrapper is checked in.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleRelease
```

Unit tests (alert state machine, track filtering, geo math — 14 tests, no device):

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

Driving past the real camera at 13.198438,77.698776 (limit 80, north of Bengaluru):

```
SPEAK limit=80 dist=569  speed=34  result=0    # 60 s out, spoken once
BEEP SLOW      dist=89   speed=34  limit=80    # 9.4 s out, no false over-limit beep
BEEP NONE      dist=0    speed=38  limit=80    # passed it
SPEAK limit=80 dist=2137 speed=153 result=0    # second pass, way over the limit
BEEP FAST      dist=2137 speed=153 limit=80    # held unbroken all the way in to 99 m
BEEP NONE      dist=99   speed=139 limit=80
```

Verified on device alongside it: the car heads-up notification posts with
`importance=4 category=navigation`, which is the shape Android Auto requires to draw it over
the map, and `RadarCarAppService` resolves for
`androidx.car.app.CarAppService` + `androidx.car.app.category.NAVIGATION`.

**Not verified:** the Android Auto session itself and the Bluetooth auto-start. Both need
hardware this machine does not have — the Desktop Head Unit needs the Android Auto app, which
needs a Play Store system image, and `ACL_CONNECTED` is a protected broadcast `adb` cannot
fake. Run `tools/dhu.md` steps on a Play-enabled device to close that gap.

## Android Auto

The app is a Car App Library app in the `androidx.car.app.category.NAVIGATION` category.
`RadarCarAppService` is the entry point: Android Auto starting it is what launches the radar,
which is a cleaner trigger than the Bluetooth heuristic — no battery-optimisation exemption
needed. `RadarScreen` draws two rows, speed and camera ahead, and nothing else.

**There is no floating overlay on the car screen.** `SYSTEM_ALERT_WINDOW` does not project to
Android Auto, and no API lets one app draw over another's map. What exists instead is the
**heads-up notification**: a notification with `CATEGORY_NAVIGATION`, extended with
`CarAppExtender` at `IMPORTANCE_HIGH`, which the car renders over whatever is on screen —
Google Maps included. `AlertService.carAlert()` posts exactly that on each announcement. It is
the whole overlay mechanism Android Auto offers, and it is what this app uses.

Worth keeping in mind: the audio path never needed any of this. TTS and beeps use
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, so they duck Maps and play through the car speakers
whether or not the app is on screen.

Shipping the navigation category on Play means meeting the
[car app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality).

## Runtime setup on the phone

1. Grant location — choose **Allow all the time** (background location).
2. Allow notifications and Bluetooth.
3. Accept the battery-optimisation exemption prompt. Without it Android 12+ refuses to let
   the Bluetooth broadcast start a location foreground service, and auto-start silently fails.

Tap anywhere on screen to start/stop manually.

## Optimisation notes

R8 full mode, resource shrinking, no BuildConfig/resValues/shaders, Compose foundation only
(no Material), zero Google Play Services — location comes from the platform `LocationManager`.
