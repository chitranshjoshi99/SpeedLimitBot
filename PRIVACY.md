# SpeedLimitBot privacy policy

Last updated: 1 October 2026

SpeedLimitBot warns you about speed and traffic cameras ahead while you drive. It has no
accounts, no ads, no analytics and no server of its own.

## Location

The app uses your device's precise location, including in the background, to tell how fast you
are going and which cameras are ahead of you. It runs in the background so that it can start by
itself when your phone connects to your car and keep warning you while another app, such as a
navigation app, is on screen.

**Your position never leaves your phone.** It is not stored after use, logged to a server, or
shared with anyone.

To keep the camera list current, the app downloads cameras for the region around you from
[OpenStreetMap's Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API). The request
contains a box about 100 km across, centred on a point rounded to roughly 11 km, so it reveals
the region you are in but not where you are. Like any web request it also reaches that server
with your IP address. These servers are run by volunteers and operators of the OpenStreetMap
community under their own policies; the app sends them nothing else.

## Cameras you add

Cameras you add and speed limits you pick are saved only on your phone. They leave it only if
you export them yourself with "Download my cameras".

## Other apps

- **OsmAnd**: if it is installed and you ask, the app passes camera locations (not your
  position) to it on your phone.
- **Android Auto**: warnings are shown on your car's screen through Android Auto.

## Updates (GitHub version only)

The version installed from GitHub, not Google Play, checks GitHub once a day for a newer
release. That request carries no personal data beyond your IP address.

## Permissions

| Permission | Why |
|---|---|
| Location (precise, all the time) | Speed and cameras ahead, including when started by the car |
| Notifications | Warnings, and the notice that the app is running |
| Nearby devices (Bluetooth) | Start when the phone connects to the car |
| Display over other apps | The optional floating bubble over your navigation app |
| Ignore battery optimisation | Lets Android start the app when the car connects |

## Children

The app is not directed at children and collects no data from anyone.

## Contact

Questions or requests: open an issue at
<https://github.com/chitranshjoshi99/SpeedLimitBot/issues>.
