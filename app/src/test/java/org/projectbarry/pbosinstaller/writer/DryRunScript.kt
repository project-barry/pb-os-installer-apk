package org.projectbarry.pbosinstaller.writer

import org.projectbarry.pbosinstaller.release.Releases
import java.io.File

/**
 * Writes build/dry-run.sh: the real write script for the alpha-v0.5.2 SM8550 image,
 * writing to /dev/null, for trying the whole run on a handheld over adb without
 * root and without touching the card. SEVENZIP_PATH and CARD_BYTES are filled in
 * when it is pushed (see docs/HOW-IT-WORKS.md).
 */
object DryRunScript {
    @JvmStatic
    fun main(args: Array<String>) {
        val sums = Releases.parseSums(File("src/test/resources/SHA256SUMS").readText())
        val dir = "/sdcard/Android/data/org.projectbarry.pbosinstaller/files/images"
        val parts = (1..3).map { "pb-os-alpha-v0.5.2-sm8550.img.7z.00$it" }.map { "$dir/$it" to sums.getValue(it) }
        val body = WriteScript.body(
            WriteScript.Job(
                id = "dry-run",
                sevenZip = "SEVENZIP_PATH",
                parts = parts,
                imageSha256 = sums.getValue("pb-os-alpha-v0.5.2-sm8550.img"),
                cardBytes = args.firstOrNull()?.toLong(),
                statusFile = "/data/local/tmp/pbos-dry-status",
                dryRunTarget = "/dev/null",
            )
        )
        File("build/dry-run.sh").writeText(body)
    }
}
