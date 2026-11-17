package com.ovra.dash

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/** PIN / fingerprint check used before sensitive actions. */
object Security {
    @Volatile private var unlockedUntil = 0L

    /** Runs [onOk] after the user proves it's them. With no PIN set, runs straight away. */
    fun require(activity: AppCompatActivity, reason: String, rememberMinutes: Int = 0, onOk: () -> Unit) {
        val prefs = Prefs(activity)
        if (!prefs.pinSet || !prefs.securityEnabled) { onOk(); return }
        if (rememberMinutes > 0 && System.currentTimeMillis() < unlockedUntil) { onOk(); return }
        val done = {
            if (rememberMinutes > 0) unlockedUntil = System.currentTimeMillis() + rememberMinutes * 60_000L
            onOk()
        }
        val canBio = prefs.useFingerprint && BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        if (canBio) {
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { done() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // "Use PIN" button or cancelled -> fall back to the PIN
                    askPin(activity, reason, prefs, done)
                }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Ovra Dash")
                .setSubtitle(reason)
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build())
        } else askPin(activity, reason, prefs, done)
    }

    private fun askPin(activity: AppCompatActivity, reason: String, prefs: Prefs, onOk: () -> Unit) {
        if (activity.isFinishing) return
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(activity)
            .setTitle(reason)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (prefs.checkPin(input.text.toString())) onOk()
                else Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks for a new 4-8 digit PIN twice. */
    fun setNewPin(activity: AppCompatActivity, onDone: (Boolean) -> Unit) {
        val first = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "New PIN (4-8 digits)"
        }
        AlertDialog.Builder(activity).setTitle("Set a PIN").setView(first)
            .setPositiveButton("Next") { _, _ ->
                val pin = first.text.toString()
                if (pin.length !in 4..8) {
                    Toast.makeText(activity, "PIN must be 4-8 digits", Toast.LENGTH_SHORT).show(); onDone(false); return@setPositiveButton
                }
                val second = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    hint = "Repeat PIN"
                }
                AlertDialog.Builder(activity).setTitle("Repeat the PIN").setView(second)
                    .setPositiveButton("Save") { _, _ ->
                        if (second.text.toString() == pin) {
                            Prefs(activity).setPin(pin)
                            Toast.makeText(activity, "PIN saved", Toast.LENGTH_SHORT).show()
                            onDone(true)
                        } else {
                            Toast.makeText(activity, "PINs didn't match", Toast.LENGTH_SHORT).show(); onDone(false)
                        }
                    }
                    .setNegativeButton("Cancel") { _, _ -> onDone(false) }.show()
            }
            .setNegativeButton("Cancel") { _, _ -> onDone(false) }.show()
    }
}
