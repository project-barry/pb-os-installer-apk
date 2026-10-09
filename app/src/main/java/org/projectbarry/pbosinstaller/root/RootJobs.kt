package org.projectbarry.pbosinstaller.root

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * Runs the app's root scripts (writing the card, the boot loader steps) and
 * follows them through a status file.
 *
 * How a script gets root depends on the handheld:
 * - [Route.Xsu]: AYANEO/KONKR firmware has /product/bin/xsu, which any app may use;
 *   the app starts the script itself.
 * - [Route.RunScriptAsRoot]: Retroid's Handheld Settings → Advanced → Run Script as
 *   Root runs a file line by line, so the app saves a one-line launcher in Downloads
 *   and the user runs it there.
 * - [Route.Manual]: anything else; same launcher, run as root however the handheld allows.
 *
 * Every job script starts with [ScriptKit.prelude], which moves it off shared
 * storage and into the background, so both routes return at once.
 */
class RootJobs(private val context: Context) {
    enum class Route { Xsu, RunScriptAsRoot, Manual }

    /** A job's latest status: the script's state plus any extra key=value lines it reported. */
    data class Status(
        val job: String,
        val state: String,
        val done: Long,
        val total: Long,
        val message: String,
        val values: Map<String, String>,
        /** How long ago the script last updated the status (it does at least every 2 s while busy). */
        val ageMs: Long,
    )

    val route: Route = when {
        File(XSU).exists() -> Route.Xsu
        android.os.Build.MANUFACTURER.equals("Moorechip", ignoreCase = true) -> Route.RunScriptAsRoot
        else -> Route.Manual
    }

    private val prefs = context.getSharedPreferences("root-jobs", Context.MODE_PRIVATE)
    private fun dir(name: String) = File(context.getExternalFilesDir(null), name).apply { mkdirs() }
    fun statusFile(name: String) = File(dir(name), "status")
    fun scriptFile(name: String) = File(dir(name), "pbos-$name.sh")
    /** The folder a job works in, as root sees it. */
    fun rootDir(name: String) = rootPath(dir(name))

    /** The job id last prepared under [name]. */
    fun currentJob(name: String): String? = prefs.getString("job.$name", null)
    fun extra(name: String, key: String): String? = prefs.getString("$name.$key", null)

    /**
     * Saves the job script; for the launcher routes, also the launcher in Downloads.
     * Returns the launcher's name in Downloads, or null when the app starts it itself.
     */
    fun prepare(name: String, id: String, body: String, extras: Map<String, String> = emptyMap()): String? {
        statusFile(name).delete()
        scriptFile(name).writeText(body)
        prefs.edit().apply {
            putString("job.$name", id)
            extras.forEach { (k, v) -> putString("$name.$k", v) }
        }.apply()
        return if (route == Route.Xsu) null else saveLauncher(name, "sh ${rootPath(scriptFile(name))}\n")
    }

    /** Starts the job through xsu (the script detaches itself, so this returns at once). */
    fun startWithXsu(name: String): Boolean = runCatching {
        val p = ProcessBuilder(XSU, "sh", rootPath(scriptFile(name))).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    }.getOrDefault(false)

    fun status(name: String): Status? {
        val job = currentJob(name) ?: return null
        val file = statusFile(name)
        val values = runCatching { file.readLines() }.getOrNull()
            ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            ?.toMap() ?: return null
        if (values["job"] != job) return null
        return Status(
            job,
            values["state"].orEmpty(),
            values["done"]?.toLongOrNull() ?: 0,
            values["total"]?.toLongOrNull() ?: 0,
            values["message"].orEmpty(),
            values,
            System.currentTimeMillis() - file.lastModified(),
        )
    }

    fun clear(name: String) {
        prefs.edit().remove("job.$name").apply()
        statusFile(name).delete()
    }

    /** Puts a launcher in Downloads, replacing copies the app saved before. */
    private fun saveLauncher(name: String, text: String): String {
        val stem = "pbos-$name"
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Only the app's own earlier copies can be deleted; others are left alone.
        runCatching {
            resolver.delete(downloads, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$stem%.sh"))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.sh")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-sh")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(downloads, values) ?: error("Couldn't save the script to Downloads.")
        resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "$stem.sh"
    }

    companion object {
        const val XSU = "/product/bin/xsu"

        /**
         * Shared storage as root reaches it underneath Android's storage layer
         * (/data/media/0/…): processes with files open under /storage/emulated are
         * killed if the system server restarts.
         */
        fun rootPath(file: File): String = file.absolutePath.replace(Regex("^/storage/emulated/0(?=/)"), "/data/media/0")
    }
}
