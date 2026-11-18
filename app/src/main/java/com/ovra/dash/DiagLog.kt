package com.ovra.dash

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small rolling log of what the dashcam did (recording, camera, errors) - shareable from Settings. */
object DiagLog {
    @Volatile private var file: File? = null
    private val fmt = SimpleDateFormat("dd MMM HH:mm:ss.SSS", Locale.UK)

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "ovra-dash-log.txt")
    }

    fun file(context: Context): File { init(context); return file!! }

    @Synchronized
    fun log(msg: String) {
        val f = file ?: return
        try {
            if (f.length() > 400_000) {
                // keep the newest half
                val keep = f.readText().takeLast(200_000)
                f.writeText(keep)
            }
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) {}
    }
}
