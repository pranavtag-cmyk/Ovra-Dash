package com.ovra.dash

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.security.MessageDigest
import java.security.SecureRandom

/** All user settings in one place (backed by the Settings screen). */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun int(key: String, def: Int) = str(key, def.toString()).toIntOrNull() ?: def

    // recording
    val quality get() = str("quality", "1080p")
    val clipMinutes get() = int("clip_minutes", 3)
    val storageGb get() = int("storage_gb", 30)
    val recordSound get() = sp.getBoolean("record_sound", true)
    /** road = back camera only, cabin = front camera only, both = road + cabin inset */
    val cameraMode get() = str("camera_mode", "road")
    val useMph get() = str("speed_units", "mph") == "mph"
    val autoStart get() = sp.getBoolean("auto_start", true)
    val stopDelaySec get() = int("stop_delay", 60)
    val orientation get() = str("orientation", "auto")   // auto, landscape, landscape_flipped

    // crash
    val crashSensitivity get() = str("crash_sensitivity", "medium")
    val crashThresholdG get() = when (crashSensitivity) { "low" -> 3.0f; "high" -> 1.6f; else -> 2.2f }
    val sosEnabled get() = sp.getBoolean("sos_enabled", false)
    val contactNumber get() = str("contact_number", "").trim()

    // parking guard
    val guardAuto get() = sp.getBoolean("guard_auto", true)
    val guardArmDelaySec get() = int("guard_arm_delay", 120)
    val guardSiren get() = sp.getBoolean("guard_siren", true)
    val guardSirenOnBump get() = sp.getBoolean("guard_siren_bump", true)
    val guardRecordRoad get() = sp.getBoolean("guard_record_road", true)
    val guardSensitivity get() = str("guard_sensitivity", "medium")
    val guardMinBattery get() = int("guard_min_battery", 20)
    val guardTexts get() = sp.getBoolean("guard_texts", false)

    // security
    val pinSet get() = sp.getString("pin_hash", null) != null
    val useFingerprint get() = sp.getBoolean("use_fingerprint", true)
    val lockClips get() = sp.getBoolean("lock_clips", true)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sp.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", null) ?: return false
        return hash(salt, pin) == sp.getString("pin_hash", null)
    }

    fun clearPin() = sp.edit().remove("pin_hash").remove("pin_salt").apply()

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
}
