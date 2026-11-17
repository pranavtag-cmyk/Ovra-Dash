package com.ovra.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var idleText: TextView
    private lateinit var modeChip: TextView
    private lateinit var speedChip: TextView
    private lateinit var gpsChip: TextView
    private lateinit var storageChip: TextView
    private lateinit var messageText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnSave: Button
    private lateinit var btnGuard: Button
    private lateinit var alertPanel: View
    private lateinit var alertTitle: TextView
    private lateinit var alertText: TextView
    private lateinit var alertButton: Button
    private lateinit var prefs: Prefs
    private lateinit var store: ClipStore
    private var storageText = ""
    private var lastStorageCheck = 0L

    // dark screen
    private lateinit var darkOverlay: View
    private lateinit var statusDot: View
    private lateinit var flashLayer: View
    private lateinit var btnSecurity: Button
    private var dark = false
    private var lastTouch = System.currentTimeMillis()
    private var dotStyle = ""
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        store = ClipStore(this)

        preview = findViewById(R.id.preview)
        idleText = findViewById(R.id.idleText)
        modeChip = findViewById(R.id.modeChip)
        speedChip = findViewById(R.id.speedChip)
        gpsChip = findViewById(R.id.gpsChip)
        storageChip = findViewById(R.id.storageChip)
        messageText = findViewById(R.id.messageText)
        btnRecord = findViewById(R.id.btnRecord)
        btnSave = findViewById(R.id.btnSave)
        btnGuard = findViewById(R.id.btnGuard)
        alertPanel = findViewById(R.id.alertPanel)
        alertTitle = findViewById(R.id.alertTitle)
        alertText = findViewById(R.id.alertText)
        alertButton = findViewById(R.id.alertButton)
        darkOverlay = findViewById(R.id.darkOverlay)
        statusDot = findViewById(R.id.statusDot)
        flashLayer = findViewById(R.id.flashLayer)
        btnSecurity = findViewById(R.id.btnSecurity)

        if (Build.VERSION.SDK_INT >= 28) {
            // draw right up to the camera notch so the status light can sit next to the lens
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        findViewById<Button>(R.id.btnDark).setOnClickListener { enterDark() }
        darkOverlay.setOnClickListener { exitDark() }
        btnSecurity.setOnClickListener { toggleSecurity() }

        btnRecord.setOnClickListener {
            val s = DashService.instance
            if (s?.mode == DashService.Mode.DRIVING) DashService.send(this, DashService.ACTION_STOP)
            else DashService.send(this, DashService.ACTION_RECORD)
        }
        btnSave.setOnClickListener { DashService.send(this, DashService.ACTION_LOCK) }
        btnGuard.setOnClickListener {
            when (DashService.instance?.mode) {
                DashService.Mode.GUARD, DashService.Mode.ARMING ->
                    Security.require(this, "Turn off parking guard") { DashService.send(this, DashService.ACTION_GUARD_OFF) }
                else -> AlertDialog.Builder(this)
                    .setTitle("Parking guard")
                    .setMessage("Switch on now, or in ${prefs.guardArmDelaySec / 60.0} minutes so you can leave the car first?")
                    .setPositiveButton("After delay") { _, _ -> DashService.instance?.armGuard(prefs.guardArmDelaySec) }
                    .setNeutralButton("Now") { _, _ -> DashService.send(this, DashService.ACTION_GUARD_ON) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        findViewById<Button>(R.id.btnClips).setOnClickListener {
            if (prefs.lockClips) Security.require(this, "Open clips", rememberMinutes = 5) { startActivity(Intent(this, ClipsActivity::class.java)) }
            else startActivity(Intent(this, ClipsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            Security.require(this, "Open settings", rememberMinutes = 5) { startActivity(Intent(this, SettingsActivity::class.java)) }
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { DashService.setPreview(st, w, h) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { DashService.setPreview(st, w, h) }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { DashService.setPreview(null, 0, 0); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        findViewById<View>(R.id.root).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }

        requestPermissionsIfNeeded()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.action) {
            ACTION_UNLOCK_GUARD -> Security.require(this, "Turn off parking guard") {
                DashService.send(this, DashService.ACTION_SILENCE)
                DashService.send(this, DashService.ACTION_GUARD_OFF)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        lastTouch = System.currentTimeMillis()
        DashService.uiListener = { refresh() }
        refresh()
        ui.post(blinker)
    }

    override fun onPause() {
        super.onPause()
        DashService.uiListener = null
        ui.removeCallbacks(blinker)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = System.currentTimeMillis()
        if (dark) {
            if (ev.action == MotionEvent.ACTION_UP) exitDark()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------- dark screen ----------------
    // The X2 Pro has an AMOLED screen: black pixels are switched off, so a black screen with one
    // small light looks (and costs) like a switched-off screen.

    private fun enterDark() {
        if (dark) return
        dark = true
        darkOverlay.visibility = View.VISIBLE
        DashService.setPreview(null, 0, 0)   // stop drawing the preview while dark
        dotStyle = ""
        applyDarkBrightness()
    }

    private fun exitDark() {
        if (!dark) return
        dark = false
        lastTouch = System.currentTimeMillis()
        darkOverlay.visibility = View.GONE
        flashLayer.visibility = View.INVISIBLE
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        preview.surfaceTexture?.let { DashService.setPreview(it, preview.width, preview.height) }
    }

    private fun applyDarkBrightness() {
        val guard = DashService.instance?.mode.let { it == DashService.Mode.GUARD || it == DashService.Mode.ARMING }
        // a little brighter in parking mode so the warning light is visible from outside
        window.attributes = window.attributes.apply { screenBrightness = if (guard) 0.35f else 0.05f }
    }

    /** Puts the light next to the camera notch (recording) or in that corner (parking guard). */
    private fun placeDot(style: String) {
        val d = resources.displayMetrics.density
        val size = ((if (style == "rec") 9 else 18) * d).toInt()
        val root = findViewById<View>(R.id.root)
        val w = root.width.toFloat(); val h = root.height.toFloat()
        var cx = 28 * d; var cy = 28 * d
        var onRight = false
        if (Build.VERSION.SDK_INT >= 28) {
            root.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull()?.let { r ->
                onRight = r.centerX() > w / 2
                if (style == "rec") {
                    cy = r.centerY().toFloat()
                    cx = if (onRight) r.left - 12 * d else r.right + 12 * d
                }
            }
        }
        if (style != "rec") {
            cx = if (onRight) w - 30 * d else 30 * d
            cy = 30 * d
        }
        // nudge a little every few minutes so the same pixels aren't always lit
        val shift = ((System.currentTimeMillis() / 180_000) % 3 - 1) * 2 * d
        statusDot.layoutParams = (statusDot.layoutParams as FrameLayout.LayoutParams).apply { width = size; height = size }
        statusDot.x = cx - size / 2f + shift
        statusDot.y = cy - size / 2f + shift
        statusDot.setBackgroundResource(if (style == "rec") R.drawable.rec_dot else R.drawable.led_dot)
    }

    private val blinker = object : Runnable {
        override fun run() {
            ui.postDelayed(this, 60)
            if (!dark) return
            val s = DashService.instance
            val mode = s?.mode ?: DashService.Mode.STANDBY
            val t = System.currentTimeMillis()
            val style = when {
                s?.alarmActive == true -> "alarm"
                mode == DashService.Mode.GUARD || mode == DashService.Mode.ARMING -> "guard"
                mode == DashService.Mode.DRIVING -> "rec"
                else -> "none"
            }
            if (style != dotStyle) { dotStyle = style; placeDot(style); applyDarkBrightness() }
            when (style) {
                // recording: slow, steady blink like a camera's REC light
                "rec" -> { statusDot.visibility = if (t % 1600 < 800) View.VISIBLE else View.INVISIBLE; flashLayer.visibility = View.INVISIBLE }
                // parking guard: short double flash every 2 s, like a car alarm LED
                "guard" -> {
                    val p = t % 2000
                    statusDot.visibility = if (p < 110 || p in 260..370) View.VISIBLE else View.INVISIBLE
                    flashLayer.visibility = View.INVISIBLE
                }
                // alarm going off: fast flashing light and red screen flashes
                "alarm" -> {
                    val on = t % 300 < 150
                    statusDot.visibility = if (on) View.VISIBLE else View.INVISIBLE
                    flashLayer.visibility = if (on) View.VISIBLE else View.INVISIBLE
                }
                else -> { statusDot.visibility = View.INVISIBLE; flashLayer.visibility = View.INVISIBLE }
            }
        }
    }

    // ---------------- security switch ----------------

    private fun toggleSecurity() {
        if (prefs.securityEnabled) {
            Security.require(this, "Turn off security") {
                AlertDialog.Builder(this)
                    .setTitle("Turn off security?")
                    .setMessage("Parking guard, the alarm, alert texts and PIN locks will all be off until you turn security back on. Crash detection keeps working.")
                    .setPositiveButton("Turn off") { _, _ -> setSecurity(false) }
                    .setNegativeButton("Cancel", null).show()
            }
        } else setSecurity(true)
    }

    private fun setSecurity(on: Boolean) {
        val s = DashService.instance
        if (s != null) s.setSecurity(on) else prefs.securityEnabled = on
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun fitPreview() {
        val root = findViewById<View>(R.id.root)
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        var pw = w; var ph = w * 9 / 16
        if (ph > h) { ph = h; pw = h * 16 / 9 }
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        if (lp.width != pw || lp.height != ph) { lp.width = pw; lp.height = ph; preview.layoutParams = lp }
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val s = DashService.instance
        val mode = s?.mode ?: DashService.Mode.STANDBY
        val now = System.currentTimeMillis()

        when (mode) {
            DashService.Mode.DRIVING -> {
                val secs = ((now - (s?.recordingSince ?: now)) / 1000).coerceAtLeast(0)
                modeChip.text = "● REC " + String.format(Locale.UK, "%02d:%02d", secs / 60, secs % 60) + (if (s?.dualActive == true) " · 2 cams" else "")
                modeChip.setTextColor(0xFFFF5A4E.toInt())
                btnRecord.text = "STOP"
                idleText.visibility = View.GONE
            }
            DashService.Mode.GUARD -> {
                modeChip.text = if (s?.alarmActive == true) "ALARM" else if (s?.eventRecording == true) "● GUARD REC" else "GUARD ON"
                modeChip.setTextColor(0xFF3DDC97.toInt())
                btnRecord.text = "REC"
                idleText.visibility = View.GONE
            }
            DashService.Mode.ARMING -> {
                val left = ((s?.armingUntil ?: now) - now) / 1000
                modeChip.text = "GUARD IN ${left.coerceAtLeast(0)}s"
                modeChip.setTextColor(0xFFFFB547.toInt())
                btnRecord.text = "REC"
                idleText.visibility = View.VISIBLE
                idleText.text = "Parking guard switches on in ${left.coerceAtLeast(0)} s"
            }
            DashService.Mode.STANDBY -> {
                modeChip.text = "STANDBY"
                modeChip.setTextColor(0xFFC9CCD6.toInt())
                btnRecord.text = "REC"
                idleText.visibility = View.VISIBLE
                idleText.text = if (prefs.autoStart) "Ready\nRecording starts when the car charger is plugged in" else "Ready\nPress REC to start"
            }
        }
        btnSave.isEnabled = mode == DashService.Mode.DRIVING
        btnSave.alpha = if (btnSave.isEnabled) 1f else 0.4f
        btnGuard.text = if (mode == DashService.Mode.GUARD || mode == DashService.Mode.ARMING) "Guard: ON" else "Parking guard"
        val secOn = prefs.securityEnabled
        btnSecurity.text = if (secOn) "Security: ON" else "Security: OFF"
        btnSecurity.setTextColor(if (secOn) 0xFF3DDC97.toInt() else 0xFFFFB547.toInt())
        btnGuard.isEnabled = secOn
        btnGuard.alpha = if (secOn) 1f else 0.4f

        // go dark by itself while recording or guarding, after a while without touches
        val delay = prefs.darkAfterSec
        if (!dark && delay > 0 && mode != DashService.Mode.STANDBY && alertPanel.visibility != View.VISIBLE &&
            now - lastTouch > delay * 1000L) enterDark()

        val sp = s?.speedMps
        speedChip.text = if (sp == null) "-- ${if (prefs.useMph) "mph" else "km/h"}"
        else "${(if (prefs.useMph) sp * 2.23694f else sp * 3.6f).toInt()} ${if (prefs.useMph) "mph" else "km/h"}"
        val loc = s?.location
        gpsChip.text = if (loc == null) "No GPS" else "GPS ±${loc.accuracy.toInt()} m"
        gpsChip.setTextColor(if (loc == null) 0xFFFFB547.toInt() else 0xFF3DDC97.toInt())

        if (now - lastStorageCheck > 5000) {
            lastStorageCheck = now
            val usedGb = store.usedBytes() / 1e9
            storageText = String.format(Locale.UK, "%.1f / %d GB", usedGb, prefs.storageGb)
        }
        storageChip.text = storageText

        val msg = listOfNotNull(s?.message?.takeIf { it.isNotEmpty() }, s?.lastEvent?.takeIf { it.isNotEmpty() }).joinToString("\n")
        messageText.text = msg
        messageText.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE

        // alarm / SOS panel
        when {
            (s?.sosSecondsLeft ?: 0) > 0 -> {
                alertPanel.visibility = View.VISIBLE
                alertTitle.text = "Crash detected"
                alertText.text = "Texting your emergency contact in ${s!!.sosSecondsLeft} s"
                alertButton.text = "I'm OK - cancel"
                alertButton.setOnClickListener { DashService.send(this, DashService.ACTION_CANCEL_SOS) }
            }
            s?.alarmActive == true -> {
                alertPanel.visibility = View.VISIBLE
                alertTitle.text = "ALARM"
                alertText.text = s.lastEvent
                alertButton.text = "Silence"
                alertButton.setOnClickListener {
                    Security.require(this, "Silence the alarm") { DashService.send(this, DashService.ACTION_SILENCE) }
                }
            }
            else -> alertPanel.visibility = View.GONE
        }
    }

    // ---------------- permissions ----------------

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        else startServiceAndChecks()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startServiceAndChecks()
    }

    @SuppressLint("BatteryLife")
    private fun startServiceAndChecks() {
        // started while the app is open, so Android lets it use the camera, mic and GPS in the background
        DashService.send(this, DashService.ACTION_STANDBY)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        if (!prefs.sp.getBoolean("shown_background_tip", false)) {
            prefs.sp.edit().putBoolean("shown_background_tip", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Keep Ovra Dash running")
                .setMessage("realme phones close background apps to save battery. For the dashcam to start by itself:\n\n" +
                        "• Settings → Battery → App battery management → Ovra Dash → allow Background activity and Auto launch\n" +
                        "• Lock Ovra Dash in Recent apps (swipe down on its card)\n\n" +
                        "Open the app once after every phone restart.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    companion object {
        const val ACTION_UNLOCK_GUARD = "com.ovra.dash.UNLOCK_GUARD"
        const val ACTION_SOS = "com.ovra.dash.SOS"
    }
}
