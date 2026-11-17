package com.ovra.dash

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** A loud rising/falling alarm tone on the alarm stream at full volume. */
class Siren(private val context: Context) {
    @Volatile private var playing = false
    private var thread: Thread? = null
    private var savedVolume = -1

    val isPlaying get() = playing

    fun start(seconds: Int) {
        if (playing) return
        playing = true
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}

        thread = Thread({
            val rate = 22050
            val buf = ShortArray(rate / 10)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.size * 4)
                .build()
            track.play()
            var phase = 0.0
            var t = 0.0
            val end = System.currentTimeMillis() + seconds * 1000L
            while (playing && System.currentTimeMillis() < end) {
                for (i in buf.indices) {
                    // sweep 650 Hz -> 1500 Hz and back every 1.2 s
                    val cycle = (t % 1.2) / 1.2
                    val f = 650 + 850 * (if (cycle < 0.5) cycle * 2 else (1 - cycle) * 2)
                    phase += 2 * PI * f / rate
                    buf[i] = (sin(phase) * 32000).toInt().toShort()
                    t += 1.0 / rate
                }
                track.write(buf, 0, buf.size)
            }
            try { track.stop() } catch (_: Exception) {}
            track.release()
            playing = false
            restoreVolume()
        }, "ovra-siren")
        thread?.start()
    }

    fun stop() {
        playing = false
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        try {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        } catch (_: Exception) {}
        savedVolume = -1
    }
}
