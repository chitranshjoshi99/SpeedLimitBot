# SpeedLimitBot

Offline speed-camera warner. Minimal UI, real Android Auto app.

Position comes from whichever provider is actually working — fused, GPS or network, ranked so
a provider republishing one stale cached fix cannot lock out the one tracking the car — seeded
from the last known fix so it never sits on "Acquiring GPS" with nothing to show. Release APK: **880 KB**.

## Install

Download the APK from the
[latest release](https://github.com/chitranshjoshi99/SpeedLimitBot/releases/latest) and open it
on the phone. Android will ask you to allow installing from your browser or file manager the
first time. On first run it asks for location (**choose Allow all the time**), notifications,
Bluetooth, and a battery-optimisation exemption — the last one matters, because without it
Android 12+ blocks the Bluetooth auto-start.

The APK is signed with a self-signed key (SHA-256
`3187c88b07b600d544fdc6050ac22854ca4127d9210b7fb1d0d0871dc6a02690`), so Play Protect warns that
it is from an unknown developer. Expected for a sideloaded build.

### The sideloaded APK will not appear in Android Auto

This is a platform rule, not a bug, and no manifest or code change works around it. From
[Google's testing documentation](https://developer.android.com/training/cars/testing):

> "To test your app in real vehicles, you must install it from a trusted source such as Google
> Play"

and Android Auto's *Unknown sources* developer option explicitly

> "doesn't apply to apps built using the Android for Cars App Library"

which is what this app is. So on a phone the APK works fully — warnings, voice, beeps, the
colour wash — but the car screen stays empty until the app is installed **through Play**.

Two ways to get it into a real car:

1. **Play internal testing.** Upload the release's `.aab` (`./gradlew bundlePlayRelease`) to a
   Play Console app, add yourself as an internal tester, and install from the Play link.
   Internal testing installs count as a trusted source, and Play does no Android Auto review on
   the internal track, so it works in the car straight away. Open testing and production *are*
   reviewed against the
   [car app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality)
   for the declared category — POI, see [Android Auto](#android-auto).
2. **Desktop Head Unit**, for development only — see `tools/dhu.md`. Sideloaded apps do work
   there, which is why this gap does not show up until a real car.

## Behaviour

| Trigger | Action |
|---|---|
| ~60 s from a camera ahead | TTS: "Speed camera ahead. Limit 50" (once per camera) |
| ~10 s from the camera | slow beep (1/s) |
| over the limit inside the warning zone | fast beep (4/s), overrides the slow beep |
| Android Auto session starts | service auto-starts, car screen appears |
| car Bluetooth connects (no Auto) | service auto-starts |
| Auto session ends / Bluetooth disconnects | service stops |
| a newer GitHub release exists | banner at the top of the screen, tap to download and install it |

Thresholds live in `AlertEngine` (`ANNOUNCE_S`, `CLOSE_S`, `OVER_TOLERANCE_KMH`, `RANGE_M`).
Only cameras inside a 55° cone ahead of the direction of travel count. The screen tints while a
camera is in range and pulses only while the beeps are sounding.

### Which cameras are "ours"

A cone alone warns about the camera on the cross street at a junction and on the other
carriageway of a divided road. So each synced camera records the roads within 25 m of it —
travel heading, class, mapped `maxspeed` — as a fifth CSV column (`87P;267P;175S40`), and
the driver's heading must match one of them within 35° (60° for the camera already being
warned about, so a bend does not re-announce it):

- Only the **nearest carriageway per axis** is kept, and a one-way way contributes one heading,
  so the opposite side of a divided road is excluded.
- `camera:direction` picks the road at a junction. It is on 64% of Bengaluru's cameras and
  within 15° of the nearest road axis for 72% of those.
- Rows without the column (bundled asset, older downloads, cameras added by hand) warn exactly
  as before until a sync covers them.

Not solvable from this data: a parallel service road or flyover with the same heading. That
needs map matching against the live route, not a map on screen.

### Predicted limits

Where no limit is mapped, it is predicted from the class of the road the driver is on:
motorway 100, trunk 80, primary 60, secondary/tertiary 50. Basis: MoRTH's 2018 notification
(M1: 120 expressway, 100 four-lane NH) with the lower state figures most cameras actually sit
under — Bengaluru 60 in city limits and 80 on designated arterials, Kerala 50 in municipal
limits, Delhi mostly 50. A predicted limit is spoken as "Limit likely 50", and the picker is
still offered after passing so the driver's answer replaces the guess.

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

Camera selection no longer depends on tags being present. Roughly 40% of India's
`man_made=surveillance` nodes carry no `surveillance:zone` or `:type` at all, and filtering on
those tags silently dropped whole neighbourhoods — a camera 1.3 km from a tester's house was
invisible while RadarBot warned about it. Anything within 25 m of a real road now counts, which
is better evidence than whether a mapper typed a tag, and still excludes building CCTV.

## Menu

A hamburger in the top-left corner of the phone screen, out of the way of the driving display:

| Item | What it does |
|---|---|
| Check for updates | Asks GitHub now, skipping the once-a-day throttle |
| Refresh camera data | Runs the Overpass download now, skipping the 30 km / one-week throttle. Park first: a busy mirror can take minutes |
| Drive with Google Maps | Turns on the floating bubble (asking for "display over other apps" the first time), starts the radar, opens Google Maps |
| Turn off floating bubble | Shown while the bubble is on |
| Show cameras in OsmAnd | Shown when OsmAnd is installed; pushes the 2000 nearest cameras onto its map |
| Add camera here | Records a camera at the current position with a limit you pick — for cameras OpenStreetMap does not have |
| Download my cameras | Saves `cameras_user.csv` wherever the system file picker points |

Manually added cameras land in the same `cameras_user.csv` as the limits picked after passing an
unknown camera, which `CameraDb` merges **last** — so a driver's answer beats OpenStreetMap and
no sync or app update can overwrite it.

Menu actions answer on a line at the top of the screen rather than a Toast, which car displays
swallow. A message ending in an ellipsis is waiting on something slow and stays up until the
result replaces it.

### Live updates

`CameraSync` refreshes from Overpass while driving: a ~100 km box around the current position
(centre snapped to a 0.1° grid, so the request reveals the region, not the car),
triggered after moving 30 km from the last download or once the data is a week old, only on a
validated connection, one request at a time, with mirror fallback. Downloads merge into the
bundled set — coverage only ever grows, and the app keeps working with no network at all.

This is the only openly queryable source that exists. There is no government or Google API
serving camera locations; see the README section below.

## Build

Needs JDK 17. The Gradle wrapper is checked in.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleGithubRelease
```

Unit tests (alert state machine, track filtering, camera merging, driver overrides, geo math — 29 tests, no device):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testGithubReleaseUnitTest
```

### Releasing

Two flavors. `github` is the sideloaded APK, with the GitHub self-update below. `play` is the
AAB for Play, without it: Play forbids apps updating themselves outside Play, so the update
code and the install permissions live only in `src/github` (`SELF_UPDATE` in `Store.kt`).

Publishing a release on GitHub runs `.github/workflows/release.yml`, which builds the signed
`github` APK and `play` AAB at that tag and attaches them as `SpeedLimitBot-<version>.apk` and
`.aab`. Play re-signs the AAB with its own key, so a Play install and a sideloaded APK cannot
update each other — uninstall one before installing the other. Bump
`versionCode`/`versionName` in `app/build.gradle.kts` first — the workflow refuses to build a
tag that disagrees with `versionName`, because the in-app update check compares the two.

Four repository secrets carry the signing key, so it never touches the repo:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i ~/.speedlimitbot/release.jks` |
| `KEYSTORE_PASSWORD` | `storePassword` from `~/.speedlimitbot/keystore.properties` |
| `KEY_ALIAS` | `keyAlias` from the same file |
| `KEY_PASSWORD` | `keyPassword` from the same file |

Optional fifth, `PLAY_SERVICE_ACCOUNT_JSON`: a Play Console service account's JSON key (Play
Console → Users and permissions, with "Release to testing tracks"). With it, every release also
lands on Play's **internal testing** track; promote from there in the Console. Play's API cannot
create an app, so the first AAB is uploaded by hand.

Privacy policy for the Play listing: [`PRIVACY.md`](PRIVACY.md). Change it if the data the app
sends changes — Play checks it against the Data safety form.

The build fails loudly if `KEYSTORE_BASE64` is missing rather than shipping an unsigned APK,
and `apksigner verify --print-certs` prints the certificate SHA-256 in the log so it can be
checked against the fingerprint above. `workflow_dispatch` rebuilds an existing tag.

### Update check

`UpdateCheck` asks the GitHub releases API for the latest tag at most once a day, only on a
validated connection, and compares it to the installed `versionName` segment by segment (a
string compare would rank 1.0.10 below 1.0.9). A newer tag with an `.apk` attached lights the
banner. Tapping it streams the APK into a `PackageInstaller` session: the system shows one
"Update?" prompt (the first time, it also asks to allow installs from this app). The platform
refuses an APK not signed with the installed key. After the app has installed itself once it is
the installer of record, so on Android 12+ later updates can go through without a prompt.

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

To run against a car instead of a phone, create an Automotive AVD from
`system-images;android-35-ext15;android-automotive;arm64-v8a` and launch
`com.speedlimitbot/androidx.car.app.activity.CarAppActivity`. That host activity comes from
`app-automotive`, which is a debug-only dependency, so the release APK is unaffected.

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
the map. (That run predates the move from the NAVIGATION to the POI category.)

### Verified on a car host

Run end to end on an Android Automotive OS emulator (`ro.build.characteristics=automotive`),
which renders the same Car App Library templates that Android Auto projection drives:

- Opening the car app starts the radar; closing the session stops it.
- The heads-up notification renders over the car UI: *"Speed camera · limit 80 — 707 m ahead"*.
- Passing an unknown-limit camera presents the picker; choosing 80 wrote
  `25.629454,85.104061,80,S` and the next pass announced `limit=80` with the over-limit beep.
- `CameraSync` pulled 4751 cameras into the car's cache while driving.

That run used the old NavigationTemplate screen; the POI screen below has not been on a car
host yet. One host difference carries over: the Automotive host did not render the action
strip, so the limit picker is presented directly rather than hidden behind a button the driver
may never see.

**Still not verified:** Android Auto *projection* specifically, and the Bluetooth auto-start.
The Desktop Head Unit needs the Android Auto phone app, which needs a Play Store image and a
Google sign-in, and `ACL_CONNECTED` is a protected broadcast `adb` cannot fake. The templates,
service lifecycle and notification are all shared with projection, so what remains
untested there is the projection transport rather than the app's behaviour. See
`tools/dhu.md`.

## Alerts and accessibility

Everything audible plays as `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and holds
`AUDIOFOCUS_GAIN_TRANSIENT` while — and only while — the warning speaks or beeps. Other apps
pause rather than duck (a ducked song over a car stereo still buries a beep) and resume when
focus is released; the resume is the system's job, so it survives the app being killed
mid-alert. Losing focus to another app clears our record of holding it, so the next warning
asks again.

Beeps are synthesised through `AudioTrack` rather than `ToneGenerator`, which only accepts a
legacy stream type. Each pattern is one long-lived static track (tone plus silence) looped by
the mixer. Building a fresh track per beep, as before, left it reachable only weakly from native
code, so the collector could finalize it mid-beep — the screen flashed and nothing played.

The app plays its own beeps at full gain and **never touches the user's volume settings**.
Raising the system media volume to force loudness would leave the car's stereo turned up after
the drive, which is worse than the problem it solves. If guidance volume is low in the car, it
is the car's guidance volume that needs raising.

The whole window washes amber on approach and red when over the limit, pulsing, with the
current UI when no camera is near — colour reads from a driving position at a glance where a
number does not. The limit disc shows `?` when the dataset has no limit.

After passing a camera with an unknown limit, five choices appear (50 / 70 / 80 / 100 / 120,
plus skip). What the driver picks is stored in `cameras_user.csv` and merged last, so it beats
both the bundled data and any later download, and it is used from the next pass onward. Fixed
choices rather than a keyboard: text entry while driving is blocked by the car host and is a
bad idea on the phone too.

## Alongside Google Maps

The app never talks to Google Maps; both run side by side. Maps navigates. This app keeps
watching in its foreground service, beeps and speaks over Maps' own audio, and on the phone
draws a draggable floating bubble (`Bubble`) over it. The bubble shows speed when idle, the
limit in amber near a camera, red over the limit, and pulses while beeping. Tapping it opens
the app, where it hides.

On Android Auto the bubble does not exist: an overlay window does not project, and no API lets
one app draw over another's map in the car. There the same warning arrives as audio and as the
heads-up and messaging notifications below, all of which show over Google Maps.

## OsmAnd

`OsmAnd` pushes the cameras onto OsmAnd's map as a GPX waypoint layer through its AIDL API (a
raw binder call to `importGpx`, so no dependency), after every sync and from the menu. It is
display only: OsmAnd's API exposes no current road or route geometry, so it cannot make these
alerts route-aware, and its Android Auto mode is a paid feature of the Play build.
**Untested against a real OsmAnd install.**

## Android Auto: warnings as messages

A sideloaded Car App Library app does not get its templates rendered in a real car, but the
app still appears in the car's app list as a **notification app**, and that surface does work.
So the warning is mirrored there as a messaging conversation: the sender's avatar is the
warning light — amber approaching, red over the limit — the message text counts down the
distance, and passing the camera cancels the conversation, which is what puts the screen back
to "no new messages".

Alerting posts happen on state changes only: first warning, and crossing into over-the-limit.
Posting once per beep was asked for and deliberately not built. The car reads the sender name
aloud for each message, so it would talk over the spoken warning; the notification manager
rate-limits a package posting in a tight loop, so the extra posts get dropped rather than
shown; and a list growing several times a second is the reading-while-driving that the car's
own rules exist to prevent. Between state changes the same notification is updated silently,
so the distance still counts down without a fresh alert each time.

## Android Auto

The app is a Car App Library app in the `androidx.car.app.category.POI` category. It was
NAVIGATION, which was wrong twice over: the app gives no directions, and a navigation app
takes Maps' place on the main screen. Cameras are points of interest, so POI is the honest fit
— and no category gets a third-party app into Android Auto's small side cards anyway (those are
reserved for the active navigation, media and call).

