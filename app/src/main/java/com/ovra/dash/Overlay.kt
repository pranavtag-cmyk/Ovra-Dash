package com.ovra.dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Draws the info bar stamped across the bottom of the video. */
object Overlay {
    private val dateFmt = SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.UK)

    fun draw(width: Int, height: Int, speedMps: Float?, mph: Boolean, lat: Double?, lng: Double?, tag: String?): Bitmap {
        val barH = (height * 0.065f).toInt().coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(width, barH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.argb(140, 0, 0, 0))

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = barH * 0.52f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val baseline = barH * 0.68f
        val pad = barH * 0.4f

        // left: date & time
        c.drawText(dateFmt.format(Date()), pad, baseline, text)

        // centre: speed
        val speed = if (speedMps == null) "--" else {
            val v = if (mph) speedMps * 2.23694f else speedMps * 3.6f
            v.toInt().toString()
        }
        val speedText = "$speed ${if (mph) "MPH" else "KM/H"}"
        val speedPaint = Paint(text).apply { color = Color.rgb(255, 214, 102) }
        c.drawText(speedText, (width - speedPaint.measureText(speedText)) / 2f, baseline, speedPaint)

        // right: position + brand (+ tag such as LOCKED / PARKING)
        val pos = if (lat != null && lng != null) "%.5f, %.5f".format(Locale.UK, lat, lng) else "NO GPS"
        val right = buildString {
            if (!tag.isNullOrEmpty()) append(tag).append("  ")
            append(pos).append("  OVRA DASH")
        }
        val rightPaint = Paint(text).apply { textSize = barH * 0.44f }
        c.drawText(right, width - pad - rightPaint.measureText(right), baseline, rightPaint)
        return bmp
    }
}
