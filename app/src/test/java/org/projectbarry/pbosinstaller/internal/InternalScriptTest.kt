package org.projectbarry.pbosinstaller.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.root.FirstUpdate
import java.io.File

class InternalScriptTest {
    private val g = 1L shl 30
    private val job = InternalScript.InstallJob(
        id = "job-1",
        statusFile = "/data/media/0/Android/data/p/files/internal-install/status",
        sevenZip = "/data/app/x/lib/arm64/lib7zzs.so",
        parts = listOf("/data/media/0/a.7z.001" to "a".repeat(64), "/data/media/0/a.7z.002" to "b".repeat(64)),
        imageSha256 = "c".repeat(64),
        disk = "sda",
        sectorSize = 4096,
        diskBytes = 476L * g,
        primaryEntriesLba = 2,
        backupLba = 124780543,
        backupEntriesLba = 124780542,
        entrySectors = 1,
        primarySha256 = "d".repeat(64),
        backupSha256 = "e".repeat(64),
        boot = InternalLayout.Area(65 * g, 2 * g),
        storage = InternalLayout.Area(67 * g, 20 * g),
        home = InternalLayout.Area(87 * g, 380 * g),
        linux = InternalLayout.Area(65 * g, 402 * g),
        table = listOf(
            InternalScript.TablePart("/x/gpt-entries.bin", 124780542, "1".repeat(64)),
            InternalScript.TablePart("/x/gpt-backup-header.bin", 124780543, "2".repeat(64)),
            InternalScript.TablePart("/x/gpt-entries.bin", 2, "1".repeat(64)),
            InternalScript.TablePart("/x/gpt-primary-header.bin", 1, "3".repeat(64)),
        ),
    )

