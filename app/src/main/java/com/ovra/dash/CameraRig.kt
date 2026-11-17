package com.ovra.dash

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface

/**
 * Opens the main camera (road = back, or cabin = front) and, if asked and the phone copes,
 * the other one as an inset. If running both crashes the camera system, it falls back to
 * the main camera only and remembers that for the rest of the session.
 */
class CameraRig(context: Context, private val onMessage: (String) -> Unit) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("ovra-cam").apply { start() }
    private val handler = Handler(thread.looper)

    private class Cam(val id: String, val surface: Surface, val isMain: Boolean) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var fps: Range<Int>? = null
        var stabilization = false
        var hasFlash = false
    }

    private var main: Cam? = null
    private var inset: Cam? = null
    private var generation = 0
    @Volatile private var torch = false
    @Volatile private var lowPower = false

    // remembered so a restart doesn't trip over the same failure again
    private var mainSurface: Surface? = null
    private var insetSurface: Surface? = null
    private var mainFront = false
    private var mainRetries = 0

    @Volatile var insetRunning = false; private set

    private val backId: String? get() = idFor(CameraCharacteristics.LENS_FACING_BACK)
    private val frontId: String? get() = idFor(CameraCharacteristics.LENS_FACING_FRONT)

    private fun idFor(facing: Int): String? = try {
        manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
    } catch (_: Exception) { null }

    /** True when Android reports that this phone can stream the back and front cameras together. */
    fun supportsDual(): Boolean {
        val r = backId ?: return false
        val f = frontId ?: return false
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            manager.concurrentCameraIds.any { set -> set.contains(r) && set.contains(f) }
        } catch (_: Exception) { false }
    }

    /** mainFront = record the front (selfie) camera full-frame. insetSurface = also run the other camera. */
    fun start(mainSurface: Surface, insetSurface: Surface?, mainFront: Boolean, lowPower: Boolean) {
        handler.post {
            this.mainSurface = mainSurface
            this.insetSurface = if (dualFailed) null else insetSurface
            this.mainFront = mainFront
            this.lowPower = lowPower
            mainRetries = 0
            openAll()
        }
    }

    private fun openAll() {
        stopInternal()
        val gen = ++generation
        val mainId = if (mainFront) frontId ?: backId else backId ?: frontId
        val ms = mainSurface ?: return
        if (mainId == null) { onMessage("No camera found"); return }
        main = Cam(mainId, ms, true).also { open(it, gen) }
        // the inset is opened once the main camera is running (see onConfigured)
    }

    private fun openInset(gen: Int) {
        val s = insetSurface ?: return
        if (dualFailed) return
        val id = (if (mainFront) backId else frontId) ?: return
        if (id == main?.id) return
        inset = Cam(id, s, false).also { open(it, gen) }
    }

    /** Both cameras together didn't work: carry on with the main camera only. */
    private fun fallBackToSingle() {
        if (dualFailed) return
        dualFailed = true
        insetSurface = null
        Log.w(TAG, "dual camera failed - main camera only")
        onMessage("This phone can't run both cameras at once - recording one camera")
        handler.postDelayed({ openAll() }, 800)
    }

    fun stop() = handler.post { stopInternal() }

    fun release() = handler.post {
        stopInternal()
        thread.quitSafely()
    }

    fun setLowPower(on: Boolean) = handler.post {
        lowPower = on
        main?.let { apply(it) }
        inset?.let { apply(it) }
    }

    fun setTorch(on: Boolean) = handler.post {
        torch = on
        main?.let { apply(it) }
        inset?.let { apply(it) }
    }

    @SuppressLint("MissingPermission")
    private fun open(cam: Cam, gen: Int) {
        try {
            val ch = manager.getCameraCharacteristics(cam.id)
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            cam.fps = ranges.filter { it.upper == 30 }.maxByOrNull { it.lower } ?: ranges.maxByOrNull { it.upper }
            val modes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            cam.stabilization = modes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            cam.hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

            manager.openCamera(cam.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (gen != generation) { device.close(); return }
                    cam.device = device
                    createSession(cam, gen)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cam.device = null
                    if (gen == generation) onCameraFailed(cam, -1)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cam.device = null
                    if (gen == generation) onCameraFailed(cam, error)
                }
            }, handler)
        } catch (e: Exception) {
            if (gen == generation) onCameraFailed(cam, -2)
        }
    }

    private fun onCameraFailed(cam: Cam, error: Int) {
        Log.w(TAG, "camera ${cam.id} (main=${cam.isMain}) failed: $error")
        if (!cam.isMain || inset != null) {
            // anything going wrong while two cameras are open -> one camera from now on
            fallBackToSingle()
            return
        }
        if (mainRetries < 2) {
            mainRetries++
            handler.postDelayed({ openAll() }, 1500)
        } else {
            onMessage("Camera error $error - close other camera apps and try again")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: Cam, gen: Int) {
        val dev = cam.device ?: return
        try {
            dev.createCaptureSession(listOf(cam.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (gen != generation) { session.close(); return }
                    cam.session = session
                    apply(cam)
                    if (cam.isMain) {
                        mainRetries = 0
                        handler.postDelayed({ if (gen == generation) openInset(gen) }, 700)
                    } else insetRunning = true
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (gen == generation) onCameraFailed(cam, -3)
                }
            }, handler)
        } catch (e: Exception) {
            if (gen == generation) onCameraFailed(cam, -4)
        }
    }

    private fun apply(cam: Cam) {
        val dev = cam.device ?: return
        val session = cam.session ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(cam.surface)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            val fps = if (lowPower) Range(10, 15) else cam.fps
            fps?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (cam.stabilization && cam.isMain) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            if (cam.hasFlash) {
                b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            session.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            // some phones reject a 10-15 fps range: fall back to the default range
            if (lowPower) {
                try {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    b.addTarget(cam.surface)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    session.setRepeatingRequest(b.build(), null, handler)
                } catch (_: Exception) {}
            }
            Log.w(TAG, "request failed: ${e.message}")
        }
    }

    private fun stopInternal() {
        generation++
        for (c in listOfNotNull(inset, main)) {
            try { c.session?.close() } catch (_: Exception) {}
            try { c.device?.close() } catch (_: Exception) {}
        }
        main = null
        inset = null
        insetRunning = false
    }

    companion object {
        private const val TAG = "OvraCamRig"
        /** Set once both cameras together have failed on this phone (until the app restarts). */
        @Volatile var dualFailed = false
    }
}
