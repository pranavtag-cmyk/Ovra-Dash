package com.ovra.dash

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class ClipsActivity : AppCompatActivity() {

    private lateinit var store: ClipStore
    private lateinit var list: RecyclerView
    private val thumbs = Executors.newFixedThreadPool(2)
    private var filter = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ClipStore(this)
        title = "Clips"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0E0F13.toInt()) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 16, 24, 8) }
        fun tab(label: String, key: String) = TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(28, 14, 28, 14)
            setBackgroundResource(R.drawable.chip_bg)
            setOnClickListener { filter = key; reload() }
        }.also { tabs.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 16 }) }
        tab("All", "all"); tab("Saved", "saved"); tab("Parking", "parking")
        root.addView(tabs)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@ClipsActivity) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() { super.onDestroy(); thumbs.shutdownNow() }

    private fun reload() {
        val current = DashService.instance?.currentClip?.absolutePath
        val clips = store.clips().filter {
            when (filter) {
                "saved" -> store.isLocked(it)
                "parking" -> it.name.startsWith("PARK")
                else -> true
            }
        }
        list.adapter = Adapter(clips, current)
        if (clips.isEmpty()) Toast.makeText(this, "No clips yet", Toast.LENGTH_SHORT).show()
    }

    private fun label(f: File): String {
        val stamp = Regex("(\\d{8}_\\d{6})").find(f.name)?.value
        return try {
            val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).parse(stamp!!)
            SimpleDateFormat("EEE d MMM  HH:mm:ss", Locale.UK).format(d!!)
        } catch (_: Exception) { f.nameWithoutExtension }
    }

    private inner class Adapter(val items: List<File>, val recording: String?) : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: LinearLayout, val thumb: ImageView, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 14, 24, 14)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val thumb = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1D2029.toInt()) }
            row.addView(thumb, LinearLayout.LayoutParams(256, 144))
            val texts = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 0, 0, 0) }
            val title = TextView(parent.context).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 16f }
            val sub = TextView(parent.context).apply { setTextColor(0xFF9EA3B2.toInt()); textSize = 13f }
            texts.addView(title); texts.addView(sub)
            row.addView(texts)
            return VH(row, thumb, title, sub)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = items[pos]
            val live = f.absolutePath == recording
            h.title.text = label(f)
            val kind = if (f.name.startsWith("PARK")) "Parking event" else "Driving"
            val tags = listOfNotNull(kind, if (store.isLocked(f)) "Saved" else null, if (live) "Recording now" else null,
                String.format(Locale.UK, "%.0f MB", f.length() / 1e6))
            h.sub.text = tags.joinToString("  ·  ")
            h.sub.tag = f.absolutePath
            thumbs.execute {
                val place = placeOf(f)
                if (place != null) runOnUiThread {
                    if (h.sub.tag == f.absolutePath) h.sub.text = place + "\n" + tags.joinToString("  ·  ")
                }
            }
            h.title.setTextColor(if (store.isLocked(f)) 0xFFFFD666.toInt() else 0xFFFFFFFF.toInt())
            h.thumb.setImageBitmap(null)
            h.thumb.tag = f.absolutePath
            if (!live) thumbs.execute {
                val bmp: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(f, Size(256, 144), null)
                    else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Images.Thumbnails.MINI_KIND)
                } catch (_: Exception) { null }
                runOnUiThread { if (h.thumb.tag == f.absolutePath) h.thumb.setImageBitmap(bmp) }
            }
            h.itemView.setOnClickListener {
                if (live) Toast.makeText(this@ClipsActivity, "This clip is still recording", Toast.LENGTH_SHORT).show()
                else startActivity(Intent(this@ClipsActivity, PlayerActivity::class.java).putExtra("path", f.absolutePath))
            }
            h.itemView.setOnLongClickListener { if (!live) actions(f); true }
        }
    }

    /** First road/place name recorded in the clip's GPS log. */
    private fun placeOf(f: File): String? = try {
        store.sidecar(f).takeIf { it.exists() }?.bufferedReader()?.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val q = line.indexOf('"')
                if (q < 0) null else line.substring(q + 1).trimEnd('"').takeIf { it.isNotBlank() }
            }.firstOrNull()
        }
    } catch (_: Exception) { null }

    private fun actions(f: File) {
        val locked = store.isLocked(f)
        val options = arrayOf("Play", if (locked) "Unsave (allow loop delete)" else "Save (protect)", "Export to gallery", "Share", "Delete")
        AlertDialog.Builder(this).setTitle(label(f)).setItems(options) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, PlayerActivity::class.java).putExtra("path", f.absolutePath))
                1 -> if (locked) Security.require(this, "Unsave clip") { store.unlock(f); reload() } else { store.lock(f); reload() }
                2 -> exportToGallery(f)
                3 -> share(f)
                4 -> Security.require(this, "Delete clip") {
                    AlertDialog.Builder(this).setMessage("Delete this clip?")
                        .setPositiveButton("Delete") { _, _ -> store.delete(f); reload() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }.show()
    }

    private fun share(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share clip"))
    }

    private fun exportToGallery(f: File) {
        Toast.makeText(this, "Exporting...", Toast.LENGTH_SHORT).show()
        thumbs.execute {
            val ok = try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name.replace("_LOCK", ""))
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Ovra Dash")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
                true
            } catch (_: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Saved to Movies/Ovra Dash" else "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }
}
