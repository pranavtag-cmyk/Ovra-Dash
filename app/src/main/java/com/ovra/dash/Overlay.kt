package com.ovra.dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Draws the two-line info bar stamped across the bottom of the video. */
object Overlay {
    private val dateFmt = SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.UK)

    fun draw(width: Int, height: Int, speedMps: Float?, mph: Boolean, lat: Double?, lng: Double?,
             road: String?, area: String?, tag: String?): Bitmap {
        val lineH = (height * 0.045f).coerceAtLeast(20f)
        val barH = (lineH * 2.2f).toInt()
        val bmp = Bitmap.createBitmap(width, barH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.argb(140, 0, 0, 0))
        val pad = lineH * 0.6f

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = lineH * 0.78f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val small = Paint(text).apply { textSize = lineH * 0.66f }
        val line1 = lineH * 0.95f
        val line2 = lineH * 1.95f

        // line 1: date/time · speed · (tag) brand
        c.drawText(dateFmt.format(Date()), pad, line1, text)
        val speed = if (speedMps == null) "--" else (if (mph) speedMps * 2.23694f else speedMps * 3.6f).toInt().toString()
        val speedText = "$speed ${if (mph) "MPH" else "KM/H"}"
        val speedPaint = Paint(text).apply { color = Color.rgb(255, 214, 102) }
        c.drawText(speedText, (width - speedPaint.measureText(speedText)) / 2f, line1, speedPaint)
        val brand = (if (!tag.isNullOrEmpty()) "$tag  " else "") + "OVRA DASH"
        c.drawText(brand, width - pad - small.measureText(brand), line1, small)

        // line 2: road + area · coordinates
        val place = listOfNotNull(road?.takeIf { it.isNotBlank() }, area?.takeIf { it.isNotBlank() }).joinToString(" · ")
        val pos = if (lat != null && lng != null) "%.5f, %.5f".format(Locale.UK, lat, lng) else "NO GPS"
        val placePaint = Paint(text).apply { textSize = lineH * 0.72f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val maxPlace = width - pad * 3 - small.measureText(pos)
        var shown = place
        while (shown.isNotEmpty() && placePaint.measureText(shown) > maxPlace) shown = shown.dropLast(2) + "…"
        if (shown.isNotEmpty()) c.drawText(shown, pad, line2, placePaint)
        c.drawText(pos, width - pad - small.measureText(pos), line2, small)
        return bmp
    }
}
