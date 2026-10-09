package org.projectbarry.pbosinstaller.release

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.delay
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads an image's parts with Android's DownloadManager (keeps going if
 * the app is closed, resumes after Wi-Fi drops) into the app's own folder on
 * internal storage, then checks every part against the signed SHA256SUMS.
 */
class ImageDownloader(private val context: Context) {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    val dir: File = File(context.getExternalFilesDir(null), "images").apply { mkdirs() }

    data class Progress(val done: Long, val total: Long, val part: Int, val parts: Int)

    class DownloadError(message: String) : Exception(message)

    fun freeBytes(): Long = StatFs(dir.path).availableBytes

    /**
     * A part counts as here only once DownloadManager finished it: it creates
     * the file at full size up front, so the size alone proves nothing.
     */
    private fun isDone(part: Asset): Boolean =
        prefs.getLong("done:${part.name}", -1L) == part.size && File(dir, part.name).length() == part.size

    /** Bytes still to download (finished parts are skipped). */
    fun bytesNeeded(image: ImageRelease): Long = image.parts.filterNot(::isDone).sumOf { it.size }

    /** Fetches SHA256SUMS + its signature and returns the checked hash list. */
    fun fetchVerifiedSums(image: ImageRelease): Map<String, String> {
        val sums = URL(image.sums.url).readBytes()
        val sig = URL(image.sumsSig.url).readText()
        if (!SshSig.verify(sums, sig)) {
            throw DownloadError("The release's checksum list is not signed by the pb-os key. Not downloading.")
        }
        return Releases.parseSums(String(sums))
    }

    /** Downloads every missing part, reporting progress; returns the part files. */
    suspend fun download(image: ImageRelease, onProgress: (Progress) -> Unit): List<File> {
        // Old images from earlier releases only waste space.
        val wanted = image.parts.map { it.name }.toSet()
        dir.listFiles()?.filter { it.name !in wanted }?.forEach { old ->
            prefs.edit().remove("done:${old.name}").apply()
            old.delete()
        }

        val total = image.downloadSize
        var finished = 0L
        image.parts.forEachIndexed { i, part ->
            val file = File(dir, part.name)
            if (!isDone(part)) {
                downloadOne(part, file) { soFar -> onProgress(Progress(finished + soFar, total, i + 1, image.parts.size)) }
            }
            finished += part.size
            onProgress(Progress(finished, total, i + 1, image.parts.size))
        }
        return image.parts.map { File(dir, it.name) }
    }

    private suspend fun downloadOne(part: Asset, file: File, onBytes: (Long) -> Unit) {
        var id = prefs.getLong(part.name, -1L)
        if (id == -1L || statusOf(id) == null) {
            prefs.edit().remove("done:${part.name}").apply()
            file.delete()
            val req = DownloadManager.Request(Uri.parse(part.url))
                .setTitle(part.name)
                .setDescription("PB-OS image")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file))
            id = dm.enqueue(req)
            prefs.edit().putLong(part.name, id).apply()
        }
        while (true) {
            val (status, soFar, reason) = statusOf(id) ?: throw DownloadError("Download of ${part.name} was cancelled.")
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(part.name).apply()
                    if (file.length() != part.size) {
                        file.delete()
                        throw DownloadError("${part.name} came down at the wrong size. Try again.")
                    }
                    prefs.edit().putLong("done:${part.name}", part.size).apply()
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(part.name).apply()
                    dm.remove(id)
                    throw DownloadError("Download of ${part.name} failed (reason $reason). Try again.")
                }
                else -> onBytes(soFar)
            }
            delay(500)
        }
    }

    private fun statusOf(id: Long): Triple<Int, Long, Int>? =
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }

    /** Hashes every part; deletes and names the first one that doesn't match. */
    fun verify(files: List<File>, sums: Map<String, String>, onProgress: (Long, Long) -> Unit) {
        val total = files.sumOf { it.length() }
        var done = 0L
        val buf = ByteArray(1 shl 20)
        for (file in files) {
            val expected = sums[file.name] ?: throw DownloadError("${file.name} is not in the release's checksum list.")
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                prefs.edit().remove("done:${file.name}").apply()
                file.delete()
                throw DownloadError("${file.name} is damaged (checksum mismatch) and was deleted. Try again.")
            }
        }
    }
}
