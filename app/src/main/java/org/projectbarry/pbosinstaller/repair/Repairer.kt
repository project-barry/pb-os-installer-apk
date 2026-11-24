package org.projectbarry.pbosinstaller.repair

import android.content.Context
import org.projectbarry.pbosinstaller.abl.AblCatalog
import org.projectbarry.pbosinstaller.device.Soc
import org.projectbarry.pbosinstaller.root.RootJobs
import java.io.File
import java.util.UUID

/**
 * Tools → Repair PB-OS on the Android side: prepares the [RepairScript] jobs and reads what they found.
 * [bundled]: the boot menu the app can install on this handheld, or null for none.
 */
class Repairer(context: Context, private val soc: Soc, private val bundled: String?) {
    private val jobs = RootJobs(context)
    private val outDir get() = File(jobs.statusFile(RepairScript.CHECK).parentFile, "found")

    val route: RootJobs.Route get() = jobs.route

    /** The read-only check; returns the launcher's name, or null when the app starts it itself. */
    fun prepareCheck(): String? {
        // Made by the app, so what root puts there stays readable to it.
        outDir.deleteRecursively()
        outDir.mkdirs()
        val id = UUID.randomUUID().toString()
        val body = RepairScript.checkBody(
            RepairScript.CheckJob(
                id, RootJobs.rootPath(jobs.statusFile(RepairScript.CHECK)), RootJobs.rootPath(outDir),
                AblCatalog.current.stock(soc), AblCatalog.current.menus(soc),
            )
        )
        return jobs.prepare(RepairScript.CHECK, id, body)
    }

    fun readCheck(status: RootJobs.Status): RepairReport =
        RepairReport.read(
            outDir, status.values["found"].orEmpty().split(' ').filter { it.isNotBlank() },
            bundled,
        )

    /**
     * Puts [kernel] (re-pointed at the install) in place of the install's KERNEL;
     * with [undoUpdate], first marks that update for PB-OS's updater to undo.
     * With [entryOnly], [kernel] is the check's copy of the KERNEL in place, and
     * only OpenABL's boot entry is written from it. The entry's pieces ([BootEntry])
     * go to the job's folder; re-pointing changes only the command line, which the
     * job takes from the KERNEL it ends up with.
     */
    fun prepareKernel(install: RepairReport.Install, kernel: File, undoUpdate: String? = null, entryOnly: Boolean = false): String? {
        val id = UUID.randomUUID().toString()
        val bytes = kernel.readBytes()
        val entryDir = File(jobs.statusFile(RepairScript.KERNEL).parentFile, "entry")
        val split = BootEntry.splitOrNull(bytes)
        if (split != null) split.writeTo(entryDir) else entryDir.deleteRecursively()
        val sha = RepairReport.hash("SHA-256", bytes)
        val body = RepairScript.kernelBody(
            RepairScript.KernelJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(RepairScript.KERNEL)),
                place = install.place,
                kernel = RootJobs.rootPath(kernel),
                kernelSha256 = sha,
                root = install.root ?: error("unknown root for ${install.place}"),
                currentSha256 = install.kernelSha256,
                undoUpdate = undoUpdate,
                entry = split?.let { RootJobs.rootPath(entryDir) },
                entryOnly = entryOnly,
            )
        )
        return jobs.prepare(RepairScript.KERNEL, id, body)
    }

    fun start(job: String): Boolean = jobs.start(job)
    fun fallbackLauncher(job: String): String? = jobs.fallbackLauncher(job)
    fun status(job: String): RootJobs.Status? = jobs.status(job)
    fun clear(job: String) = jobs.clear(job)
}
