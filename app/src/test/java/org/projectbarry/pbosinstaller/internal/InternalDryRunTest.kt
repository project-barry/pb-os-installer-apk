package org.projectbarry.pbosinstaller.internal

import org.junit.Assert.assertTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.release.Releases
import java.io.File

/**
 * Writes build/internal-dry-run/: a fake 60 GiB disk with Android and ArmadaOS
 * (disk-head.bin, disk-tail.bin: its first and last MiB) and the real install
 * script pointed at a file made from them, for a run on a handheld as root that
 * touches nothing of its own storage (see docs/HOW-IT-WORKS.md). The image
 * parts are the alpha-v0.5.2 SM8550 ones; IMAGE_DIR is filled in when pushed.
 */
class InternalDryRunTest {
    @Test fun writesDryRun() {
        val out = File("build/internal-dry-run").apply { mkdirs() }
        val ss = 4096
        val g = (1L shl 30) / ss
        val disk = FakeDisk(sectorSize = ss, bytes = 60L shl 30)
        val android = FakeDisk.android(ss, 16)
        val ud = android.last()
        disk.write(
            android + listOf(
                FakeDisk.Part("ARMADA", ud.last + 1, ud.last + g / 2, Guid.BASIC_DATA),
                FakeDisk.Part("ARMADA_BOOT", ud.last + g / 2 + 1, ud.last + g * 3 / 2),
                FakeDisk.Part("ARMADA_ROOT", ud.last + g * 3 / 2 + 1, disk.lastUsable),
            )
        )
        File(out, "disk-head.bin").writeBytes(disk.head)
        File(out, "disk-tail.bin").writeBytes(disk.tail)
        val gpt = disk.gpt()
        val plan = (InternalLayout.inspect(gpt) as InternalLayout.Result.Found).plan
        val table = plan.newTable!!
        val dev = "/data/local/tmp/pbos-dry"
        File(out, "gpt-entries.bin").writeBytes(table.entryArrayBytes())
        File(out, "gpt-backup-header.bin").writeBytes(table.backupHeaderBytes())
        File(out, "gpt-primary-header.bin").writeBytes(table.primaryHeaderBytes())
        fun part(name: String, lba: Long) =
            InternalScript.TablePart("$dev/$name", lba, InternalInstaller.sha256(File(out, name).readBytes()))

        val sums = Releases.parseSums(File("src/test/resources/SHA256SUMS").readText())
        val parts = (1..3).map { "pb-os-alpha-v0.5.2-sm8550.img.7z.00$it" }.map { "IMAGE_DIR/$it" to sums.getValue(it) }
        val body = InternalScript.installBody(
            InternalScript.InstallJob(
                id = "dry-run",
                statusFile = "$dev/status",
                sevenZip = "SEVENZIP_PATH",
                parts = parts,
                imageSha256 = sums.getValue("pb-os-alpha-v0.5.2-sm8550.img"),
                disk = "sda",
                sectorSize = ss,
                diskBytes = disk.bytes,
                primaryEntriesLba = gpt.primaryEntriesLba,
                backupLba = gpt.backupLba,
                backupEntriesLba = gpt.backupEntriesLba,
                entrySectors = gpt.entrySectors,
                primarySha256 = InternalInstaller.sha256(gpt.primaryHeaderBytes() + gpt.entryArrayBytes()),
                backupSha256 = InternalInstaller.sha256(gpt.backupHeaderBytes() + gpt.backupEntryArrayBytes()),
                boot = plan.bootArea,
                storage = plan.storageArea,
                home = plan.homeArea,
                linux = InternalInstaller.linuxArea(plan),
                table = listOf(
                    part("gpt-entries.bin", gpt.backupEntriesLba),
                    part("gpt-backup-header.bin", gpt.backupLba),
                    part("gpt-entries.bin", gpt.primaryEntriesLba),
                    part("gpt-primary-header.bin", 1),
                ),
                testDiskFile = "$dev/disk.img",
            )
        )
        File(out, "install.sh").writeText(body)
        File(out, "plan.txt").writeText(
            listOf(plan.bootArea, plan.storageArea, plan.homeArea).zip(InternalLayout.PBOS_NAMES)
                .joinToString("\n") { (a, n) -> "$n offset=${a.offset} length=${a.length}" } + "\nbytes=${disk.bytes}\n"
        )
        assertTrue("TEST_DISK='$dev/disk.img'" in body)
        // The real check, for running by hand: saves to /data/local/tmp/pbos-probe.
        File(out, "probe.sh").writeText(
            InternalScript.probeBody(
                InternalScript.ProbeJob("probe", "/data/local/tmp/pbos-probe/status", "/data/local/tmp/pbos-probe")
            )
        )
    }

    /**
     * What the app makes of a probe pulled from a handheld (PBOS_PROBE=<folder> with
     * head.bin, tail.bin, parts.txt and status). Skipped without it: dumps stay out of the repo.
     */
    @Test fun readsPulledProbe() {
        val dir = System.getenv("PBOS_PROBE")?.let(::File) ?: return
        val values = File(dir, "status").readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val gpt = Gpt.parse(File(dir, "head.bin").readBytes(), File(dir, "tail.bin").readBytes(), values.getValue("bytes").toLong(), values.getValue("sector").toInt())
        val report = StringBuilder()
        gpt.partitions.forEach { report.append("${it.number} ${it.name} ${it.firstLba}-${it.lastLba} ${it.type}\n") }
        report.append("result: ${InternalLayout.inspect(gpt)}\n")
        (InternalLayout.inspect(gpt) as? InternalLayout.Result.Found)?.plan?.let { p ->
            report.append("android ${InternalLayout.gb(p.androidBytes)} linux ${InternalLayout.gb(p.linuxBytes)}\n")
            listOf(p.boot, p.storage, p.home).forEach { report.append("new ${it.number} ${it.name} ${it.firstLba}-${it.lastLba} (${InternalLayout.gb(it.sectors * gpt.sectorSize)})\n") }
        }
        File(dir, "report.txt").writeText(report.toString())
    }
}
