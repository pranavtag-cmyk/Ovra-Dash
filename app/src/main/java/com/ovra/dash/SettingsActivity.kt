package com.ovra.dash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ovra Dash settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, Fragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class Fragment : PreferenceFragmentCompat() {
        override fun onResume() {
            super.onResume()
            findPreference<Preference>("welcome_overlay")?.summary =
                if (android.provider.Settings.canDrawOverlays(requireContext())) "Allowed - the video can appear even when another app is open"
                else "Not allowed yet - tap to allow. Without it, the video only appears when the screen is off or Ovra Dash is open"
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)
            val prefs = Prefs(requireContext())

            fun needSms(enabled: Boolean) {
                if (enabled && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.SEND_SMS), 2)
                }
            }
            findPreference<SwitchPreferenceCompat>("sos_enabled")?.setOnPreferenceChangeListener { _, v -> needSms(v as Boolean); true }
            findPreference<SwitchPreferenceCompat>("guard_texts")?.setOnPreferenceChangeListener { _, v -> needSms(v as Boolean); true }

            val pinPref = findPreference<Preference>("pin")!!
            fun updatePin() { pinPref.summary = if (prefs.pinSet) "PIN is set - tap to change or remove" else "Not set - anyone can open clips and turn off the guard" }
            updatePin()
            pinPref.setOnPreferenceClickListener {
                val act = requireActivity() as AppCompatActivity
                if (prefs.pinSet) {
                    androidx.appcompat.app.AlertDialog.Builder(act).setTitle("PIN")
                        .setItems(arrayOf("Change PIN", "Remove PIN")) { _, w ->
                            if (w == 0) Security.setNewPin(act) { updatePin() }
                            else Security.require(act, "Remove PIN") { prefs.clearPin(); updatePin(); Toast.makeText(act, "PIN removed", Toast.LENGTH_SHORT).show() }
                        }.show()
                } else Security.setNewPin(act) { updatePin() }
                true
            }

            findPreference<SwitchPreferenceCompat>("security_enabled")?.setOnPreferenceChangeListener { pref, v ->
                val on = v as Boolean
                val act = requireActivity() as AppCompatActivity
                fun apply() {
                    val svc = DashService.instance
                    if (svc != null) svc.setSecurity(on) else prefs.securityEnabled = on
                    (pref as SwitchPreferenceCompat).isChecked = on
                }
                if (!on) Security.require(act, "Turn off security") { apply() } else apply()
                false   // we set it ourselves once the PIN check passes
            }
            findPreference<Preference>("test_alarm")?.setOnPreferenceClickListener {
                val svc = DashService.instance
                if (svc != null) svc.testAlarm()
                else Toast.makeText(requireContext(), "Open the main screen once first", Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("share_log")?.setOnPreferenceClickListener {
                val f = DiagLog.file(requireContext())
                if (!f.exists()) f.writeText("(empty)\n")
                val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), requireContext().packageName + ".files", f)
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share diagnostics log"))
                true
            }

            findPreference<Preference>("welcome_preview")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), WelcomeActivity::class.java)); true
            }
            findPreference<Preference>("welcome_overlay")?.setOnPreferenceClickListener {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + requireContext().packageName)))
                } catch (_: Exception) {}
                true
            }

            findPreference<Preference>("about")?.summary = "Version ${requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName} · Coded by Pranav Tagadpallewar"
            findPreference<Preference>("dual_info")?.summary =
                if (CameraRig(requireContext()) {}.let { val d = it.supportsDual(); it.release(); d }) "This phone reports it can run both cameras together"
                else "This phone doesn't officially report dual-camera support - Ovra Dash will still try, and fall back to the road camera"
        }
    }
}
