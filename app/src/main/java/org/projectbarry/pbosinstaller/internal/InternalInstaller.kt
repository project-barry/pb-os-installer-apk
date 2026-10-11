package org.projectbarry.pbosinstaller.internal

import android.content.Context
import org.projectbarry.pbosinstaller.root.FirstUpdate
import org.projectbarry.pbosinstaller.root.RootJobs
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Installing over a Linux install on internal storage, on the Android side:
 * prepares the [InternalScript] jobs, reads what the probe found and follows
 * the install. Root comes from the same routes as the card write ([RootJobs]).
 */
class InternalInstaller(context: Context) {
    private val jobs = RootJobs(context)

    val route: RootJobs.Route get() = jobs.route
    val currentTag: String? get() = jobs.extra(InternalScript.INSTALL, "tag")

    /** What the probe found: the disk, its table and the kernel's partitions. */
    data class Probe(
        val disk: String,
        val sectorSize: Int,
        val diskBytes: Long,
        val gpt: Gpt,
        /** Kernel partition number → in use (mounted or held). */
        val inUse: Map<Int, Boolean>,
        /** Kernel partition number → (start, size) in 512-byte units. */
        val kernelView: Map<Int, Pair<Long, Long>>,
    )

    private val probeDir get() = File(jobs.statusFile(InternalScript.PROBE).parentFile, "found")

    /** Reads internal storage (changes nothing); returns the launcher's name, or null when the app starts it itself. */
    fun prepareProbe(): String? {
        val id = UUID.randomUUID().toString()
        // Made by the app, so the files root puts there stay readable to it.
        probeDir.deleteRecursively()
        probeDir.mkdirs()
        val body = InternalScript.probeBody(
            InternalScript.ProbeJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(InternalScript.PROBE)),
                outDir = RootJobs.rootPath(probeDir),
            )
        )
        return jobs.prepare(InternalScript.PROBE, id, body)
    }

    /** The probe's result, once its status says done. Throws [GptError] if the table can't be read. */
    fun readProbe(status: RootJobs.Status): Probe {
        val disk = status.values["disk"] ?: throw GptError("the check didn't name a disk")
        val ss = status.values["sector"]?.toIntOrNull() ?: throw GptError("the check didn't give a sector size")
        val bytes = status.values["bytes"]?.toLongOrNull() ?: throw GptError("the check didn't give a disk size")
        val gpt = Gpt.parse(File(probeDir, "head.bin").readBytes(), File(probeDir, "tail.bin").readBytes(), bytes, ss)
        val inUse = mutableMapOf<Int, Boolean>()
        val view = mutableMapOf<Int, Pair<Long, Long>>()
        File(probeDir, "parts.txt").readLines().forEach { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size == 4) {
                val n = f[0].toIntOrNull() ?: return@forEach
                view[n] = (f[1].toLongOrNull() ?: 0) to (f[2].toLongOrNull() ?: 0)
                inUse[n] = f[3] == "1"
            }
        }
        return Probe(disk, ss, bytes, gpt, inUse, view)
    }

    /**
     * The kernel's partitions inside the Linux space that Android has in use. The
     * install refuses to start while there are any.
     */
    fun busyInLinuxSpace(probe: Probe, plan: InternalLayout.Plan): List<Int> {
        val space = linuxArea(plan)
        return probe.kernelView.filter { (n, v) ->
            val start = v.first * 512
            val end = start + v.second * 512
            probe.inUse[n] == true && start < space.offset + space.length && space.offset < end
        }.keys.sorted()
    }

    fun probeStatus(): RootJobs.Status? = jobs.status(InternalScript.PROBE)
    fun startProbe(): Boolean = jobs.start(InternalScript.PROBE)
    fun probeFallback(): String? = jobs.fallbackLauncher(InternalScript.PROBE)
    fun clearProbe() = jobs.clear(InternalScript.PROBE)

    /** Saves the install job; returns the launcher's name, or null when the app starts it itself. */
    fun prepareInstall(
        tag: String,
        probe: Probe,
        plan: InternalLayout.Plan,
        sevenZip: File,
        parts: List<Pair<File, String>>,
        imageSha256: String,
        keepHome: Boolean,
        firstUpdate: FirstUpdate.Files? = null,
    ): String? {
        require(!keepHome || plan.kind == InternalLayout.Kind.PbOs)
        val id = UUID.randomUUID().toString()
        val gpt = plan.gpt
        val dir = jobs.statusFile(InternalScript.INSTALL).parentFile!!
        // The new table, backup copy first and the primary header last.
        val table = plan.newTable?.let { new ->
            val entries = File(dir, "gpt-entries.bin").apply { writeBytes(new.entryArrayBytes()) }
            val backup = File(dir, "gpt-backup-header.bin").apply { writeBytes(new.backupHeaderBytes()) }
            val primary = File(dir, "gpt-primary-header.bin").apply { writeBytes(new.primaryHeaderBytes()) }
            listOf(
                InternalScript.TablePart(RootJobs.rootPath(entries), gpt.backupEntriesLba, sha256(entries.readBytes())),
                InternalScript.TablePart(RootJobs.rootPath(backup), gpt.backupLba, sha256(backup.readBytes())),
                InternalScript.TablePart(RootJobs.rootPath(entries), gpt.primaryEntriesLba, sha256(entries.readBytes())),
                InternalScript.TablePart(RootJobs.rootPath(primary), 1, sha256(primary.readBytes())),
            )
        }.orEmpty()
        val body = InternalScript.installBody(
            InternalScript.InstallJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(InternalScript.INSTALL)),
                sevenZip = sevenZip.absolutePath,
                parts = parts.map { (file, sha) -> RootJobs.rootPath(file) to sha },
                imageSha256 = imageSha256,
                disk = probe.disk,
                sectorSize = probe.sectorSize,
                diskBytes = probe.diskBytes,
                primaryEntriesLba = gpt.primaryEntriesLba,
                backupLba = gpt.backupLba,
                backupEntriesLba = gpt.backupEntriesLba,
                entrySectors = gpt.entrySectors,
                primarySha256 = sha256(gpt.primaryHeaderBytes() + gpt.entryArrayBytes()),
                backupSha256 = sha256(gpt.backupHeaderBytes() + gpt.backupEntryArrayBytes()),
                boot = plan.bootArea,
                storage = plan.storageArea,
                home = plan.homeArea,
                linux = linuxArea(plan),
                table = table,
                keepHome = keepHome,
                firstUpdate = firstUpdate,
            )
        )
        return jobs.prepare(InternalScript.INSTALL, id, body, mapOf("tag" to tag))
    }

    fun start(): Boolean = jobs.start(InternalScript.INSTALL)
    fun fallbackLauncher(): String? = jobs.fallbackLauncher(InternalScript.INSTALL)
    fun status(): RootJobs.Status? = jobs.status(InternalScript.INSTALL)
    fun clear() = jobs.clear(InternalScript.INSTALL)

    companion object {
        /** From the first old partition's start to the last one's end. */
        fun linuxArea(plan: InternalLayout.Plan): InternalLayout.Area {
            val ss = plan.gpt.sectorSize
            val first = plan.old.first().firstLba
            return InternalLayout.Area(first * ss, (plan.old.last().lastLba - first + 1) * ss)
        }

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
