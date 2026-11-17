package com.ovra.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The always-running heart of Ovra Dash:
 *  STANDBY  - waiting for the car charger (or the Record button)
 *  DRIVING  - recording loop clips with overlay, crash detection and GPS
 *  ARMING   - grace period after parking before the guard switches on
 *  GUARD    - parking guard: watches cameras + motion sensor, records events, sounds the alarm
 */
class DashService : Service(), SegmentRecorder.Listener, SensorEventListener, LocationListener {

    enum class Mode { STANDBY, DRIVING, ARMING, GUARD }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var store: ClipStore
    private lateinit var sensors: SensorManager
    private lateinit var siren: Siren
    private lateinit var places: Places
    private var camRig: CameraRig? = null
    private var renderer: GlRenderer? = null
    private var recorder: SegmentRecorder? = null       // driving
    private var eventRecorder: SegmentRecorder? = null  // parking events
    private var wakeLock: PowerManager.WakeLock? = null
    private var videoW = 1920
    private var videoH = 1080

    // ---- state shown in the app ----
    @Volatile var mode = Mode.STANDBY; private set
    @Volatile var location: Location? = null; private set
    @Volatile var speedMps: Float? = null; private set
    @Volatile var roadName: String? = null; private set
    @Volatile var areaName: String? = null; private set
    @Volatile var currentClip: File? = null; private set
    @Volatile var lastEvent: String = ""; private set
    @Volatile var message: String = ""; private set
    @Volatile var alarmActive = false; private set
    @Volatile var sosSecondsLeft = 0; private set
    @Volatile var armingUntil = 0L; private set
    val dualActive get() = camRig?.insetRunning == true
    val recordingSince get() = recorder?.startedAt ?: 0L
    val eventRecording get() = eventRecorder != null

    private var previousClip: File? = null
    private var lockNext = false
    private var lastCrashAt = 0L
    private var gpsLog: FileWriter? = null
    private var stopRunnable: Runnable? = null
    private var armRunnable: Runnable? = null

    // guard detection
    private var gravity = FloatArray(3)
    private var gravityValid = false
    private var refGravity: FloatArray? = null
    private var guardReadyAt = 0L
    private var eventUntil = 0L
    private var eventStartedAt = 0L
    private var lastTextAt = 0L
    private var roadHits = 0
    private var cabinHits = 0
    private var strobeOn = false

