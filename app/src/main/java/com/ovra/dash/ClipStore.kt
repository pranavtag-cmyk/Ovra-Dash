package com.ovra.dash

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clips live in the app's private folder (not visible in the gallery or to other apps).
 * Names: DASH_yyyyMMdd_HHmmss.mp4 (driving), PARK_... (parking guard). "_LOCK" = protected from loop deletion.
 */
class ClipStore(context: Context) {
    val dir: File = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "clips").apply { mkdirs() }
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK)

    fun newClip(prefix: String = "DASH", locked: Boolean = false): File {
        var base = "${prefix}_${fmt.format(Date())}"
        var f = File(dir, base + (if (locked) "_LOCK" else "") + ".mp4")
        var n = 1
        while (f.exists()) { f = File(dir, "${base}_$n" + (if (locked) "_LOCK" else "") + ".mp4"); n++ }
        return f
    }

    fun isLocked(f: File) = f.name.contains("_LOCK")

    fun sidecar(f: File) = File(f.parentFile, f.nameWithoutExtension + ".csv")

    /** Renames to a locked name. Safe while the file is still being written. Returns the new file. */
    fun lock(f: File): File {
        if (isLocked(f) || !f.exists()) return f
        val nf = File(f.parentFile, f.nameWithoutExtension + "_LOCK.mp4")
        val sc = sidecar(f)
        if (f.renameTo(nf)) {
            if (sc.exists()) sc.renameTo(sidecar(nf))
            return nf
        }
        return f
    }

    fun unlock(f: File): File {
        if (!isLocked(f)) return f
        val nf = File(f.parentFile, f.name.replace("_LOCK", ""))
        val sc = sidecar(f)
        if (f.renameTo(nf)) {
            if (sc.exists()) sc.renameTo(sidecar(nf))
            return nf
        }
        return f
    }

    fun delete(f: File) {
        sidecar(f).delete()
        f.delete()
    }

    fun clips(): List<File> = (dir.listFiles { x -> x.extension == "mp4" } ?: emptyArray()).sortedByDescending { it.name.substringAfter('_') }

    fun usedBytes(): Long = (dir.listFiles() ?: emptyArray()).sumOf { it.length() }

    fun freeBytes(): Long = try { StatFs(dir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }

    /** Loop recording: delete the oldest unlocked clips until under the limit and enough space is free. */
    fun enforce(limitBytes: Long, keep: Set<String>) {
        val minFree = 1_500_000_000L
        val oldestFirst = clips().reversed().filter { !isLocked(it) && it.absolutePath !in keep }.toMutableList()
        var used = usedBytes()
        var free = freeBytes()
        while ((used > limitBytes || free < minFree) && oldestFirst.isNotEmpty()) {
            val f = oldestFirst.removeAt(0)
            val size = f.length() + sidecar(f).length()
            delete(f)
            used -= size
            free += size
        }
    }
}
