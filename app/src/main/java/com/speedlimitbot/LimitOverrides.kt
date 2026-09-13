package com.speedlimitbot

import android.content.Context
import java.io.File

/**
 * Speed limits the driver supplied for cameras OpenStreetMap has no limit for.
 *
 * Stored as extra CSV rows in their own file, so [CameraDb] picks them up through the same
 * merge as downloads and a sync can never overwrite them.
 */
object LimitOverrides {

    const val FILE = "cameras_user.csv"

    /** Offered in the car, where a list has to be short enough to read at a glance. */
    val CHOICES = intArrayOf(50, 70, 80, 100, 120)

    fun save(ctx: Context, lat: Double, lon: Double, limitKmh: Int, speedCamera: Boolean) {
        val row = "%.6f,%.6f,%d,%s".format(lat, lon, limitKmh, if (speedCamera) "S" else "T")
        val file = File(ctx.filesDir, FILE)
        val rows = LinkedHashSet<String>()
        if (file.exists()) runCatching { file.forEachLine { if (it.isNotEmpty()) rows += it } }
        // A camera the driver re-labels should not keep its old row.
        val prefix = row.substringBeforeLast(',').substringBeforeLast(',')
        rows.removeAll { it.startsWith("$prefix,") }
        rows += row
        file.writeText(rows.joinToString("\n"))
        CameraDb.reload(ctx)
    }
}
