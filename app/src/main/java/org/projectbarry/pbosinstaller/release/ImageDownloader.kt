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
class ImageDownloader(private val context: Context, folder: String = "images") {
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    /** One folder per kind (images, updates): each deletes what it doesn't want any more. */
    val dir: File = File(context.getExternalFilesDir(null), folder).apply { mkdirs() }

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
    fun bytesNeeded(image: ImageRelease): Long = bytesNeeded(image.parts)
    fun bytesNeeded(parts: List<Asset>): Long = parts.filterNot(::isDone).sumOf { it.size }

    /** Fetches SHA256SUMS + its signature and returns the checked hash list. */
    fun fetchVerifiedSums(image: ImageRelease): Map<String, String> = fetchSigned(image.sums, image.sumsSig).hashes

    /** A release's SHA256SUMS exactly as published, with its signature, after checking it. */
    class SignedSums(val bytes: ByteArray, val signature: ByteArray) {
        val hashes: Map<String, String> = Releases.parseSums(String(bytes))
    }

    fun fetchSigned(sums: Asset, sig: Asset): SignedSums {
        val bytes = URL(sums.url).readBytes()
        val signature = URL(sig.url).readBytes()
        if (!SshSig.verify(bytes, String(signature))) {
            throw DownloadError("The release's checksum list is not signed by the pb-os key. Not downloading.")
        }
        return SignedSums(bytes, signature)
    }

    /** Downloads every missing part, reporting progress; returns the part files. */
    suspend fun download(image: ImageRelease, onProgress: (Progress) -> Unit): List<File> = download(image.parts, onProgress)

    suspend fun download(parts: List<Asset>, onProgress: (Progress) -> Unit): List<File> {
        // Old files from earlier releases only waste space.
        val wanted = parts.map { it.name }.toSet() + KEEP
        dir.listFiles()?.filter { it.name !in wanted }?.forEach { old ->
            prefs.edit().remove("done:${old.name}").apply()
            old.delete()
        }

        val total = parts.sumOf { it.size }
        var finished = 0L
        parts.forEachIndexed { i, part ->
            val file = File(dir, part.name)
            if (!isDone(part)) {
                downloadOne(part, file) { soFar -> onProgress(Progress(finished + soFar, total, i + 1, parts.size)) }
            }
            finished += part.size
            onProgress(Progress(finished, total, i + 1, parts.size))
        }
        return parts.map { File(dir, it.name) }
    }

    /**
     * Checks that [files], joined, are the package SHA256SUMS lists as [name]
     * (an update's parts are listed only as the joined package). Deletes them if not.
     */
    fun verifyJoined(files: List<File>, name: String, sums: Map<String, String>, onProgress: (Long, Long) -> Unit) {
        val expected = sums[name] ?: throw DownloadError("$name is not in the release's checksum list.")
        val total = files.sumOf { it.length() }
        var done = 0L
        val buf = ByteArray(1 shl 20)
        val md = MessageDigest.getInstance("SHA-256")
        for (file in files) file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done, total)
            }
        }
        if (md.digest().joinToString("") { "%02x".format(it) } != expected) {
            files.forEach { prefs.edit().remove("done:${it.name}").apply(); it.delete() }
            throw DownloadError("The update download is damaged (checksum mismatch) and was deleted. Try again.")
        }
    }

    /** Saves the signed checksum list next to the parts (PB-OS's updater checks it again). */
    fun saveSums(signed: SignedSums): List<File> = listOf(
        File(dir, "SHA256SUMS").apply { writeBytes(signed.bytes) },
        File(dir, "SHA256SUMS.sig").apply { writeBytes(signed.signature) },
    )

    private companion object {
        val KEEP = setOf("SHA256SUMS", "SHA256SUMS.sig")
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
