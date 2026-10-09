package org.projectbarry.pbosinstaller.writer

import android.content.Context
import org.projectbarry.pbosinstaller.root.RootJobs
import java.io.File
import java.util.UUID

/**
 * Prepares a card write and follows it. The write runs as root ([WriteScript]);
 * [RootJobs] starts it through xsu where the handheld has it, or saves the
 * launcher for "Run Script as Root".
 */
class CardWriter(context: Context) {
    private val jobs = RootJobs(context)
    private val name = WriteScript.NAME

    val route: RootJobs.Route get() = jobs.route
    val currentJob: String? get() = jobs.currentJob(name)
    val currentTag: String? get() = jobs.extra(name, "tag")

    /** 7-Zip, shipped as a native library so Android installs it as a runnable file. */
    private val sevenZip = File(context.applicationInfo.nativeLibraryDir, "lib7zzs.so")

    /** Saves the job; returns the launcher's name in Downloads, or null when the app starts it itself. */
    fun prepare(tag: String, parts: List<Pair<File, String>>, imageSha256: String, cardBytes: Long?): String? {
        val id = UUID.randomUUID().toString()
        val body = WriteScript.body(
            WriteScript.Job(
                id = id,
                sevenZip = sevenZip.absolutePath,
                parts = parts.map { (file, sha) -> RootJobs.rootPath(file) to sha },
                imageSha256 = imageSha256,
                cardBytes = cardBytes,
                statusFile = RootJobs.rootPath(jobs.statusFile(name)),
            )
        )
        return jobs.prepare(name, id, body, mapOf("tag" to tag))
    }

    /** Starts the prepared job through xsu. */
    fun start(): Boolean = jobs.startWithXsu(name)

    fun status(): RootJobs.Status? = jobs.status(name)

    fun clear() = jobs.clear(name)
}
