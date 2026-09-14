package com.speedlimitbot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat

/**
 * Camera warnings as a messaging conversation, because that is the only surface Android Auto
 * gives a sideloaded app. The car draws the sender's avatar, so the avatar carries the colour:
 * amber approaching, red over the limit. Passing the camera cancels the conversation, which is
 * what puts the screen back to "no new messages".
 *
 * Alerting posts happen on state changes only. Posting per beep was considered and rejected:
 * the car reads the sender name aloud for each message, which would talk over the spoken
 * warning; the notification manager rate-limits a package that posts in a tight loop, so the
 * extra posts are dropped rather than shown; and a message list that grows several times a
 * second is precisely the reading-while-driving that the car's own rules exist to prevent.
 * Between state changes the same notification is updated silently, so the distance still counts
 * down without a new alert each time.
 */
object CarMessage {

    private const val TAG = "RadarMsg"
    private const val NOTE_ID = 3
    private const val ACTION_REPLY = "com.speedlimitbot.MSG_REPLY"
    private const val ACTION_READ = "com.speedlimitbot.MSG_READ"
    private const val KEY_REPLY = "reply"

    private val AMBER = Color.rgb(214, 152, 0)
    private val RED = Color.rgb(200, 36, 28)

    /** Last alerting state, so a silent update is not mistaken for a new warning. */
    private var lastAlert: String? = null

    /** Required by the car: both actions must exist, neither has anything to do. */
    private val sink = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = Unit
    }

    fun register(ctx: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_REPLY)
            addAction(ACTION_READ)
        }
        ctx.registerReceiver(
            sink, filter,
            if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        )
    }

    fun unregister(ctx: Context) {
        runCatching { ctx.unregisterReceiver(sink) }
        clear(ctx)
    }

    /**
     * @param alert true to alert the driver (a new state), false to update the text quietly.
     */
    fun show(ctx: Context, title: String, body: String, over: Boolean, alert: Boolean) {
        if (!allowed(ctx)) return
        if (alert) Log.i(TAG, "car message: $title | $body")
        val sender = Person.Builder()
            .setName(title)
            .setIcon(IconCompat.createWithBitmap(dot(if (over) RED else AMBER)))
            .setImportant(true)
            .build()

        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("Driver").build()
        )
            .setConversationTitle("SpeedLimitBot")
            .setGroupConversation(false)
            .addMessage(body, System.currentTimeMillis(), sender)

        val note = NotificationCompat.Builder(ctx, App.CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(if (over) RED else AMBER)
            .setColorized(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .addAction(replyAction(ctx))
            .addAction(readAction(ctx))
            .build()

        NotificationManagerCompat.from(ctx).notify(NOTE_ID, note)
        lastAlert = if (alert) body else lastAlert
    }

    fun clear(ctx: Context) {
        lastAlert = null
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTE_ID) }
    }

    private fun allowed(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun replyAction(ctx: Context): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            ctx, 10, Intent(ACTION_REPLY).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(0, "Reply", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setShowsUserInterface(false)
            .build()
    }

    private fun readAction(ctx: Context): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            ctx, 11, Intent(ACTION_READ).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(0, "Mark as read", pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /** A flat colour disc; at avatar size that reads as a warning light, which is the point. */
    private fun dot(color: Int): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawCircle(
            size / 2f, size / 2f, size / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        )
        return bmp
    }
}
