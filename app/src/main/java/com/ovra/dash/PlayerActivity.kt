package com.ovra.dash

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import android.view.Gravity

/** Plays clips inside the app so they stay private. */
class PlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root)
        val mc = MediaController(this)
        mc.setAnchorView(video)
        video.setMediaController(mc)
        video.setVideoPath(path)
        video.setOnPreparedListener { video.start(); mc.show(3000) }
    }
}
