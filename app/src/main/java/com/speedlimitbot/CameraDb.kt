package com.speedlimitbot

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable camera set: the bundled asset merged with whatever [CameraSync] has downloaded.
 * Rebuilt off the main thread by [reload] whenever a sync lands.
 */
class CameraDb private constructor(
    private val lat: DoubleArray,
    private val lon: DoubleArray,
    private val limit: IntArray,
    private val speedCam: BooleanArray,
    /** Per camera, flattened (heading, limit, guessed) triples; null when the road is unknown. */
    private val approaches: Array<IntArray?>
) {
    val size get() = limit.size

    /**
     * Nearest camera within [maxMeters] that lies ahead of a vehicle at (myLat, myLon)
     * travelling on [heading] degrees. Returns null when nothing is ahead.
     *
     * Ahead is not enough. A camera on the cross street at a junction, or on the other
     * carriageway of a divided road, is ahead too, and warning about it is how a driver learns
     * to ignore the app. Where the data says which way traffic past the camera travels, the
     * driver's heading has to match one of those approaches. [keepId] is the camera already
     * being warned about, which gets a looser match so a bend does not re-announce it.
     */
    fun nearestAhead(myLat: Double, myLon: Double, heading: Float, maxMeters: Double, keepId: Int = -1): Hit? {
        val dLatWin = maxMeters / M_PER_DEG
        val cosLat = cos(Math.toRadians(myLat))
        val dLonWin = dLatWin / (if (cosLat < 0.01) 0.01 else cosLat)
        var best: Hit? = null
        var bestDist = maxMeters

        // ponytail: linear scan with a bbox reject. Sub-millisecond into the tens of
        // thousands of rows; a lat-banded index only pays off past a few hundred thousand.
        for (i in limit.indices) {
            if (abs(lat[i] - myLat) > dLatWin) continue
            if (abs(lon[i] - myLon) > dLonWin) continue
            val d = distance(myLat, myLon, lat[i], lon[i])
            if (d >= bestDist) continue
            if (abs(angleDiff(bearing(myLat, myLon, lat[i], lon[i]), heading)) > AHEAD_CONE) continue

            var li = limit[i]
            var guessed = false
            val a = approaches[i]
            if (a != null) {
                val tol = if (i == keepId) ALIGN_KEEP else ALIGN
                var match = -1
                var k = 0
                while (k < a.size) {
                    if (abs(angleDiff(a[k].toFloat(), heading)) <= tol) { match = k; break }
                    k += 3
                }
                if (match < 0) continue
                if (li == 0) { li = a[match + 1]; guessed = a[match + 2] == 1 }
            }
            best = Hit(i, d, li, speedCam[i], lat[i], lon[i], guessed)
            bestDist = d
        }
        return best
    }

    /** Up to [max] cameras nearest to a point, closest first, with their mapped limit. */
    fun nearest(myLat: Double, myLon: Double, max: Int): List<Hit> =
        limit.indices
            .map { Hit(it, distance(myLat, myLon, lat[it], lon[it]), limit[it], speedCam[it], lat[it], lon[it]) }
            .sortedBy { it.distance }
            .take(max)

    /**
     * @param speedCamera false for a traffic camera, which may not enforce speed at all.
     * @param guessed the limit is predicted from the class of road, not mapped.
     */
    class Hit(
        val id: Int,
        val distance: Double,
        val limitKmh: Int,
        val speedCamera: Boolean,
        val lat: Double,
        val lon: Double,
        val guessed: Boolean = false
    )

    companion object {
        private const val M_PER_DEG = 111_320.0
        private const val AHEAD_CONE = 55f
        private const val ALIGN = 35f
        private const val ALIGN_KEEP = 60f

        /**
         * Car limits by OSM road class where nobody mapped one, leaning to the lower common value.
         * MoRTH's 2018 notification caps M1 at 120 on expressways and 100 on 4-lane NHs, and
         * states set lower: Bengaluru 60 in city limits (80 on designated arterials), Kerala 50
         * in municipal limits and 100 on 4-lane NHs, Delhi mostly 50. Most mapped cameras are
         * in cities, so the urban figure wins for the city classes.
         */
        fun guessKmh(roadClass: Char): Int = when (roadClass) {
            'M' -> 100    // motorway: expressways
            'T' -> 80     // trunk: national highways, often through towns
            'P' -> 60     // primary: arterials, state highways
            'S', 'R' -> 50  // secondary, tertiary
            else -> 0
        }
        private const val EARTH_R = 6_371_000.0
        const val CACHE = "cameras_downloaded.csv"

        @Volatile private var instance: CameraDb? = null

        fun get(ctx: Context): CameraDb = instance ?: synchronized(this) {
            instance ?: load(ctx).also { instance = it }
        }

        /** Rebuilds from disk. Call off the main thread — it parses the whole set. */
        fun reload(ctx: Context) {
            val fresh = load(ctx)
            synchronized(this) { instance = fresh }
        }

        private fun load(ctx: Context): CameraDb {
            val asset = runCatching { ctx.assets.open("cameras.csv").bufferedReader().readLines() }
                .getOrDefault(emptyList())
            fun rows(name: String): List<String> {
                val f = File(ctx.filesDir, name)
                return if (!f.exists()) emptyList()
                    else runCatching { f.readLines() }.getOrDefault(emptyList())
            }
            // Driver-entered limits come last so they win over anything downloaded.
            return build(
                asset.asSequence(),
                rows(CACHE).asSequence(),
                rows(LimitOverrides.FILE).asSequence()
            )
        }

        /** Pure builder: later sources add cameras and fill in gaps, never remove any. */
        fun build(vararg sources: Sequence<String>): CameraDb {
            // A camera present in both the asset and the download is one camera.
            class Row(val la: Double, val lo: Double, var li: Int, var speed: Boolean, var app: IntArray?)
            val seen = LinkedHashMap<Long, Row>(4096)
            fun parse(lines: Sequence<String>) {
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val p = line.split(',')
                    if (p.size < 3) continue
                    val la = p[0].trim().toDoubleOrNull() ?: continue
                    val lo = p[1].trim().toDoubleOrNull() ?: continue
                    val li = p[2].trim().toIntOrNull() ?: continue
                    val speed = p.size < 4 || p[3].trim() != "T"
                    val app = if (p.size >= 5) parseApproaches(p[4]) else null
                    // 1e5 is ~1 m, which is the same camera. Pack into separate halves of a
                    // long: an xor of overlapping bits silently merges distinct cameras once
                    // longitude passes 83.9 degrees, which is most of eastern India.
                    val key = (Math.round(la * 1e5) shl 32) or (Math.round(lo * 1e5) and 0xFFFFFFFFL)
                    val prev = seen[key]
                    if (prev == null) {
                        seen[key] = Row(la, lo, li, speed, app)
                    } else {
                        if (li > 0) prev.li = li                      // later sources correct earlier ones
                        if (speed) prev.speed = true                  // speed camera beats traffic
                        if (app != null) prev.app = app               // a newer sync knows the road
                    }
                }
            }
            sources.forEach(::parse)

            val rows = seen.values.toList()
            return CameraDb(
                DoubleArray(rows.size) { rows[it].la },
                DoubleArray(rows.size) { rows[it].lo },
                IntArray(rows.size) { rows[it].li },
                BooleanArray(rows.size) { rows[it].speed },
                Array(rows.size) { rows[it].app }
            )
        }

        /**
         * `87P;267P;175S40` -> heading 87 on a primary, heading 267 on the same, heading 175 on a
         * secondary mapped at 40. A bare heading carries no road, so no limit. Empty -> null.
         */
        private fun parseApproaches(field: String): IntArray? {
            val out = ArrayList<Int>()
            for (part in field.split(';')) {
                val m = APPROACH.matchEntire(part.trim()) ?: continue
                val mapped = m.groupValues[3].toIntOrNull() ?: 0
                val guess = m.groupValues[2].firstOrNull()?.let(::guessKmh) ?: 0
                out += m.groupValues[1].toInt() % 360
                out += if (mapped > 0) mapped else guess
                out += if (mapped == 0 && guess > 0) 1 else 0
            }
            return if (out.isEmpty()) null else out.toIntArray()
        }

        private val APPROACH = Regex("""(\d+)([A-Z]?)(\d*)""")

        /** Great-circle distance in metres (haversine). */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val s = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_R * atan2(sqrt(s), sqrt(1 - s))
        }

        /** Initial bearing in degrees, 0..360. */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
        }

        /** Signed smallest difference between two bearings, -180..180. */
        fun angleDiff(a: Float, b: Float): Float {
            var d = (a - b + 540f) % 360f - 180f
            if (d == -180f) d = 180f
            return d
        }
    }
}