    private fun assertValidShell(body: String) {
        val f = File.createTempFile("pbos-internal", ".sh")
        f.writeText(body)
        val p = ProcessBuilder("/bin/sh", "-n", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun scriptsAreValidShell() {
        assertValidShell(InternalScript.probeBody(InternalScript.ProbeJob("p", "/x/status", "/x/found")))
        assertValidShell(InternalScript.installBody(job))
        assertValidShell(InternalScript.installBody(job.copy(table = emptyList())))
        assertValidShell(InternalScript.installBody(job.copy(testDiskFile = "/data/local/tmp/disk.img")))
    }

    @Test fun probeOnlyReads() {
        val body = InternalScript.probeBody(InternalScript.ProbeJob("p", "/x/status", "/x/found"))
        assertTrue("/dev/block/by-name/userdata" in body)
        assertFalse("of=\"${'$'}DEV\"" in body)
        listOf("losetup", "mkfs", "mount -t", "conv=notrunc").forEach { assertFalse(it, it in body) }
    }

    @Test fun everyCheckComesBeforeTheFirstWrite() {
        val body = InternalScript.installBody(job)
        val firstWrite = body.indexOf("loop STAGE")
        listOf(
            "gpt_sha 1 ${'$'}PRI_ENT_LBA", // table unchanged since the app read it
            "Android is using part of the Linux space",
            "doesn't fit the STORAGE partition",
            "check_part '/data/media/0/a.7z.002' '${"b".repeat(64)}'",
            "check_part '/x/gpt-primary-header.bin' '${"3".repeat(64)}'",
            "command -v ${'$'}t",
        ).forEach { assertTrue(it, body.indexOf(it) in 0 until firstWrite) }
    }

    @Test fun theOrderIsSafe() {
        val body = InternalScript.installBody(job)
        fun at(s: String) = body.indexOf(s).also { assertTrue(s, it >= 0) }
        // The image is checked before anything is taken from it; home is copied before
        // the system copy overwrites the image; the table is written last.
        assertTrue(at("didn't read back correctly from internal storage") < at("status boot"))
        assertTrue(at("status boot") < at("Copying home failed"))
        assertTrue(at("Home didn't read back") < at("Copying the system failed"))
        assertTrue(at("Reading the system failed") < at("Copying the system failed")) // source checked before it's overwritten
        assertTrue(at("status finishing") < at("status table"))
        assertTrue(at("status table") < at("status done"))
        // Backup copy first, primary header last.
        val writes = Regex("put_table '([^']+)' (\\d+)").findAll(body).map { it.groupValues[2].toLong() }.toList()
        assertEquals(listOf(124780542L, 124780543L, 2L, 1L), writes)
        assertTrue(at("check_table '/x/gpt-primary-header.bin' 1") > at("put_table '/x/gpt-primary-header.bin' 1"))
    }

    @Test fun pbOsKeepsItsTable() {
        val body = InternalScript.installBody(job.copy(table = emptyList()))
        assertFalse("put_table" in body)
        assertFalse("status table" in body)
    }

    @Test fun bootAndMounts() {
        val body = InternalScript.installBody(job)
        // The image's own mkfs.fat: Android's newfs_msdos ignores -S on a loop device.
        assertTrue("/usr/bin/mkfs.fat\" -F 32 -S ${'$'}WANT_SS -n ROCKNIX" in body)
        assertFalse("newfs_msdos -" in body)
        assertTrue("ro,noload ext4" in body)
        assertTrue("retarget_kernel \"${'$'}K\" PARTLABEL=STORAGE" in body)
        assertTrue("root=${'$'}2 rootfstype=ext4 errors=remount-ro" in body)
        assertTrue("PARTLABEL=HOME     /home  ext4" in body)
        assertTrue("var/lib/overlays/etc/upper/fstab" in body)
        assertTrue("chmod 644 \"${'$'}WORK/root/${'$'}f\"" in body)
        // Byte counts: never 32-bit shell arithmetic.
        assertFalse(Regex("""\$\(\((.*)(OFF|LEN)""").containsMatchIn(body))
        // Never delete into a mount point.
        assertFalse("rm -rf \"${'$'}WORK\"\n" in body.substringBefore("clean_work() {"))
        assertTrue("trap 'unmount_all; restore_vm; clean_work; on_exit' EXIT" in body)
    }

    @Test fun keptHomeIsCheckedAndNeverWritten() {
        val body = InternalScript.installBody(job.copy(table = emptyList(), keepHome = true))
        assertValidShell(body)
        assertFalse("Copying home failed" in body)
        assertFalse(Regex("loop DST \\${'$'}HM_OFF").containsMatchIn(body)) // HOME never opened for writing
        assertTrue("loop HOME_CHECK ${'$'}HM_OFF ${'$'}HM_LEN ro" in body)
        // Checked before the first write.
        assertTrue(body.indexOf("isn't a PB-OS home") in 0 until body.indexOf("loop STAGE"))
        assertTrue("Copying the system failed" in body)
        // Only where the table stays (PB-OS over PB-OS).
        assertTrue(runCatching { InternalScript.installBody(job.copy(keepHome = true)) }.isFailure)
        assertTrue("Copying home failed" in InternalScript.installBody(job.copy(table = emptyList())))
    }

    private val update = FirstUpdate.Files(
        "alpha-v0.5.2.10",
        listOf("/u/pb-os-alpha-v0.5.2.10-sm8550.from-alpha-v0.5.2.delta.tar.gz.001" to "f".repeat(64), "/u/SHA256SUMS" to "9".repeat(64)),
    )

    @Test fun firstUpdateGoesInLastAndNeverFails() {
        val body = InternalScript.installBody(job.copy(firstUpdate = update))
        assertValidShell(body)
        assertValidShell(InternalScript.installBody(job.copy(firstUpdate = update, table = emptyList(), keepHome = true)))
        // Checked with the download before anything is written.
        assertTrue(body.indexOf("check_part '/u/SHA256SUMS'") in 0 until body.indexOf("loop STAGE"))
        // Put in while the new system is mounted, after fstab, before the table.
        assertTrue(body.indexOf("place_update \"${'$'}WORK/root\" \"${'$'}WORK/home\"") in body.indexOf("Couldn't write fstab") until body.indexOf("status table"))
        assertTrue("first_update=failed" in body)
        val placeUpdate = body.substringAfter("place_update() {").substringBefore("\n}\n")
        assertFalse("fail " in placeUpdate)
        assertTrue("ConditionPathExists=/home/.pbos-installer/first-update.sh" in body)
        assertTrue("Before=display-manager.service" in body)
        assertTrue("After=local-fs.target steamos-sm8550-expand-home.service" in body)
        assertTrue("konkr-update.py local-update" in body)
        assertTrue("var/lib/overlays/etc/upper" in placeUpdate)
        assertFalse("place_update" in InternalScript.installBody(job))
    }

    @Test fun testDiskSkipsTheRealDisk() {
        val body = InternalScript.installBody(job.copy(testDiskFile = "/data/local/tmp/disk.img"))
        assertTrue("TEST_DISK='/data/local/tmp/disk.img'" in body)
        assertFalse("by-name/userdata" in body)
        assertTrue("TEST_DISK=\n" in InternalScript.installBody(job))
    }
}
