package com.ovra.dash

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen start-up video, played when the car charger is connected.
 * Black background, video centred at its own shape; tap to skip.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var video: VideoView
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        root.setOnClickListener { done() }

        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.welcome}"))
        if (Build.VERSION.SDK_INT >= 26) {
            // media stream, so it plays through the car speakers when the phone is connected by Bluetooth
            video.setAudioFocusRequest(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        video.setOnPreparedListener { mp: MediaPlayer ->
            if (!Prefs(this).welcomeSound) mp.setVolume(0f, 0f)
            mp.setOnInfoListener { _, _, _ -> false }
            video.start()
        }
        video.setOnCompletionListener { done() }
        video.setOnErrorListener { _, _, _ -> done(); true }
    }

    override fun onPause() {
        super.onPause()
        // if the user leaves (or the screen goes off), don't come back to a half-played video
        if (!isChangingConfigurations) done()
    }

    private fun done() {
        if (finished) return
        finished = true
        try { video.stopPlayback() } catch (_: Exception) {}
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
