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
    private val catalog = AblCatalog.current
    /** The Duo's platform (sun) is the 8 Elite's too, so its scripts check the model as well. */
    private val checkedSocModel: String? = info.socModel.takeIf { soc == Soc.SM7750 }
    val route: RootJobs.Route get() = jobs.route

    /** The backup folder of the last backup (app's own storage). */
    val backupDir: File? get() = jobs.extra(AblScript.BACKUP, "dir")?.let(::File)

    private val backups get() = File(context.getExternalFilesDir(null), "abl-backup")

    /** Root's note about an install that is under way or didn't finish (see [AblScript]). Its folder is made here, so it stays readable. */
    private val journal: File get() = File(File(context.getExternalFilesDir(null), "abl-flash").apply { mkdirs() }, "journal")

    /** An install started writing and didn't end with both slots read back. */
    val interrupted: Boolean get() = journal.exists()

    /** The boot menu that install was writing, when this version of the app still has that one to finish it. */
    val interruptedMenu: String? get() = runCatching { journal.readLines() }.getOrNull()
        ?.firstNotNullOfOrNull { l -> l.removePrefix("label=").takeIf { it != l } }
        ?.takeIf { it == catalog.bundled(soc) }

    /** Where the copy of the unfinished install's backup is for the user (the one the app made in Documents), or null. */
    val interruptedBackup: String? get() = runCatching { journal.readLines() }.getOrNull()
        ?.firstNotNullOfOrNull { l -> l.removePrefix("dir=").takeIf { it != l } }
        .let { dir -> (dir ?: backupDir?.path)?.substringAfterLast('/') }
        ?.let { "Documents/PB-OS/ABL backup/${backupName(it)}/" }

    /** Backs up both slots; returns the launcher's name in Downloads, or null when the app starts it itself. */
    fun prepareBackup(): String? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val dir = File(backups, stamp).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val body = AblScript.backupBody(
            AblScript.BackupJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(AblScript.BACKUP)),
                backupDir = RootJobs.rootPath(dir),
                journal = RootJobs.rootPath(journal),
                platform = info.boardPlatform,
                info = listOf(
                    "handheld=$deviceName",
                    "manufacturer=${info.manufacturer}",
                    "model=${info.model}",
                    "fingerprint=${info.fingerprint}",
                ),
                stock = catalog.stock(soc),
                menus = catalog.menus(soc),
                socModel = checkedSocModel,
            )
        )
        return jobs.prepare(AblScript.BACKUP, id, body, mapOf("dir" to dir.absolutePath))
    }

    /**
     * Installs the bundled boot menu (OpenABL); returns the launcher's name, or null when the app starts it itself.
     * [finish]: an install that didn't finish is carried on, with its own backup as the original.
     */
    fun prepareInstall(finish: Boolean = false): String? {
        val dir = if (finish) null else backupDir ?: error("Back up the boot loader first.")
        val payload = catalog.payload(soc) ?: error("this version of the app has no boot menu for the ${soc.label}")
        val id = UUID.randomUUID().toString()
        val body = AblScript.installBody(
            AblScript.InstallJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(AblScript.INSTALL)),
                platform = info.boardPlatform,
                payload = RootJobs.rootPath(payloadFile(payload)),
                label = payload.label,
                payloadBytes = payload.bytes,
                payloadSha256 = payload.sha256,
                backups = RootJobs.rootPath(backups),
                backupDir = dir?.let(RootJobs::rootPath),
                journal = RootJobs.rootPath(journal),
                stock = catalog.stock(soc),
                menus = catalog.menus(soc),
                socModel = checkedSocModel,
                careful = soc == Soc.SM7750,
                onlyOverKnown = catalog.strict(soc),
            )
        )
        return jobs.prepare(AblScript.INSTALL, id, body)
    }

    /** The boot menu's file where root can read it, checked: its size and checksum are the ones the app knows. */
    private fun payloadFile(payload: AblKnown.Payload): File {
        val file = File(context.getExternalFilesDir(null), "abl/${payload.asset.substringAfterLast('/')}")
        file.parentFile!!.mkdirs()
        context.assets.open(payload.asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        check(file.length() == payload.bytes && sha256(file) == payload.sha256) { "The bundled boot menu file is damaged." }
        return file
    }

    private fun afterJob(job: String, id: String) = AblScript.AfterJob(
        id = id,
        statusFile = RootJobs.rootPath(jobs.statusFile(job)),
        platform = info.boardPlatform,
        backups = RootJobs.rootPath(backups),
        journal = RootJobs.rootPath(journal),
        stock = catalog.stock(soc),
        menus = catalog.menus(soc),
        payload = runCatching { catalog.payload(soc)?.let { RootJobs.rootPath(payloadFile(it)) } }.getOrNull(),
        socModel = checkedSocModel,
        careful = soc == Soc.SM7750,
    )

    /** Looks at what the slots hold after an install that didn't finish (writes to neither). */
    fun prepareCheck(): String? {
        val id = UUID.randomUUID().toString()
        return jobs.prepare(AblScript.CHECK, id, AblScript.checkBody(afterJob(AblScript.CHECK, id)))
    }

    /** Puts the original back after an install that didn't finish. */
    fun prepareRestore(): String? {
        val id = UUID.randomUUID().toString()
        return jobs.prepare(AblScript.RESTORE, id, AblScript.restoreBody(afterJob(AblScript.RESTORE, id)))
    }

    /** The app starts root jobs itself here (no launcher for the user to run). */
    val startsItself: Boolean get() = jobs.route == RootJobs.Route.Xsu || jobs.route == RootJobs.Route.PServer

    fun start(job: String): Boolean = jobs.start(job)
    fun fallbackLauncher(job: String): String? = jobs.fallbackLauncher(job)
    fun status(job: String): RootJobs.Status? = jobs.status(job)
    fun clear(job: String) = jobs.clear(job)
    fun drop(job: String) = jobs.drop(job)
    /** A job was prepared and not seen to its end. */
    fun pending(job: String): Boolean = jobs.currentJob(job) != null

    /** The backup's files: abl_a.img, abl_b.img, info.txt. */
    private fun backupFiles(): List<File> =
        backupDir?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    /** A name for copies of this backup, e.g. "KONKR Pocket FIT 2026-10-09_131500". */
    fun backupName(stamp: String = backupDir?.name.orEmpty()): String = "PB-OS ABL backup - $deviceName - $stamp"

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