    // ---------------- lifecycle ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = ClipStore(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        siren = Siren(this)
        places = Places(this) { road, area -> main.post { roadName = road; areaName = area; notifyUi() } }
        createChannels()
        startInForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, powerReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // always listen to the accelerometer (cheap) - used for orientation, crash and guard
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        main.post(ticker)
        if (prefs.autoStart && isCharging()) startDriving()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_RECORD -> startDriving()
            ACTION_STOP -> stopDriving(toGuard = false)
            ACTION_LOCK -> lockClips("Clip saved")
            ACTION_GUARD_ON -> armGuard(0)
            ACTION_GUARD_OFF -> disarmGuard()
            ACTION_SILENCE -> silenceAlarm()
            ACTION_CANCEL_SOS -> cancelSos()
        }
        notifyUi()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        sensors.unregisterListener(this)
        stopLocation()
        stopPipeline()
        siren.stop()
        places.shutdown()
        releaseWake()
        super.onDestroy()
    }

    // ---------------- notifications ----------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Recording status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alarms & crashes", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun pending(action: String?, req: Int): PendingIntent {
        val i = if (action == null) Intent(this, MainActivity::class.java)
        else Intent(this, MainActivity::class.java).setAction(action)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun servicePending(action: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, DashService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(): Notification {
        val (title, text) = when (mode) {
            Mode.STANDBY -> "Ovra Dash ready" to (if (prefs.autoStart) "Starts recording when the car charger is plugged in" else "Open the app to record")
            Mode.DRIVING -> "Recording" to (lastEvent.ifEmpty { "Road" + (if (dualActive) " + cabin" else "") + " camera" })
            Mode.ARMING -> "Parking guard arming" to "Switches on in ${((armingUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} s"
            Mode.GUARD -> (if (alarmActive) "ALARM" else "Parking guard on") to (lastEvent.ifEmpty { "Watching for movement" })
        }
        val b = NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending(null, 1))
        when (mode) {
            Mode.DRIVING -> {
                b.addAction(0, "Save clip", servicePending(ACTION_LOCK, 2))
                b.addAction(0, "Stop", servicePending(ACTION_STOP, 3))
            }
            Mode.GUARD, Mode.ARMING -> b.addAction(0, "Turn off guard", pending(MainActivity.ACTION_UNLOCK_GUARD, 4))
            Mode.STANDBY -> b.addAction(0, "Record", servicePending(ACTION_RECORD, 5))
        }
        return b.build()
    }

    private fun startInForeground() {
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) {
            if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (types == 0 && Build.VERSION.SDK_INT >= 34) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
        } catch (e: Exception) {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), 0)
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun updateNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }

    private fun alertNotification(title: String, text: String, action: String?) {
        val b = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending(action, 7))
            .setFullScreenIntent(pending(action, 8), true)
        if (action == MainActivity.ACTION_SOS) b.addAction(0, "I'm OK - cancel", servicePending(ACTION_CANCEL_SOS, 9))
        try { getSystemService(NotificationManager::class.java).notify(ALERT_ID, b.build()) } catch (_: Exception) {}
    }

    // ---------------- camera pipeline ----------------

    private fun videoSize(): Pair<Int, Int> = when (prefs.quality) {
        "720p" -> 1280 to 720
        "4k" -> 3840 to 2160
        else -> 1920 to 1080
    }

    private fun bitrate() = when (prefs.quality) { "720p" -> 6_000_000; "4k" -> 40_000_000; else -> 14_000_000 }

    /** Clockwise texture rotation so the picture is upright however the phone is mounted. */
    private fun videoRotation(): Int {
        val displayDeg = when (prefs.orientation) {
            "landscape" -> 90
            "landscape_flipped" -> 270
            else -> {
                if (!gravityValid) 90 else {
                    val gx = gravity[0]; val gy = gravity[1]
                    if (abs(gx) >= abs(gy)) (if (gx >= 0) 90 else 270) else (if (gy >= 0) 0 else 180)
                }
            }
        }
        return (360 - displayDeg) % 360
    }

    private fun startPipeline(lowPower: Boolean): Boolean {
        if (!granted(Manifest.permission.CAMERA)) {
            message = "Camera permission is needed - open Ovra Dash"
            return false
        }
        stopPipeline()
        val (w, h) = videoSize()
        videoW = w; videoH = h
        val r = GlRenderer(w, h)
        r.start()
        r.rotationDegrees = videoRotation()
        r.frontRotationDegrees = r.rotationDegrees
        val rig = CameraRig(this) { err -> main.post { message = err; notifyUi() } }
        val camMode = prefs.cameraMode
        val wantInset = camMode == "both" && !CameraRig.dualFailed
        r.showFront = wantInset
        r.motionListener = { rear, front -> main.post { onMotion(rear, front) } }
        renderer = r
        camRig = rig
        preview?.let { (st, pw, ph) -> r.setPreview(st, pw, ph) }
        rig.start(r.cameraSurface, if (wantInset) r.frontSurface else null, camMode == "cabin", lowPower)
        pushOverlay()
        return true
    }

    private fun stopPipeline() {
        stopEventRecording()
        recorder?.let { rec ->
            renderer?.setEncoderSurface(null)
            rec.stop()
        }
        recorder = null
        camRig?.let { it.stop(); it.release() }
        camRig = null
        val r = renderer
        renderer = null
        if (r != null) main.postDelayed({ r.release() }, 600)
    }

    // ---------------- driving ----------------

    fun startDriving() {
        cancelArm()
        stopRunnable?.let { main.removeCallbacks(it) }
        stopRunnable = null
        if (mode == Mode.DRIVING) return
        if (mode == Mode.GUARD) stopGuardInternal()
        if (!startPipeline(false)) { notifyUi(); return }
        try {
            val rec = SegmentRecorder(store, videoW, videoH, bitrate(), prefs.recordSound && granted(Manifest.permission.RECORD_AUDIO),
                prefs.clipMinutes * 60, "DASH", false, this)
            val surface = rec.start(location?.latitude, location?.longitude)
            renderer?.setEncoderSurface(surface)
            recorder = rec
        } catch (e: Exception) {
            message = "Couldn't start recording: ${e.message}"
            stopPipeline()
            notifyUi()
            return
        }
        mode = Mode.DRIVING
        lastEvent = ""
        message = ""
        startLocation()
        acquireWake()
        updateNotification()
        notifyUi()
    }

    fun stopDriving(toGuard: Boolean) {
        if (mode != Mode.DRIVING) return
        stopPipeline()
        closeGpsLog()
        mode = Mode.STANDBY
        currentClip = null
        releaseWake()
        stopLocation()
        // parked: switch on the Sentry light (independent of security / parking guard)
        if (toGuard && prefs.sentryAuto) prefs.sentryOn = true
        if (toGuard && prefs.guardAuto) armGuard(prefs.guardArmDelaySec) else updateNotification()
        notifyUi()
    }

    override fun onClipStarted(file: File) {
        currentClip = file
        if (lockNext) {
            lockNext = false
            recorder?.lockCurrent()?.let { currentClip = it }
        }
        openGpsLog(currentClip ?: file)
        val keep = setOfNotNull(currentClip?.absolutePath)
        val limit = prefs.storageGb.toLong() * 1_000_000_000L
        io.execute { store.enforce(limit, keep) }
        notifyUi()
    }

    override fun onClipFinished(file: File) {
        if (file.name.startsWith("DASH")) previousClip = file
        closeGpsLog()
        notifyUi()
    }

    /** Protect the current clip and the one before it (crash or "Save clip"). */
    fun lockClips(reason: String) {
        val rec = recorder ?: return
        rec.lockCurrent()?.let { currentClip = it }
        previousClip?.let { prev ->
            if (System.currentTimeMillis() - prev.lastModified() < 90_000) previousClip = store.lock(prev)
        }
        // if we're near the end of this clip, keep the next one as well
        if (System.currentTimeMillis() - rec.startedAt > prefs.clipMinutes * 60_000L - 20_000) lockNext = true
        lastEvent = "$reason · ${timeNow()}"
        pushOverlay()
        updateNotification()
        notifyUi()
    }

    // ---------------- crash SOS ----------------

    private val sosTick = object : Runnable {
        override fun run() {
            if (sosSecondsLeft <= 0) return
            sosSecondsLeft--
            notifyUi()
            if (sosSecondsLeft == 0) {
                val sent = Alerts.send(this@DashService, prefs.contactNumber,
                    "Ovra Dash: a possible crash was detected at ${timeNow()}. Location: ${Alerts.mapsLink(location?.latitude, location?.longitude)}")
                lastEvent = if (sent) "Crash alert text sent" else "Crash alert text could not be sent"
                notifyUi()
            } else main.postDelayed(this, 1000)
        }
    }

    private fun startSos() {
        if (!prefs.sosEnabled || prefs.contactNumber.isBlank() || sosSecondsLeft > 0) return
        sosSecondsLeft = 30
        alertNotification("Crash detected", "Texting your contact in 30 seconds - tap to cancel", MainActivity.ACTION_SOS)
        main.postDelayed(sosTick, 1000)
        notifyUi()
    }

    fun cancelSos() {
        sosSecondsLeft = 0
        main.removeCallbacks(sosTick)
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        notifyUi()
    }

    // ---------------- parking guard ----------------

    fun armGuard(delaySec: Int) {
        if (!prefs.securityEnabled) {
            lastEvent = "Security is off - parking guard unavailable"
            notifyUi(); return
        }
        if (mode == Mode.DRIVING) stopDriving(toGuard = false)
        cancelArm()
        if (delaySec <= 0) { startGuard(); return }
        mode = Mode.ARMING
        armingUntil = System.currentTimeMillis() + delaySec * 1000L
        armRunnable = Runnable { startGuard() }.also { main.postDelayed(it, delaySec * 1000L) }
        updateNotification()
        notifyUi()
    }

    private fun cancelArm() {
        armRunnable?.let { main.removeCallbacks(it) }
        armRunnable = null
        if (mode == Mode.ARMING) mode = Mode.STANDBY
    }

    private fun startGuard() {
        armRunnable = null
        if (batteryPercent() in 0 until prefs.guardMinBattery && !isCharging()) {
            mode = Mode.STANDBY
            lastEvent = "Guard not started - battery below ${prefs.guardMinBattery}%"
            updateNotification(); notifyUi(); return
        }
        if (!startPipeline(true)) { mode = Mode.STANDBY; notifyUi(); return }
        mode = Mode.GUARD
        lastEvent = ""
        alarmActive = false
        refGravity = null
        roadHits = 0; cabinHits = 0
        guardReadyAt = System.currentTimeMillis() + 6000   // let the cameras settle
        main.postDelayed({
            renderer?.resetMotion()
            renderer?.motionEnabled = true
            if (gravityValid) refGravity = gravity.copyOf()
        }, 5000)
        startLocation()
        acquireWake()
        updateNotification()
        notifyUi()
    }

    private fun stopGuardInternal() {
        silenceAlarm()
        stopPipeline()
        mode = Mode.STANDBY
        releaseWake()
        stopLocation()
    }

    /** Master switch from the app. Off = guard, alarm and alert texts stop straight away. */
    fun setSecurity(on: Boolean) {
        prefs.securityEnabled = on
        if (!on) {
            silenceAlarm()
            disarmGuard()
            lastEvent = "Security switched off"
        } else lastEvent = "Security switched on"
        updateNotification()
        notifyUi()
    }

    fun disarmGuard() {
        cancelArm()
        if (mode == Mode.GUARD) stopGuardInternal()
        mode = Mode.STANDBY
        updateNotification()
        notifyUi()
    }

    private fun guardThresholds(): Triple<Float, Float, Float> = when (prefs.guardSensitivity) {
        // road motion share, cabin motion share, bump in g
        "low" -> Triple(0.12f, 0.08f, 0.6f)
        "high" -> Triple(0.03f, 0.02f, 0.2f)
        else -> Triple(0.06f, 0.04f, 0.35f)
    }

    private fun onMotion(rear: Float, front: Float) {
        if (mode != Mode.GUARD || System.currentTimeMillis() < guardReadyAt) return
        val (roadT, cabinT, _) = guardThresholds()
        val cabinOnly = prefs.cameraMode == "cabin"
        val roadScore = if (cabinOnly) 0f else rear
        val cabinScore = if (cabinOnly) rear else front
        roadHits = if (roadScore > roadT) roadHits + 1 else 0
        cabinHits = if (cabinScore > cabinT) cabinHits + 1 else 0
        if (cabinHits >= 2) { cabinHits = 0; guardEvent("Movement inside the car", alarm = true) }
        else if (roadHits >= 2) { roadHits = 0; if (prefs.guardRecordRoad) guardEvent("Movement outside", alarm = false) }
    }

    private fun guardEvent(what: String, alarm: Boolean) {
        val now = System.currentTimeMillis()
        lastEvent = "$what · ${timeNow()}"
        // record (or extend) an event clip: at least 45 s, at most 3 min
        eventUntil = now + 45_000
        if (eventRecorder == null) startEventRecording()
        if (alarm && prefs.guardSiren && prefs.securityEnabled) startAlarm()
        if (prefs.guardTexts && now - lastTextAt > 10 * 60_000L) {
            lastTextAt = now
            io.execute {
                Alerts.send(this, prefs.contactNumber, "Ovra Dash parking alarm: $what at ${timeNow()}. ${Alerts.mapsLink(location?.latitude, location?.longitude)}")
            }
        }
        alertNotification(if (alarm) "Car alarm!" else "Parking guard", what, MainActivity.ACTION_UNLOCK_GUARD)
        updateNotification()
        notifyUi()
    }

    private fun startEventRecording() {
        val r = renderer ?: return
        try {
            camRig?.setLowPower(false)
            val rec = SegmentRecorder(store, videoW, videoH, bitrate(), prefs.recordSound && granted(Manifest.permission.RECORD_AUDIO),
                0, "PARK", true, this)
            r.setEncoderSurface(rec.start(location?.latitude, location?.longitude))
            eventRecorder = rec
            eventStartedAt = System.currentTimeMillis()
            pushOverlay()
        } catch (e: Exception) {
            message = "Event recording failed: ${e.message}"
        }
    }

    private fun stopEventRecording() {
        val rec = eventRecorder ?: return
        eventRecorder = null
        renderer?.setEncoderSurface(null)
        main.postDelayed({ rec.stop() }, 300)
        camRig?.setLowPower(true)
    }

    private fun startAlarm() {
        if (alarmActive) return
        alarmActive = true
        siren.start(30)
        main.post(strobe)
    }

    private val strobe = object : Runnable {
        override fun run() {
            if (!alarmActive || !siren.isPlaying) {
                alarmActive = false
                camRig?.setTorch(false)
                strobeOn = false
                notifyUi()
                return
            }
            strobeOn = !strobeOn
            camRig?.setTorch(strobeOn)
            main.postDelayed(this, 400)
        }
    }

    fun silenceAlarm() {
        alarmActive = false
        siren.stop()
        camRig?.setTorch(false)
        notifyUi()
    }

    // ---------------- sensors ----------------

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // low-pass gravity, high-pass = movement
        val a = 0.9f
        for (i in 0..2) gravity[i] = a * gravity[i] + (1 - a) * e.values[i]
        gravityValid = true
        val lx = e.values[0] - gravity[0]
        val ly = e.values[1] - gravity[1]
        val lz = e.values[2] - gravity[2]
        val g = sqrt(lx * lx + ly * ly + lz * lz) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()

        when (mode) {
            Mode.DRIVING -> if (g > prefs.crashThresholdG && now - lastCrashAt > 10_000) {
                lastCrashAt = now
                lockClips("Impact detected (%.1f g)".format(Locale.UK, g))
                if (g > maxOf(prefs.crashThresholdG * 1.6f, 3.5f)) startSos()
            }
            Mode.GUARD -> {
                if (now < guardReadyAt) return
                val (_, _, bumpT) = guardThresholds()
                if (g > bumpT && now - lastCrashAt > 5_000) {
                    lastCrashAt = now
                    guardEvent("Bump or knock detected", alarm = prefs.guardSirenOnBump)
                }
                val ref = refGravity
                if (ref != null && angleBetween(ref, gravity) > 12f && now - lastCrashAt > 5_000) {
                    lastCrashAt = now
                    refGravity = gravity.copyOf()
                    guardEvent("Phone moved or lifted", alarm = true)
                }
            }
            else -> {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na == 0f || nb == 0f) return 0f
        return Math.toDegrees(acos((dot / (na * nb)).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    // ---------------- GPS ----------------

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) {}
    }

    private fun stopLocation() {
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
        speedMps = null
    }

    override fun onLocationChanged(loc: Location) {
        val prev = location
        // prefer GPS fixes; only use network ones when GPS is stale
        if (loc.provider == LocationManager.NETWORK_PROVIDER && prev?.provider == LocationManager.GPS_PROVIDER &&
            loc.time - prev.time < 10_000) return
        location = loc
        speedMps = when {
            loc.hasSpeed() -> loc.speed
            prev != null && loc.time > prev.time -> loc.distanceTo(prev) / ((loc.time - prev.time) / 1000f)
            else -> null
        }
        places.update(loc)
        gpsLog?.let {
            try {
                val place = listOfNotNull(roadName, areaName).joinToString(" - ").replace("\"", "'")
                it.write("${loc.time},${loc.latitude},${loc.longitude},${speedMps ?: ""},${if (loc.hasBearing()) loc.bearing else ""},\"$place\"\n")
                it.flush()
            } catch (_: Exception) {}
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun openGpsLog(clip: File) {
        closeGpsLog()
        try {
            gpsLog = FileWriter(store.sidecar(clip)).apply { write("time_ms,lat,lng,speed_mps,bearing,place\n") }
        } catch (_: Exception) {}
    }

    private fun closeGpsLog() {
        try { gpsLog?.close() } catch (_: Exception) {}
        gpsLog = null
    }

    // ---------------- power ----------------

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    stopRunnable?.let { main.removeCallbacks(it) }
                    stopRunnable = null
                    if (prefs.autoStart && mode != Mode.DRIVING && mode != Mode.GUARD) startDriving()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    if (mode == Mode.DRIVING) {
                        val r = Runnable { stopDriving(toGuard = true) }
                        stopRunnable = r
                        main.postDelayed(r, prefs.stopDelaySec * 1000L)
                        lastEvent = "Charger unplugged - stopping in ${prefs.stopDelaySec} s"
                        updateNotification()
                        notifyUi()
                    }
                }
            }
        }
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.isCharging
    }

    fun batteryPercent(): Int = try {
        (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    // ---------------- misc ----------------

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OvraDash:record").apply { acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun timeNow() = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())

    private fun pushOverlay() {
        val r = renderer ?: return
        val tag = when {
            mode == Mode.GUARD || eventRecorder != null -> "PARKING"
            currentClip?.let { store.isLocked(it) } == true -> "SAVED"
            else -> null
        }
        r.setOverlay(Overlay.draw(videoW, videoH, speedMps, prefs.useMph, location?.latitude, location?.longitude, roadName, areaName, tag))
    }

    private var tickCount = 0
    private val ticker = object : Runnable {
        override fun run() {
            tickCount++
            if (renderer != null) pushOverlay()
            if (mode == Mode.GUARD) {
                val now = System.currentTimeMillis()
                if (eventRecorder != null && (now > eventUntil || now - eventStartedAt > 180_000)) stopEventRecording()
                if (tickCount % 60 == 0 && !isCharging() && batteryPercent() in 0 until prefs.guardMinBattery) {
                    stopGuardInternal()
                    lastEvent = "Guard switched off - battery below ${prefs.guardMinBattery}%"
                    updateNotification()
                }
            }
            if (mode == Mode.ARMING || tickCount % 5 == 0) updateNotification()
            notifyUi()
            main.postDelayed(this, 1000)
        }
    }

    private fun notifyUi() {
        uiListener?.let { l -> main.post { l() } }
    }

    companion object {
        const val ACTION_STANDBY = "com.ovra.dash.STANDBY"
        const val ACTION_RECORD = "com.ovra.dash.RECORD"
        const val ACTION_STOP = "com.ovra.dash.STOP"
        const val ACTION_LOCK = "com.ovra.dash.LOCK"
        const val ACTION_GUARD_ON = "com.ovra.dash.GUARD_ON"
        const val ACTION_GUARD_OFF = "com.ovra.dash.GUARD_OFF"
        const val ACTION_SILENCE = "com.ovra.dash.SILENCE"
        const val ACTION_CANCEL_SOS = "com.ovra.dash.CANCEL_SOS"
        private const val CH_STATUS = "status"
        private const val CH_ALERT = "alerts"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2

        @Volatile var instance: DashService? = null
            private set
        @Volatile var uiListener: (() -> Unit)? = null

        /** The on-screen preview, when the app is open. */
        @Volatile var preview: Triple<SurfaceTexture, Int, Int>? = null
            private set

        fun setPreview(st: SurfaceTexture?, w: Int, h: Int) {
            preview = if (st == null) null else Triple(st, w, h)
            instance?.renderer?.setPreview(st, w, h)
        }

        fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(context, Intent(context, DashService::class.java).setAction(action))
        }
    }
}
