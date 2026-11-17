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

/** Opens the road (back) camera and, when the phone allows it, the cabin (front) camera at the same time. */
class CameraRig(context: Context, private val onError: (String) -> Unit) {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("ovra-cam").apply { start() }
    private val handler = Handler(thread.looper)

    private class Cam(val id: String) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var surface: Surface? = null
        var fps: Range<Int>? = null
        var stabilization = false
        var hasFlash = false
    }

    private var rear: Cam? = null
    private var front: Cam? = null
    private var generation = 0
    @Volatile private var torch = false
    @Volatile private var lowPower = false

    @Volatile var frontRunning = false; private set
    @Volatile var rearSensorOrientation = 90; private set
    @Volatile var frontSensorOrientation = 270; private set

    val rearId: String? get() = idFor(CameraCharacteristics.LENS_FACING_BACK)
    val frontId: String? get() = idFor(CameraCharacteristics.LENS_FACING_FRONT)

    private fun idFor(facing: Int): String? = try {
        manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
    } catch (_: Exception) { null }

    /** True when Android reports that this phone can stream the back and front cameras together. */
    fun supportsDual(): Boolean {
        val r = rearId ?: return false
        val f = frontId ?: return false
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            manager.concurrentCameraIds.any { set -> set.contains(r) && set.contains(f) }
        } catch (_: Exception) { false }
    }

    /** lowPower = 15 fps (parking guard). */
    fun start(rearSurface: Surface, frontSurface: Surface?, lowPower: Boolean) {
        handler.post {
            stopInternal()
            this.lowPower = lowPower
            val gen = ++generation
            val r = rearId
            if (r == null) { onError("No back camera found"); return@post }
            rear = Cam(r).also { open(it, rearSurface, gen) }
            rearSensorOrientation = sensorOrientation(r, 90)
            val f = frontId
            if (frontSurface != null && f != null) {
                frontSensorOrientation = sensorOrientation(f, 270)
                front = Cam(f).also { open(it, frontSurface, gen) }
            }
        }
    }

    fun stop() = handler.post { stopInternal() }

    fun release() = handler.post {
        stopInternal()
        thread.quitSafely()
    }

    fun setLowPower(on: Boolean) = handler.post {
        lowPower = on
        rear?.let { apply(it) }
        front?.let { apply(it) }
    }

    fun setTorch(on: Boolean) = handler.post {
        torch = on
        rear?.let { apply(it) }
    }

    private fun sensorOrientation(id: String, def: Int) = try {
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: def
    } catch (_: Exception) { def }

    @SuppressLint("MissingPermission")
    private fun open(cam: Cam, surface: Surface, gen: Int) {
        try {
            val ch = manager.getCameraCharacteristics(cam.id)
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            cam.fps = ranges.filter { it.upper == 30 }.maxByOrNull { it.lower } ?: ranges.maxByOrNull { it.upper }
            val modes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
            cam.stabilization = modes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            cam.hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            cam.surface = surface

            manager.openCamera(cam.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (gen != generation) { device.close(); return }
                    cam.device = device
                    createSession(cam, gen)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cam.device = null
                    if (cam === front) frontRunning = false
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cam.device = null
                    if (cam === front) {
                        frontRunning = false
                        front = null
                        Log.w(TAG, "front camera error $error - recording road only")
                        onError("This phone won't run both cameras together - recording the road camera only")
                    } else {
                        onError("Camera error $error")
                    }
                }
            }, handler)
        } catch (e: Exception) {
            if (cam === front) {
                front = null
                onError("Cabin camera unavailable - recording the road camera only")
            } else onError(e.message ?: "Camera failed")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: Cam, gen: Int) {
        val dev = cam.device ?: return
        val s = cam.surface ?: return
        try {
            dev.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (gen != generation) { session.close(); return }
                    cam.session = session
                    if (cam === front) frontRunning = true
                    apply(cam)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cam === front) {
                        try { dev.close() } catch (_: Exception) {}
                        front = null
                        onError("This phone won't run both cameras together - recording the road camera only")
                    } else onError("Camera setup failed")
                }
            }, handler)
        } catch (e: Exception) {
            onError(e.message ?: "Camera session failed")
        }
    }

    private fun apply(cam: Cam) {
        val dev = cam.device ?: return
        val session = cam.session ?: return
        val s = cam.surface ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(s)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            val fps = if (lowPower) Range(10, 15) else cam.fps
            fps?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (cam.stabilization && cam === rear) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
            if (cam === rear && cam.hasFlash) {
                b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            session.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            // some phones reject a 10-15 fps range: fall back to the default range
            if (lowPower) {
                try {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    b.addTarget(s)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    session.setRepeatingRequest(b.build(), null, handler)
                } catch (_: Exception) {}
            }
            Log.w(TAG, "request failed: ${e.message}")
        }
    }

    private fun stopInternal() {
        generation++
        for (c in listOfNotNull(rear, front)) {
            try { c.session?.close() } catch (_: Exception) {}
            try { c.device?.close() } catch (_: Exception) {}
        }
        rear = null
        front = null
        frontRunning = false
    }

    companion object { private const val TAG = "OvraCamRig" }
}
