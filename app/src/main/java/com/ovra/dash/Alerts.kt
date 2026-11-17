package com.ovra.dash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Text-message alerts to the contact set in Settings. */
object Alerts {
    fun canSend(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun send(context: Context, number: String, message: String): Boolean {
        if (number.isBlank() || !canSend(context)) return false
        return try {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            sms.sendMultipartTextMessage(number, null, parts, null, null)
            true
        } catch (e: Exception) {
            Log.w("OvraAlerts", "SMS failed: ${e.message}")
            false
        }
    }

    fun mapsLink(lat: Double?, lng: Double?) =
        if (lat != null && lng != null) "https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.UK, lat, lng) else "location unknown"
}
