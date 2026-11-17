package com.ovra.dash

import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Records the GL output in back-to-back clips without gaps (MediaRecorder.setNextOutputFile).
 * maxClipSeconds = 0 means one clip until stop() (used for parking events).
 */
class SegmentRecorder(
    private val store: ClipStore,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int,
    private val audio: Boolean,
    private val maxClipSeconds: Int,
    private val prefix: String,
    private val lockedClips: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onClipStarted(file: File)
        fun onClipFinished(file: File)
    }

    private var recorder: MediaRecorder? = null
    @Volatile var current: File? = null; private set
    private var pendingNext: File? = null
    var surface: Surface? = null; private set
    @Volatile var startedAt = 0L; private set

    @Suppress("DEPRECATION")
    fun start(lat: Double?, lng: Double?): Surface {
        val first = store.newClip(prefix, lockedClips)
        val r = MediaRecorder()
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(first.absolutePath)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoEncodingBitRate(bitrate)
        r.setVideoFrameRate(30)
        r.setVideoSize(width, height)
        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
        }
        if (lat != null && lng != null) {
            try { r.setLocation(lat.toFloat(), lng.toFloat()) } catch (_: Exception) {}
        }
        if (maxClipSeconds > 0) {
            val bytes = (bitrate.toLong() + (if (audio) 128_000 else 0)) / 8 * maxClipSeconds
            r.setMaxFileSize(bytes)
        }
        r.setOnInfoListener { mr, what, _ ->
            try {
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                        val next = store.newClip(prefix, lockedClips)
                        pendingNext = next
                        mr.setNextOutputFile(next)
                    }
                    MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                        val done = current
                        current = pendingNext
                        pendingNext = null
                        startedAt = System.currentTimeMillis()
                        done?.let { listener.onClipFinished(it) }
                        current?.let { listener.onClipStarted(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "segment switch failed: ${e.message}")
            }
        }
        r.setOnErrorListener { _, what, extra -> Log.e(TAG, "recorder error $what/$extra") }
        r.prepare()
        surface = r.surface
        r.start()
        recorder = r
        current = first
        startedAt = System.currentTimeMillis()
        listener.onClipStarted(first)
        return r.surface
    }

    /** Lock the clip being recorded now. */
    fun lockCurrent(): File? {
        val c = current ?: return null
        val locked = store.lock(c)
        current = locked
        return locked
    }

    fun stop() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (e: Exception) {
            // stop() throws if almost nothing was recorded - drop that clip
            current?.let { if (it.length() < 1024) store.delete(it) }
        }
        try { r.release() } catch (_: Exception) {}
        pendingNext?.let { if (it.exists() && it.length() == 0L) it.delete() }
        current?.let { if (it.exists()) listener.onClipFinished(it) }
        current = null
        surface = null
    }

    companion object { private const val TAG = "OvraRec" }
}