`RadarCarAppService` is the entry point: Android Auto starting it is what launches the radar,
which is a cleaner trigger than the Bluetooth heuristic — no battery-optimisation exemption
needed. `RadarScreen` is a `PlaceListMapTemplate`: a host-drawn map with the nearest cameras
as coloured pins (red speed, yellow traffic, labelled with the limit), a live "Now" row with
speed and the camera ahead, and one row per camera. It redraws on every fix, so each update
must count as a template *refresh* or the host closes the app after five: the header title,
row count and row titles never change. Camera row titles are a bare `DistanceSpan`, which the
refresh check ignores, and everything that changes sits in the row text.

**There is no floating overlay on the car screen.** `SYSTEM_ALERT_WINDOW` does not project to
Android Auto, and no API lets one app draw over another's map. What exists instead is the
**heads-up notification**: a notification with `CATEGORY_NAVIGATION`, extended with
`CarAppExtender` at `IMPORTANCE_HIGH`, which the car renders over whatever is on screen —
Google Maps included. `AlertService.carAlert()` posts exactly that on each announcement. It is
the whole overlay mechanism Android Auto offers, and it is what this app uses.

Worth keeping in mind: the audio path never needed any of this. TTS and beeps use
`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, so they duck Maps and play through the car speakers
whether or not the app is on screen.

Shipping on Play means meeting the
[car app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality).
Google's POI examples are parking, charging and fuel; speed cameras are not named, so review
may ask.

## Runtime setup on the phone

1. Grant location — choose **Allow all the time** (background location). A disclosure screen
   comes first, as Play requires; "Not now" skips it and the app still works while open.
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
