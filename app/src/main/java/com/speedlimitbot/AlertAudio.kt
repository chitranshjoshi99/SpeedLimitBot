package com.speedlimitbot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Beeps and the audio focus they ride on.
 *
 * Everything plays as USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, which is the usage car systems
 * treat as guidance: it mixes over media rather than competing with it. Focus is taken as
 * GAIN_TRANSIENT_MAY_DUCK so music drops while a camera is live and comes back by itself the
 * moment focus is released — the reset is the system's job, not ours, which is why it survives
 * the app being killed mid-alert.
 *
 * Beeps are synthesised rather than played through ToneGenerator because ToneGenerator only
 * accepts a legacy stream type; it cannot carry navigation-guidance attributes, so its output
 * would fight the music instead of ducking it.
 */
class AlertAudio(private val ctx: Context) {

    private val am = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var pattern: AlertEngine.Beep = AlertEngine.Beep.NONE

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** One pre-rendered burst per urgency; short enough to allocate once and keep. */
    private val slowTone by lazy { tone(freq = 880.0, ms = 120) }
    private val fastTone by lazy { tone(freq = 1320.0, ms = 70) }

    private val beeper = object : Runnable {
        override fun run() {
            val (pcm, gap) = when (pattern) {
                AlertEngine.Beep.FAST -> fastTone to 180
                AlertEngine.Beep.SLOW -> slowTone to 900
                AlertEngine.Beep.NONE -> return
            }
            play(pcm)
            handler.postDelayed(this, (pcm.size / (SAMPLE_RATE / 1000) + gap).toLong())
        }
    }

    fun setPattern(p: AlertEngine.Beep) {
        if (p == pattern) return
        pattern = p
        handler.removeCallbacks(beeper)
        if (p == AlertEngine.Beep.NONE) releaseFocus() else {
            takeFocus()
            handler.post(beeper)
        }
    }

    /** Held across the whole alert so speech and beeps do not fight over it. */
    fun takeFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        am.requestAudioFocus(req)
        focus = req
    }

    private fun releaseFocus() {
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun release() {
        handler.removeCallbacks(beeper)
        releaseFocus()
    }

    private fun play(pcm: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setVolume(AudioTrack.getMaxVolume())   // our own gain, never the user's music volume
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
    }

    /** Sine burst with a short fade so it does not click. */
    private fun tone(freq: Double, ms: Int): ShortArray {
        val n = SAMPLE_RATE * ms / 1000
        val fade = SAMPLE_RATE / 500
        return ShortArray(n) { i ->
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            (sin(2 * PI * freq * i / SAMPLE_RATE) * env * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
    }
}
