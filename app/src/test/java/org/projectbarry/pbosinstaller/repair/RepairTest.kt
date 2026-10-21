package org.projectbarry.pbosinstaller.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.abl.AblKnown
import org.projectbarry.pbosinstaller.device.Soc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class RepairTest {
    /** A version 0 boot image like PB-OS's KERNEL, with [cmdline]. */
    private fun bootImage(cmdline: String, kernel: ByteArray = ByteArray(5000) { it.toByte() }, ramdisk: ByteArray = ByteArray(3000) { 7 }): ByteArray {
        val page = 4096
        fun pad(b: ByteArray) = b + ByteArray((page - b.size % page) % page)
        val sha = MessageDigest.getInstance("SHA-1")
        for (blob in listOf(kernel, ramdisk, ByteArray(0))) {
            sha.update(blob)
            sha.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.size).array())
        }
        val h = ByteBuffer.allocate(page).order(ByteOrder.LITTLE_ENDIAN)
        h.put("ANDROID!".toByteArray())
        h.putInt(kernel.size).putInt(0x8000).putInt(ramdisk.size).putInt(0x1000000).putInt(0).putInt(0).putInt(0x100).putInt(page).putInt(0).putInt(0)
        h.position(64); h.put(cmdline.toByteArray())
        h.position(576); h.put(sha.digest())
        return pad(h.array()) + pad(kernel) + pad(ramdisk)
    }

    private val internalCmd = "console=tty0 quiet rw rootwait root=PARTLABEL=STORAGE rootfstype=ext4 errors=remount-ro"

    @Test fun bootImageIsReadAndChecked() {
        val img = bootImage(internalCmd)
        val b = BootImage.parse(img)!!
        assertEquals("PARTLABEL=STORAGE", b.root)
        assertTrue(b.idOk)
        val damaged = img.copyOf().also { it[4096 + 10] = (it[4096 + 10] + 1).toByte() }
        assertFalse(BootImage.parse(damaged)!!.idOk)
        assertNull(BootImage.parse(ByteArray(100)))
        assertNull(BootImage.parse(img.copyOf(5000)))
    }

    /** The real 0.5.2.10 KERNEL, when PBOS_KERNEL points at it (not in the repo). */
    @Test fun realKernel() {
        val f = System.getenv("PBOS_KERNEL")?.let(::File) ?: return
        val b = BootImage.parse(f.readBytes())!!
        assertTrue(b.idOk)
        assertTrue(b.root!!.startsWith("PARTUUID="))
    }

    private fun installDir(
        kernel: ByteArray? = bootImage(internalCmd),
        info: String = "boot_sector=4096\nboot_mounts=1\nroot_mounts=1\ninit=1\nhome_mounts=1\n",
        update: Pair<String, ByteArray?>? = null,
        flags: String = "backup=1\nrecovery=1\n",
    ): File {
        val d = Files.createTempDirectory("repair").toFile()
        File(d, "info").writeText(info)
        kernel?.let { File(d, "KERNEL").writeBytes(it) }
        File(d, "version").writeText("alpha-v0.5.2.10\n")
        File(d, "fstab").writeText("PARTLABEL=STORAGE / ext4 defaults 0 1\n")
        update?.let { (state, prev) ->
            val u = File(d, "updates/1b4e28ba-2fa1-11d2-883f-0016d3cca427").apply { mkdirs() }
            File(u, "state.json").writeText("""{"state": "$state", "time": 1760000000.5}""")
            File(u, "version").writeText(""""version": "alpha-v0.5.2.10"""")
            prev?.let { File(u, "previous-KERNEL").writeBytes(it) }
            File(u, "flags").writeText(flags)
        }
        return d
    }

    @Test fun healthyInstallNeedsNothing() {
        val (install, fixes) = RepairReport.judge(RepairScript.Place.Internal, installDir())
        assertTrue(install.healthy)
        assertTrue(fixes.isEmpty())
        assertEquals("PB-OS on internal storage (alpha-v0.5.2.10)", install.title)
    }

    @Test fun theLastUpdateCanBeUndone() {
        // The Thor case: the update installed, the device no longer starts; Game Mode never ran,
        // so the updater still has its backup, the previous kernel and its own copy.
        val (install, fixes) = RepairReport.judge(RepairScript.Place.Internal, installDir(update = "committed" to bootImage(internalCmd, kernel = ByteArray(4000) { 3 })))
        val fix = fixes.single() as RepairReport.Fix.UndoUpdate
        assertEquals("alpha-v0.5.2.10", fix.update.version)
        assertEquals("1b4e28ba-2fa1-11d2-883f-0016d3cca427", fix.update.id)
        assertEquals("Undo Update alpha-v0.5.2.10", fix.label)
        assertTrue(install.findings.any { "alpha-v0.5.2.10 installed" in it.text })
        // Not offered when the updater cleaned up (Game Mode ran), a part is missing, the copy is damaged,
        // the update didn't finish, or another update is waiting.
        fun none(d: File) = assertTrue(RepairReport.judge(RepairScript.Place.Internal, d).second.isEmpty())
        none(installDir(update = "committed" to null))
        none(installDir(update = "committed" to bootImage(internalCmd), flags = "recovery=1\n"))
        none(installDir(update = "committed" to bootImage(internalCmd), flags = "backup=1\n"))
        none(installDir(update = "committed" to ByteArray(10000)))
        none(installDir(update = "committed" to bootImage(internalCmd)).also { File(it, "pending").writeText("x\n") })
        val (halfway, halfwayFixes) = RepairReport.judge(RepairScript.Place.Internal, installDir(update = "applying" to bootImage(internalCmd)))
        assertFalse(halfway.healthy)
        assertTrue(halfwayFixes.isEmpty())
    }

    @Test fun wrongRootIsRepointed() {
        val sd = installDir(
            kernel = bootImage("quiet root=PARTUUID=00000000-02"),
            info = "boot_mounts=1\nroot_mounts=1\ninit=1\nhome_mounts=1\ncard_disk_id=8a1b2c3d\n",
        )
        val (install, fixes) = RepairReport.judge(RepairScript.Place.Card, sd)
        assertEquals("PARTUUID=8a1b2c3d-02", install.root)
        assertFalse(install.healthy)
        assertTrue(fixes.single() is RepairReport.Fix.Repoint)
        // The right one is left alone.
        val ok = installDir(kernel = bootImage("root=PARTUUID=8a1b2c3d-02"), info = "boot_mounts=1\nroot_mounts=1\ninit=1\nhome_mounts=1\ncard_disk_id=8a1b2c3d\n")
        assertTrue(RepairReport.judge(RepairScript.Place.Card, ok).first.healthy)
    }

    @Test fun anEarlierRepairCanBeUndone() {
        val d = installDir()
        File(d, "KERNEL.bad").writeBytes(bootImage(internalCmd, kernel = ByteArray(6000) { 9 }))
        val (install, fixes) = RepairReport.judge(RepairScript.Place.Internal, d)
        assertTrue(install.healthy)
        assertTrue(fixes.single() is RepairReport.Fix.Undo)
        File(d, "KERNEL.bad").writeBytes(ByteArray(9000))   // damaged: not offered
        assertTrue(RepairReport.judge(RepairScript.Place.Internal, d).second.isEmpty())
    }

    @Test fun brokenPartsAreProblems() {
        assertFalse(RepairReport.judge(RepairScript.Place.Internal, installDir(kernel = null)).first.healthy)
        assertFalse(RepairReport.judge(RepairScript.Place.Internal, installDir(kernel = ByteArray(9000))).first.healthy)
        assertFalse(RepairReport.judge(RepairScript.Place.Internal, installDir(info = "boot_mounts=1\nroot_mounts=0\nhome_mounts=1\n")).first.healthy)
        val fat512 = RepairReport.judge(RepairScript.Place.Internal, installDir(info = "boot_sector=512\nboot_mounts=1\nroot_mounts=1\ninit=1\nhome_mounts=1\n")).first
        assertTrue(fat512.findings.any { "512-byte" in it.text })
    }

    @Test fun bootMenuGoneOffersReinstall() {
        val f = Files.createTempFile("abl", "").toFile()
        f.writeText("abl_a=stock: Retroid Pocket Nova (firmware 2026-07-22)\nabl_b=rocknix: ROCKNIX 1.2\n")
        val (findings, fix) = RepairReport.bootMenu(f)
        assertEquals(RepairReport.Level.Problem, findings[0].level)
        assertEquals(RepairReport.Fix.BootMenu, fix)
        // Working older menus: fine, but the bundled one is offered.
        f.writeText("abl_a=rocknix: ROCKNIX 1.2\nabl_b=rocknix: ROCKNIX 1.1.8\n")
        val (older, switch) = RepairReport.bootMenu(f)
        assertTrue(older.none { it.level == RepairReport.Level.Problem })
        assertEquals(RepairReport.Fix.BootMenu, switch)
        f.writeText("abl_a=menu: OpenABL 1.2\nabl_b=menu: ROCKNIX 1.2\n")
        val (ok, mixed) = RepairReport.bootMenu(f)
        assertEquals(RepairReport.Fix.BootMenu, mixed)
        assertEquals("Slot A: OpenABL 1.2 boot menu.", ok.first().text)
        // OpenABL 1.1 (app 0.4.1) works, is called older, and 1.2 is offered.
        f.writeText("abl_a=menu: OpenABL 1.1\nabl_b=menu: OpenABL 1.1\n")
        val (old11, update) = RepairReport.bootMenu(f)
        assertTrue(old11.none { it.level == RepairReport.Level.Problem })
        assertEquals("Slot A: OpenABL 1.1 boot menu (older version).", old11.first().text)
        assertEquals(RepairReport.Fix.BootMenu, update)
        f.writeText("abl_a=menu: OpenABL 1.2\nabl_b=menu: OpenABL 1.2\n")
        assertNull(RepairReport.bootMenu(f).second)
    }

    private fun assertValidShell(body: String) {
        val f = File.createTempFile("pbos-repair", ".sh")
        f.writeText(body)
        val p = ProcessBuilder("/bin/sh", "-n", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun checkOnlyReads() {
        val body = RepairScript.checkBody(RepairScript.CheckJob("c", "/x/status", "/x/found", AblKnown.stock, AblKnown.menus.getValue(Soc.SM8550)))
        assertValidShell(body)
        assertFalse(Regex("mount -t \\S+ -o rw").containsMatchIn(body))
        assertFalse("of=" in body)
        assertFalse("sm unmount" in body)
        assertTrue("[ -L \"\$t/recovery/usr/bin/python3\" ]" in body) // a symlink to /usr/bin/python3.12 (seen on the Nova)
        listOf("losetup -f -s -r", "ro,noload", "/dev/block/by-name/ROCKNIX", "previous-KERNEL", "abl_", "card_disk_id")
            .forEach { assertTrue(it, it in body) }
    }

    @Test fun undoMarksTheUpdateBeforeTheKernel() {
        val job = RepairScript.KernelJob("k", "/x/status", RepairScript.Place.Internal, "/x/prev", "a".repeat(64),
            "PARTLABEL=STORAGE", "b".repeat(64), undoUpdate = "1b4e28ba-2fa1-11d2-883f-0016d3cca427")
        val body = RepairScript.kernelBody(job)
        assertValidShell(body)
        fun at(s: String) = body.indexOf(s).also { assertTrue(s, it >= 0) }
        // Every check before the first write.
        assertTrue(at("isn't in a state that can be undone") < at("rollback-requested"))
        assertTrue(at("backup is incomplete") < at("rollback-requested"))
        assertTrue(at("Another update is waiting") < at("rollback-requested"))
        // The updater's own formats: state.json, then the root-owned pending marker; then the kernel.
        assertTrue(at("{\"state\": \"rollback-requested\"") < at("> \"\$P\""))
        assertTrue("chown 0:0 \"\$P\" && chmod 600 \"\$P\"" in body)
        assertTrue(at("> \"\$P\"") < at("KERNEL.new"))
        // The kernel an undo replaces is never offered back by the check (it's the newer system's).
        assertTrue("OLD=KERNEL.before-undo" in body)
        assertTrue("OLD=KERNEL.bad" in RepairScript.kernelBody(job.copy(undoUpdate = null)))
        // Read back, owner and mode included.
        assertTrue(at("= 0600 ]") > at("status verifying"))
        // The shared script start is intact (its \$@ must not be rewritten).
        assertTrue("chown \"\$OWNER\" \"\$@\"" in body)
        assertFalse("\$\$OWNER" in body)
        assertTrue(runCatching { RepairScript.kernelBody(job.copy(undoUpdate = "x; rm -rf /")) }.isFailure)
    }

    @Test fun kernelFixKeepsTheOldOneAndReadsBack() {
        val job = RepairScript.KernelJob("k", "/x/status", RepairScript.Place.Card, "/x/found/card/updates/u/previous-KERNEL", "a".repeat(64), "PARTUUID=8a1b2c3d-02", "b".repeat(64))
        val body = RepairScript.kernelBody(job)
        assertValidShell(body)
        assertValidShell(RepairScript.kernelBody(job.copy(place = RepairScript.Place.Internal, currentSha256 = null)))
        fun at(s: String) = body.indexOf(s).also { assertTrue(s, it >= 0) }
        assertTrue(at("sm unmount") < at("status writing"))                         // the card's FAT is let go first
        assertTrue(at("retarget_kernel \"\$NEW\" \"\$ROOT\"") < at("status writing"))  // prepared before anything changes
        assertTrue(at("changed since the check") < at("status writing"))
        assertTrue(at("KERNEL.bad") < at("KERNEL.new"))
        assertTrue(at("status verifying") < at("status done"))
        assertTrue("md5sum -c" in body)
        // Android detaches a loop device on unmount (seen on the Nova): the read-back needs a new one.
        assertTrue(at("open boot \"\$BOOT\" ro vfat") > at("status verifying"))
        assertTrue("then l=\$(losetup -f -s -r \"\$2\"); else l=\$(losetup -f -s \"\$2\"); fi" in body)
        assertFalse("sm unmount" in RepairScript.kernelBody(job.copy(place = RepairScript.Place.Internal)))
    }
}
