package org.projectbarry.pbosinstaller.repair

import android.content.Context
import org.projectbarry.pbosinstaller.abl.AblKnown
import org.projectbarry.pbosinstaller.device.Soc
import org.projectbarry.pbosinstaller.root.RootJobs
import java.io.File
import java.util.UUID

/** Tools → Repair PB-OS on the Android side: prepares the [RepairScript] jobs and reads what they found. */
class Repairer(context: Context, private val soc: Soc) {
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
                AblKnown.stock, AblKnown.menus.getValue(soc),
            )
        )
        return jobs.prepare(RepairScript.CHECK, id, body)
    }

    fun readCheck(status: RootJobs.Status): RepairReport =
        RepairReport.read(outDir, status.values["found"].orEmpty().split(' ').filter { it.isNotBlank() })

    /**
     * Puts [kernel] (re-pointed at the install) in place of the install's KERNEL;
     * with [undoUpdate], first marks that update for PB-OS's updater to undo.
     */
    fun prepareKernel(install: RepairReport.Install, kernel: File, undoUpdate: String? = null): String? {
        val id = UUID.randomUUID().toString()
        val body = RepairScript.kernelBody(
            RepairScript.KernelJob(
                id = id,
                statusFile = RootJobs.rootPath(jobs.statusFile(RepairScript.KERNEL)),
                place = install.place,
                kernel = RootJobs.rootPath(kernel),
                kernelSha256 = RepairReport.hash("SHA-256", kernel.readBytes()),
                root = install.root ?: error("unknown root for ${install.place}"),
                currentSha256 = install.kernelSha256,
                undoUpdate = undoUpdate,
            )
        )
        return jobs.prepare(RepairScript.KERNEL, id, body)
    }

    fun start(job: String): Boolean = jobs.start(job)
    fun fallbackLauncher(job: String): String? = jobs.fallbackLauncher(job)
    fun status(job: String): RootJobs.Status? = jobs.status(job)
    fun clear(job: String) = jobs.clear(job)
}
