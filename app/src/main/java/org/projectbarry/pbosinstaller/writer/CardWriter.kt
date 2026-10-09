package org.projectbarry.pbosinstaller.writer

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/**
 * Prepares a card write and follows it. The write itself runs as root (see
 * [WriteScript]): this class writes the job script into the app's own folder,
 * puts the one-line launcher in Downloads for "Run Script as Root", and reads
 * the status file the script keeps up to date.
 */
class CardWriter(private val context: Context) {
    data class Status(val job: String, val state: String, val done: Long, val total: Long, val message: String)

    private val prefs = context.getSharedPreferences("write", Context.MODE_PRIVATE)
    private val dir = File(context.getExternalFilesDir(null), "write")
    private val statusFile = File(dir, "status")
    private val bodyFile = File(dir, "pbos-write.sh")

    /** The job the app last prepared, if any, and its release tag. */
    val currentJob: String? get() = prefs.getString("job", null)
    val currentTag: String? get() = prefs.getString("tag", null)

    /** 7-Zip, shipped as a native library so Android installs it as a runnable file. */
    private val sevenZip: File get() = File(context.applicationInfo.nativeLibraryDir, "lib7zzs.so")

    /**
     * Writes the job script and the launcher; returns the launcher's file name in
     * Downloads (Android may number it if an old copy is there).
     */
    fun prepare(tag: String, parts: List<Pair<File, String>>, imageSha256: String, cardBytes: Long?): String {
        dir.mkdirs()
        statusFile.delete()
        val id = UUID.randomUUID().toString()
        val job = WriteScript.Job(
            id = id,
            sevenZip = sevenZip.absolutePath,
            parts = parts.map { (file, sha) -> rootPath(file) to sha },
            imageSha256 = imageSha256,
            cardBytes = cardBytes,
            statusFile = rootPath(statusFile),
        )
        bodyFile.writeText(WriteScript.body(job))
        val name = saveLauncher(WriteScript.launcher(rootPath(bodyFile)))
        prefs.edit().putString("job", id).putString("tag", tag).apply()
        return name
    }

    /** The script's latest status for the current job, or null before it has started. */
    fun status(): Status? {
        val job = currentJob ?: return null
        val values = runCatching { statusFile.readLines() }.getOrNull()
            ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.toMap() ?: return null
        if (values["job"] != job) return null
        return Status(
            job,
            values["state"].orEmpty(),
            values["done"]?.toLongOrNull() ?: 0,
            values["total"]?.toLongOrNull() ?: 0,
            values["message"].orEmpty(),
        )
    }

    /** Forget the job (after it finished, or to start a new one). */
    fun clear() {
        prefs.edit().remove("job").remove("tag").apply()
        statusFile.delete()
    }

    /** Puts the launcher in Downloads, replacing copies the app saved before. */
    private fun saveLauncher(text: String): String {
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Only the app's own earlier copies can be deleted; others are left alone.
        runCatching {
            resolver.delete(downloads, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$LAUNCHER_STEM%.sh"))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$LAUNCHER_STEM.sh")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-sh")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(downloads, values) ?: error("Couldn't save the script to Downloads.")
        resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "$LAUNCHER_STEM.sh"
    }

    companion object {
        const val LAUNCHER_STEM = "pbos-write-card"

        /** Shared storage as root's shell sees it ("/sdcard/…"). */
        fun rootPath(file: File): String = file.absolutePath.replace(Regex("^/storage/emulated/0(?=/)"), "/sdcard")
    }
}
