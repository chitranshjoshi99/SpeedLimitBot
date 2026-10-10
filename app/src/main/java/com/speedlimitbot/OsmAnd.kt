package com.speedlimitbot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Shows the camera set on OsmAnd's map, so a driver navigating with OsmAnd sees what this app
 * will warn about — on the phone and, with OsmAnd's paid Android Auto mode, on the car screen.
 *
 * One call does it: `importGpx` with the cameras as GPX waypoints. OsmAnd stores the file,
 * selects it, and redraws. Nothing flows back — OsmAnd's API exposes no current road or route
 * geometry — so alerts stay this app's own, and OsmAnd not being installed changes nothing.
 *
 * ponytail: raw Binder transact instead of OsmAnd's API artifact, which lives on OsmAnd's own
 * ivy server and would pull in ~100 parcelables for one method. The transaction code is the
 * method's position in IOsmAndAidlInterface.aidl; OsmAnd only appends to that file because every
 * existing client depends on the order. If a release ever breaks it, the call fails and is logged.
 */
object OsmAnd {

    private const val TAG = "RadarOsmAnd"
    private const val ACTION = "net.osmand.aidl.OsmandAidlServiceV2"
    private const val DESCRIPTOR = "net.osmand.aidlapi.IOsmAndAidlInterface"
    /** `importGpx` is the 13th method declared. */
    private const val IMPORT_GPX = IBinder.FIRST_CALL_TRANSACTION + 12
    /** OsmAnd from Play (paid and free), and the F-Droid build. */
    private val PACKAGES = listOf("net.osmand.plus", "net.osmand")
    const val FILE = "SpeedLimitBot cameras.gpx"

    /**
     * Keeps one binder transaction well under its 1 MB limit: ~150 bytes per waypoint, twice that
     * as UTF-16 in the parcel. Nearest first, so the cut falls far from the driver.
     */
    private const val MAX_POINTS = 2000

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "osmand") }

    fun installed(ctx: Context) = PACKAGES.any { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /** Sends the cameras nearest (lat, lon). A no-op without OsmAnd. [done] gets a result line. */
    fun push(ctx: Context, lat: Double, lon: Double, done: (String) -> Unit = {}) {
        val pkg = PACKAGES.firstOrNull { p ->
            runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess
        } ?: return done("OsmAnd is not installed")
        val app = ctx.applicationContext
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                pool.execute {
                    val msg = try {
                        val cams = CameraDb.get(app).nearest(lat, lon, MAX_POINTS)
                        if (importGpx(binder, gpx(cams))) "Sent ${cams.size} cameras to OsmAnd"
                            else "OsmAnd refused the cameras"
                    } catch (e: Exception) {
                        Log.i(TAG, "push failed: $e")
                        "Could not reach OsmAnd"
                    } finally {
                        runCatching { app.unbindService(this) }
                    }
                    Log.i(TAG, msg)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { done(msg) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = runCatching {
            app.bindService(Intent(ACTION).setPackage(pkg), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) done("Could not reach OsmAnd")
    }

    private fun importGpx(binder: IBinder, gpx: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(1)                                   // non-null parcelable follows
            data.writeBundle(Bundle().apply {                  // ImportGpxParams.writeToBundle
                putString("sourceRawData", gpx)
                putString("destinationPath", FILE)
                putString("color", "red")
                putBoolean("show", true)
            })
            binder.transact(IMPORT_GPX, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Waypoints named by limit, so the map says "60" or "?" beside each camera. */
    internal fun gpx(cams: List<CameraDb.Hit>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="SpeedLimitBot" xmlns="http://www.topografix.com/GPX/1/1" xmlns:osmand="https://osmand.net">""")
        append('\n')
        for (c in cams) {
            append(String.format(Locale.US, """<wpt lat="%.6f" lon="%.6f">""", c.lat, c.lon))
            append("<name>").append(if (c.limitKmh > 0) c.limitKmh.toString() else "?").append("</name>")
            append("<type>").append(if (c.speedCamera) "Speed camera" else "Traffic camera").append("</type>")
            append("<extensions><osmand:icon>highway_speed_camera</osmand:icon>")
            append("<osmand:color>").append(if (c.speedCamera) "#ffc82424" else "#ffd69800").append("</osmand:color>")
            append("</extensions></wpt>\n")
        }
        append("</gpx>\n")
    }
}
