# SpeedLimitBot

Offline speed-camera warner. Minimal UI, real Android Auto app. Release APK: **863 KB**.

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

`app/src/main/assets/cameras.csv` — `lat,lon,limit_kmh,kind`, one per line, `#` comments.
`kind` is `S` for an explicit speed camera and `T` for a traffic camera; `limit 0` means the
dataset does not know the limit, and the app then warns about the camera without claiming a
number and without ever calling the driver speeding.

**3066 cameras bundled: 521 tagged as speed cameras, 2545 as traffic/ALPR cameras**, from
OpenStreetMap (ODbL) over a bounding box around India.

Both tagging schemes matter, and missing the second one is easy: India maps most enforcement
cameras as `man_made=surveillance` with `surveillance:zone=traffic` or `surveillance:type=ALPR`,
which outnumber `highway=speed_camera` five to one. Querying only the latter returns 521 for
the whole country and looks like the data does not exist.

| Area | Cameras |
|---|---|
| Bengaluru | 1050 |
| Kerala | 1021 |
| Delhi NCR | 485 |
| Mumbai / Pune | 229 |
| elsewhere in the bbox | 270 |
| Chennai | 7 |
| Hyderabad | 3 |
| Kolkata | 1 |

Rebuild or retarget the bundle at another country with:

```bash
tools/fetch_cameras.sh "south,west,north,east"
```

### Live updates

`CameraSync` refreshes from Overpass while driving: a ~100 km box around the current position,
triggered after moving 30 km from the last download or once the data is a week old, only on a
validated connection, one request at a time, with mirror fallback. Downloads merge into the
bundled set — coverage only ever grows, and the app keeps working with no network at all.

This is the only openly queryable source that exists. There is no government or Google API
serving camera locations; see the README section below.

## Build

Needs JDK 17. The Gradle wrapper is checked in.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleRelease
```

Unit tests (alert state machine, track filtering, camera merging, geo math — 20 tests, no device):

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

Live sync verified on device against real Overpass: 1058 cameras downloaded around
Bengaluru, then 572 more after jumping to Dubai — 2983 to 3555 rows, coverage growing and
never shrinking, alerts unaffected while it ran on its background thread.

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

## Why not a better data source

There isn't one that is both open and legal to use.

- **Google Maps** shows speed cameras but exposes no API for them, and its terms forbid
  extracting or caching Maps content. The Roads API returns speed limits, not cameras, and is
  gated behind an asset-tracking licence.
- **Indian government data** does not publish camera locations as a dataset or API.
  Individual state police and municipal bodies announce locations in press releases and PDFs;
  there is no national feed, and nothing with an update cadence worth wiring to.
- **RadarBot, Waze and similar apps** run proprietary crowdsourced databases with paid data
  partnerships behind them. None offers a public API, and pulling from their private endpoints
  would breach their terms — so this app does not.
- **SCDB.info** is the commercial database that supplies TomTom, Sygic and others. It licenses
  its data, which is the realistic paid route if this ever needs coverage OSM cannot give.

Which leaves OpenStreetMap, queried through Overpass. It is genuinely thin in places — three
cameras in Hyderabad, one in Kolkata — and no amount of client-side cleverness fixes that. The
honest path to RadarBot-grade coverage is user reporting plus a licensed dataset, which is a
backend product, not an algorithm.

## Optimisation notes

R8 full mode, resource shrinking, no BuildConfig/resValues/shaders, Compose foundation only
(no Material), zero Google Play Services — location comes from the platform `LocationManager`.
