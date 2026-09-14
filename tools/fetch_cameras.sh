#!/bin/bash
# Rebuilds app/src/main/assets/cameras.csv from OpenStreetMap (ODbL).
#
# Collects explicit speed cameras (highway=speed_camera, enforcement=maxspeed) plus any
# man_made=surveillance node sitting within 25 m of a real road.
#
# The proximity test replaced a tag test that looked principled and was not: roughly 40% of
# India's surveillance nodes carry no surveillance:zone or :type at all, so filtering on those
# tags silently dropped whole neighbourhoods — including cameras people drive past daily.
# Where a camera sits is better evidence than whether a mapper typed a tag.
#
# Cameras often carry no maxspeed of their own, so the limit is inherited from the road
# the camera node sits on, then from any enforcement relation it belongs to. What is still
# unknown is written as 0, which the app reads as "warn, but say no number".
#
# Default bbox is India. Pass another as: fetch_cameras.sh "south,west,north,east"
set -e
BBOX=${1:-6.0,67.5,36.0,97.9}
OUT=$(dirname "$0")/../app/src/main/assets/cameras.csv
MIRRORS="https://overpass.private.coffee https://overpass.kumi.systems https://overpass-api.de https://overpass.osm.ch"

query() {
  cat <<Q
[out:json][timeout:600];
(
  node["highway"="speed_camera"]($BBOX);
  node["enforcement"="maxspeed"]($BBOX);
)->.explicit;
way["highway"~"^(motorway|trunk|primary|secondary|tertiary)$"]($BBOX)->.roads;
node["man_made"="surveillance"]["surveillance"!="indoor"]["surveillance:zone"!="building"]($BBOX)->.watch;
(
  .explicit;
  node.watch(around.roads:25);
)->.cams;
.cams out body;
way(bn.cams)["highway"];
out body;
rel["type"="enforcement"]["enforcement"="maxspeed"]($BBOX);
out body;
Q
}

raw=$(mktemp)
for m in $MIRRORS; do
  echo "trying $m"
  query | curl -sS --max-time 600 -X POST -d @- "$m/api/interpreter" -o "$raw" || continue
  if head -c 200 "$raw" | grep -q '"elements"'; then found=$m; break; fi
done
[ -n "${found:-}" ] || { echo "every mirror was busy, try again in a minute"; exit 1; }

python3 - "$raw" "$OUT" <<'PY'
import json, re, sys
els = json.load(open(sys.argv[1]))['elements']
nodes = {e['id']: e for e in els if e['type'] == 'node'}
ways  = [e for e in els if e['type'] == 'way']
rels  = [e for e in els if e['type'] == 'relation']

def kmh(v):
    if not v: return 0
    v = v.strip().lower()
    m = re.match(r'^(\d+)\s*mph$', v)
    if m: return int(round(int(m.group(1)) * 1.609))
    m = re.match(r'^(\d+)', v)
    return int(m.group(1)) if m else 0

limit = {i: kmh(n.get('tags', {}).get('maxspeed')) for i, n in nodes.items()}
kind  = {i: ('S' if n.get('tags', {}).get('highway') == 'speed_camera' else 'T')
         for i, n in nodes.items()}
for w in ways:
    s = kmh(w.get('tags', {}).get('maxspeed'))
    if s:
        for i in w.get('nodes', []):
            if limit.get(i) == 0: limit[i] = s
for r in rels:
    s = kmh(r.get('tags', {}).get('maxspeed'))
    if s:
        for m in r.get('members', []):
            if m['type'] == 'node' and limit.get(m['ref']) == 0: limit[m['ref']] = s

rows = sorted((round(n['lat'], 6), round(n['lon'], 6), limit[i], kind[i]) for i, n in nodes.items())
with open(sys.argv[2], 'w') as f:
    f.write('# OpenStreetMap traffic cameras, ODbL.\n')
    f.write('# lat,lon,limit_kmh,kind -- limit 0 = unknown; kind S = speed camera, T = traffic camera.\n')
    for la, lo, li, k in rows:
        f.write('%.6f,%.6f,%d,%s\n' % (la, lo, li, k))
print('%d cameras (%d speed, %d traffic), %d with a known limit'
      % (len(rows), sum(1 for r in rows if r[3] == 'S'),
         sum(1 for r in rows if r[3] == 'T'), sum(1 for r in rows if r[2])))
PY
rm -f "$raw"
