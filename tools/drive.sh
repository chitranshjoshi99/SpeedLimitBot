#!/bin/bash
# Drives a synthetic track past the camera at 12.971599,77.594566 (limit 50 km/h).
# Phase A: 10 m per fix (~35 km/h, under the limit) -> expect one SPEAK, then a SLOW beep.
# Phase B: 40 m per fix (~140 km/h, over the limit)  -> expect one SPEAK, then a held FAST beep.
#
# Watch it with:  adb logcat -s Radar:I -v time
A=~/Library/Android/sdk/platform-tools/adb
CAM_LAT=12.971599
LON=77.594566
DEG=111320

drive() { # $1 = metres per fix, $2 = start distance south of camera
  python3 -c "
print('\n'.join('%.6f' % ($CAM_LAT - (d/$DEG)) for d in range(int($2), -200, -int($1))))
" | while read -r lat; do
    $A emu geo fix $LON "$lat" > /dev/null
    sleep 1
  done
}

park() { # sit still so the engine resets between phases
  for _ in 1 2 3 4; do $A emu geo fix 77.7 12.5 > /dev/null; sleep 1; done
}

echo "--- phase A: under the limit"
park
drive 10 700
echo "--- phase B: over the limit"
park
drive 40 1800
echo "--- done"
