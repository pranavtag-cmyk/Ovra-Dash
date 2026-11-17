package com.ovra.dash

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Turns GPS positions into road and place names ("A38 Devon Expressway · Plymouth") using the
 * phone's built-in geocoder. Needs mobile data; when offline the last known name is kept.
 * Lookups are throttled: at most every 15 s, and only after moving 120 m.
 */
class Places(context: Context, private val onName: (road: String?, area: String?) -> Unit) {
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.UK) else null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private var lastLookup: Location? = null
    private var lastLookupAt = 0L

    fun update(loc: Location) {
        val g = geocoder ?: return
        val now = System.currentTimeMillis()
        val prev = lastLookup
        if (busy) return
        if (prev != null && (now - lastLookupAt < 15_000 || loc.distanceTo(prev) < 120f)) return
        busy = true
        lastLookup = loc
        lastLookupAt = now
        exec.execute {
            try {
                @Suppress("DEPRECATION")
                val a = g.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                if (a != null) onName(road(a), area(a))
            } catch (_: Exception) {
                // offline or service unavailable - keep the previous name
            } finally {
                busy = false
            }
        }
    }

    private fun road(a: Address): String? {
        val t = a.thoroughfare?.trim()
        if (!t.isNullOrEmpty()) return t
        // feature name is sometimes the road number or a house number - only use it if it isn't just digits
        val f = a.featureName?.trim()
        return if (!f.isNullOrEmpty() && !f.all { it.isDigit() || it == '-' }) f else null
    }

    private fun area(a: Address): String? {
        val parts = listOfNotNull(
            a.subLocality?.takeIf { it.isNotBlank() },
            a.locality?.takeIf { it.isNotBlank() } ?: a.subAdminArea?.takeIf { it.isNotBlank() },
        ).distinct()
        return parts.joinToString(", ").ifEmpty { a.adminArea }
    }

    fun shutdown() = exec.shutdownNow()
}
