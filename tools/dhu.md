# Verifying the Android Auto side

The emulator used for the rest of the testing cannot do this: the Desktop Head Unit talks to
the Android Auto app, which only ships on Play-enabled images. Use a real phone.

1. Install the SDK command line tools, then the head unit:

   ```bash
   sdkmanager "extras;google;auto"
   ```

2. On the phone, install the debug APK and open Android Auto's settings. Tap the version
   string ten times to unlock developer mode, then in the overflow menu enable
   **Developer settings → Unknown sources**. Without it a sideloaded car app never appears.

3. Start the head unit server on the phone and forward the port:

   ```bash
   adb forward tcp:5277 tcp:5277
   ```

4. Run the head unit:

   ```bash
   ~/Library/Android/sdk/extras/google/auto/desktop-head-unit
   ```

SpeedLimitBot should appear in the car launcher. What to check:

- Opening it starts the radar — `adb shell dumpsys activity services com.speedlimitbot`
  shows `AlertService` foreground with `types=0x00000008`.
- Closing the Auto session stops it.
- With Google Maps navigating in the foreground, an approaching camera still produces the
  heads-up notification over the map. This is the one behaviour the docs do not state
  explicitly, so it is the thing actually worth testing on hardware.
- Audio ducks Maps rather than fighting it.
