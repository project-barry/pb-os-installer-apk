package org.projectbarry.pbosinstaller.abl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.projectbarry.pbosinstaller.device.DeviceInfo
import org.projectbarry.pbosinstaller.device.Soc
import org.projectbarry.pbosinstaller.root.RootJobs
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The boot loader steps on the Android side: prepares the [AblScript] jobs,
 * follows them, and saves copies of the backup where the user can find them.
 */
class AblSetup(private val context: Context, private val info: DeviceInfo, private val soc: Soc, private val deviceName: String) {
    private val jobs = RootJobs(context)
    val route: RootJobs.Route get() = jobs.route

    /** The backup folder of the last backup (app's own storage). */
    val backupDir: File? get() = jobs.extra(AblScript.BACKUP, "dir")?.let(::File)

    /** Backs up both slots; returns the launcher's name in Downloads, or null when the app starts it itself. */
    fun prepareBackup(): String? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "abl-backup/$stamp").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val body = AblScript.backupBody(
            AblScript.BackupJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(AblScript.BACKUP)),
                backupDir = RootJobs.rootPath(dir),
                platform = info.boardPlatform,
                info = listOf(
                    "handheld=$deviceName",
                    "manufacturer=${info.manufacturer}",
                    "model=${info.model}",
                    "fingerprint=${info.fingerprint}",
                ),
                stock = AblKnown.stock,
                rocknix = AblKnown.rocknix.getValue(soc),
            )
        )
        return jobs.prepare(AblScript.BACKUP, id, body, mapOf("dir" to dir.absolutePath))
    }

    /** Installs the ROCKNIX ABL; returns the launcher's name, or null when the app starts it itself. */
    fun prepareInstall(): String? {
        val dir = backupDir ?: error("Back up the boot loader first.")
        val payload = AblKnown.payload.getValue(soc)
        val file = File(context.getExternalFilesDir(null), "abl/${payload.asset.substringAfterLast('/')}")
        file.parentFile!!.mkdirs()
        context.assets.open(payload.asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        check(sha256(file) == payload.sha256) { "The bundled boot menu file is damaged." }
        val id = UUID.randomUUID().toString()
        val body = AblScript.installBody(
            AblScript.InstallJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(AblScript.INSTALL)),
                platform = info.boardPlatform,
                payload = RootJobs.rootPath(file),
                payloadBytes = payload.bytes,
                payloadSha256 = payload.sha256,
                backupDir = RootJobs.rootPath(dir),
            )
        )
        return jobs.prepare(AblScript.INSTALL, id, body)
    }

    fun start(job: String): Boolean = jobs.startWithXsu(job)
    fun status(job: String): RootJobs.Status? = jobs.status(job)
    fun clear(job: String) = jobs.clear(job)

    /** The backup's files: abl_a.img, abl_b.img, info.txt. */
    private fun backupFiles(): List<File> =
        backupDir?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /** A name for copies of this backup, e.g. "KONKR Pocket FIT 2026-10-09_131500". */
    fun backupName(): String = "PB-OS ABL backup - $deviceName - ${backupDir?.name.orEmpty()}"

    /** Copies the backup to Documents/PB-OS/ABL backup/<name>/ (stays on the handheld). */
    fun copyToDocuments(): String {
        val folder = "Documents/PB-OS/ABL backup/${backupName()}/"
        val resolver = context.contentResolver
        backupFiles().forEach { f ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (f.name.endsWith(".txt")) "text/plain" else "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: error("Couldn't save ${f.name} to Documents.")
            resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        }
        return folder
    }

    /** Writes the backup as one zip to where the user chose (Drive, USB drive, …). */
    fun writeZip(uri: Uri) {
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                backupFiles().forEach { f ->
                    zip.putNextEntry(ZipEntry("${backupName()}/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    companion object {
        fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Boot loaders the app knows. Stock ones are the makers' (never shipped, only their checksums). */
object AblKnown {
    data class Payload(val asset: String, val bytes: Long, val sha256: String)

    /** ROCKNIX ABL 1.2, bundled (github.com/ROCKNIX/abl releases; pb-os abl/releases.tsv). */
    val payload = mapOf(
        Soc.SM8550 to Payload("abl/abl_signed-SM8550.elf", 258048, "8c27a70961476b327071ada2fd9b9c55dd2dcdc913eff3d33f2576133d7e398c"),
        Soc.SM8650 to Payload("abl/abl_signed-SM8650.elf", 258048, "053e1e9e8300c29de68866c6c09e5527b1222d2ee774757d9cfbe8346137d6b6"),
    )

    /** ROCKNIX versions, by the SHA-256 of their first 258048 bytes. */
    val rocknix = mapOf(
        Soc.SM8550 to listOf(
            AblScript.Known("ROCKNIX 1.2", "8c27a70961476b327071ada2fd9b9c55dd2dcdc913eff3d33f2576133d7e398c", 258048),
            AblScript.Known("ROCKNIX 1.1.8", "5f1018211feaa109563d5890ab52dccfb31db90f4bbe9e6b7fb39e94f860f8d4", 258048),
        ),
        Soc.SM8650 to listOf(
            AblScript.Known("ROCKNIX 1.2", "053e1e9e8300c29de68866c6c09e5527b1222d2ee774757d9cfbe8346137d6b6", 258048),
            AblScript.Known("ROCKNIX 1.1.8", "4ed04dda4da2ac76bc27925ae68094c055a043697f1a1bee4b958b979905bed5", 258048),
        ),
    )

    /** Stock boot loaders seen on our handhelds, by the SHA-256 of the whole 1 MiB partition. */
    val stock = listOf(
        AblScript.Known("Retroid Pocket Nova (firmware 2026-07-22)", "39615e0d5d6302a512c30c78e49c50004d331de30a3e0b07f619ff5b979e280a", null),
        AblScript.Known("KONKR Pocket FIT (firmware 2025-12-26)", "739202be4e3f781d0a5e0321c5923f05b66da694f59ae90745e0054b2cb02537", null),
    )

    /** "rocknix: ROCKNIX 1.2" on both slots means there is nothing to install. */
    fun alreadyInstalled(slotA: String?, slotB: String?): Boolean =
        slotA == "rocknix: ROCKNIX 1.2" && slotB == "rocknix: ROCKNIX 1.2"
}
