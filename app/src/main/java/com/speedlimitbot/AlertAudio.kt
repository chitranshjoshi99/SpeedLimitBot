package com.speedlimitbot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Beeps and the audio focus they ride on.
 *
 * Everything plays as USAGE_ASSISTANCE_NAVIGATION_GUIDANCE. Focus is GAIN_TRANSIENT, not
 * MAY_DUCK: ducking only dips the music, and over a car stereo a dipped song still buries a
 * beep. Other apps pause while a warning is audible and resume by themselves when focus is
 * released — that resume is the system's job, so it survives the app being killed mid-alert.
 * Focus is held only while something is actually sounding (speech or beeps), not for the whole
 * approach, so the music is not stopped for a minute of silence.
 *
 * Each pattern is one pre-rendered buffer — tone then silence — looped by the mixer. The old
 * version built a fresh AudioTrack per beep and kept it only in a local: the native side holds
 * a Java track weakly, so the collector could finalize it mid-beep and the beep never played.
 * Two long-lived tracks cannot be collected, and cannot leak either.
 */
class AlertAudio(ctx: Context) {

    private val am = ctx.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var pattern = AlertEngine.Beep.NONE
    private var speaking = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val slow by lazy { loop(freq = 880.0, toneMs = 120, gapMs = 900) }
    private val fast by lazy { loop(freq = 1320.0, toneMs = 70, gapMs = 180) }

    fun setPattern(p: AlertEngine.Beep) {
        if (p == pattern) return
        pattern = p
        runCatching {
            slow?.silence()
            fast?.silence()
            when (p) {
                AlertEngine.Beep.SLOW -> slow?.start()
                AlertEngine.Beep.FAST -> fast?.start()
                AlertEngine.Beep.NONE -> Unit
            }
        }.onFailure { Log.i(TAG, "beep failed: $it") }
        syncFocus()
    }

    /** Speech runs through TTS, which takes no focus of its own; the service reports it here. */
    fun setSpeaking(on: Boolean) {
        speaking = on
        syncFocus()
    }

    private fun syncFocus() {
        if (speaking || pattern != AlertEngine.Beep.NONE) takeFocus() else releaseFocus()
    }

    private fun takeFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            // Another app taking focus back must not leave us believing we still hold it, or the
            // next warning skips the request and plays under the music.
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) focus = null
            }
            .build()
        am.requestAudioFocus(req)
        focus = req
    }

    private fun releaseFocus() {
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    fun release() {
        runCatching { slow?.release(); fast?.release() }
        releaseFocus()
    }

    private fun AudioTrack.start() {
        // A static track restarts from where it was paused, so rewind and re-arm the loop.
        playbackHeadPosition = 0
        setLoopPoints(0, bufferSizeInFrames, -1)
        play()
    }

    private fun AudioTrack.silence() {
        if (playState == AudioTrack.PLAYSTATE_PLAYING) pause()
    }

    /** Sine burst with a short fade so it does not click, then silence; null if the mixer refuses. */
    private fun loop(freq: Double, toneMs: Int, gapMs: Int): AudioTrack? = runCatching {
        val n = SAMPLE_RATE * toneMs / 1000
        val fade = SAMPLE_RATE / 500
        val pcm = ShortArray(SAMPLE_RATE * (toneMs + gapMs) / 1000)
        for (i in 0 until n) {
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            pcm[i] = (sin(2 * PI * freq * i / SAMPLE_RATE) * env * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        AudioTrack.Builder()
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
            .apply {
                write(pcm, 0, pcm.size)
                setVolume(AudioTrack.getMaxVolume())   // our own gain, never the user's volume
            }
    }.onFailure { Log.i(TAG, "could not build beep track: $it") }.getOrNull()

    companion object {
        private const val TAG = "Radar"
        private const val SAMPLE_RATE = 44_100
    }
}
