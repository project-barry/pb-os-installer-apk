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

    /** An install whose boot partition has OpenABL's entry for [entryKernel] (the check's pbos.conf and entry-files). */
    private fun withEntry(kernel: ByteArray, entryKernel: ByteArray = kernel): File {
        val d = installDir(kernel = kernel)
        val s = BootEntry.split(entryKernel)
        val tag = BootEntry.tag(entryKernel)
        File(d, "pbos.conf").writeText(BootEntry.conf(tag, s.cmdline))
        File(d, "entry-files").writeText(
            (listOf("pbos/Image-$tag.gz" to s.gz, "pbos/initrd-$tag.gz" to s.initrd) + s.dtbs.map { (n, b) -> "dtbs/qcom/$n" to b })
                .joinToString("") { (path, b) -> "${RepairReport.hash("SHA-256", b)}  $path\n" }
        )
        return d
    }

    @Test fun bootEntryIsChecked() {
        val k = BootEntryTest.kernel(internalCmd)
        // Matches: fine, nothing to do.
        val (good, none) = RepairReport.judge(RepairScript.Place.Internal, withEntry(k))
        assertTrue(good.healthy)
        assertTrue(good.findings.any { it.level == RepairReport.Level.Ok && "entry starts this kernel" in it.text })
        assertTrue(none.isEmpty())
        // No entry (an image from before it): a note only.
        val (old, oldFixes) = RepairReport.judge(RepairScript.Place.Internal, installDir(kernel = k))
        assertTrue(old.healthy)
        assertTrue(old.findings.any { it.level == RepairReport.Level.Note && "No boot entry" in it.text })
        assertTrue(oldFixes.isEmpty())
        // Still the card's entry (the internal install before this app version): written again.
        val card = BootEntryTest.repoint(k, "quiet root=PARTUUID=1a2b3c4d-02 rootfstype=ext4")
        val (stale, fixes) = RepairReport.judge(RepairScript.Place.Internal, withEntry(k, entryKernel = card))
        assertFalse(stale.healthy)
        assertTrue(fixes.single() is RepairReport.Fix.Entry)
        // A file missing or different is a mismatch too.
        val missing = withEntry(k).also { d -> File(d, "entry-files").writeText(File(d, "entry-files").readLines().drop(1).joinToString("\n")) }
        assertTrue(RepairReport.judge(RepairScript.Place.Internal, missing).second.single() is RepairReport.Fix.Entry)
        // Wrong root as well: re-pointing writes the entry too, so only that is offered.
        val (_, both) = RepairReport.judge(RepairScript.Place.Internal, withEntry(card, entryKernel = k))
        assertTrue(both.single() is RepairReport.Fix.Repoint)
        // A kernel that can't start from an entry, under an entry: a problem, no entry fix.
        val d = withEntry(k).also { File(it, "KERNEL").writeBytes(bootImage(internalCmd)) }
        val (unsplittable, noFix) = RepairReport.judge(RepairScript.Place.Internal, d)
        assertFalse(unsplittable.healthy)
        assertTrue(noFix.isEmpty())
    }

    @Test fun fixesTheJobWouldRefuseArentOffered() {
        val k = BootEntryTest.kernel(internalCmd)
        val cantStart = bootImage(internalCmd, kernel = ByteArray(6000) { 9 })   // no EFI stub, DTBs
        fun judge(d: File) = RepairReport.judge(RepairScript.Place.Internal, d).second
        // An earlier repair's kernel: only with an entry on the boot partition does it have to start from one.
        fun bad(d: File, kernel: ByteArray) = d.also { File(it, "KERNEL.bad").writeBytes(kernel) }
        assertTrue(judge(bad(withEntry(k), cantStart)).isEmpty())
        assertTrue(judge(bad(withEntry(k), k)).single() is RepairReport.Fix.Undo)
        assertTrue(judge(bad(installDir(kernel = k), cantStart)).single() is RepairReport.Fix.Undo)
        // The last update's previous kernel.
        fun updated(d: File, prev: ByteArray) = d.also {
            val u = File(it, "updates/1b4e28ba-2fa1-11d2-883f-0016d3cca427").apply { mkdirs() }
            File(u, "state.json").writeText("""{"state": "committed", "time": 1760000000.5}""")
            File(u, "version").writeText(""""version": "alpha-v0.5.2.10"""")
            File(u, "previous-KERNEL").writeBytes(prev)
            File(u, "flags").writeText("backup=1\nrecovery=1\n")
        }
        assertTrue(judge(updated(withEntry(k), cantStart)).isEmpty())
        assertTrue(judge(updated(withEntry(k), k)).single() is RepairReport.Fix.UndoUpdate)
        assertTrue(judge(updated(installDir(kernel = k), cantStart)).single() is RepairReport.Fix.UndoUpdate)
        // A KERNEL with the wrong root that can't start from the entry.
        val wrongRoot = bootImage("quiet root=PARTUUID=00000000-02")
        assertTrue(judge(withEntry(k).also { File(it, "KERNEL").writeBytes(wrongRoot) }).isEmpty())
        assertTrue(judge(installDir(kernel = wrongRoot)).single() is RepairReport.Fix.Repoint)
    }

    @Test fun bootMenuGoneOffersReinstall() {
        val f = Files.createTempFile("abl", "").toFile()
        f.writeText("abl_a=stock: Retroid Pocket Nova (firmware 2026-07-22)\nabl_b=rocknix: ROCKNIX 1.2\n")
        val (findings, fix) = RepairReport.bootMenu(f, AblKnown.FORK_12)
        assertEquals(RepairReport.Level.Problem, findings[0].level)
        assertEquals(RepairReport.Fix.BootMenu, fix)
        // Working older menus: fine, but the bundled one is offered.
        f.writeText("abl_a=rocknix: ROCKNIX 1.2\nabl_b=rocknix: ROCKNIX 1.1.8\n")
        val (older, switch) = RepairReport.bootMenu(f, AblKnown.FORK_12)
        assertTrue(older.none { it.level == RepairReport.Level.Problem })
        assertEquals(RepairReport.Fix.BootMenu, switch)
        f.writeText("abl_a=menu: OpenABL 1.2 (PB-OS fork)\nabl_b=menu: ROCKNIX 1.2\n")
        val (ok, mixed) = RepairReport.bootMenu(f, AblKnown.FORK_12)
        assertEquals(RepairReport.Fix.BootMenu, mixed)
        assertEquals("Slot A: OpenABL 1.2 (PB-OS fork) boot menu.", ok.first().text)
        // OpenABL 1.1 (app 0.4.1) works, is called older, and the bundled one is offered.
        f.writeText("abl_a=menu: OpenABL 1.1\nabl_b=menu: OpenABL 1.1\n")
        val (old11, update) = RepairReport.bootMenu(f, AblKnown.FORK_12)
        assertTrue(old11.none { it.level == RepairReport.Level.Problem })
        assertEquals("Slot A: OpenABL 1.1 boot menu (older version).", old11.first().text)
        assertEquals(RepairReport.Fix.BootMenu, update)
        f.writeText("abl_a=menu: OpenABL 1.2 (PB-OS fork)\nabl_b=menu: OpenABL 1.2 (PB-OS fork)\n")
        assertNull(RepairReport.bootMenu(f, AblKnown.FORK_12).second)
    }

    /** A handheld the app has no boot menu for (a Duo before its OpenABL): said, but no fix that could flash another's. */
    @Test fun noBootMenuForThisHandheldMeansNoFix() {
        val f = Files.createTempFile("abl", "").toFile()
        f.writeText("abl_a=stock: Retroid Pocket Duo (older firmware)\nabl_b=unknown\n")
        val (findings, fix) = RepairReport.bootMenu(f, null)
        assertEquals(listOf(RepairReport.Level.Note, RepairReport.Level.Problem), findings.map { it.level })
        assertNull(fix)
        f.writeText("abl_a=menu: OpenABL 1.1\nabl_b=menu: OpenABL 1.1\n")
        assertNull(RepairReport.bootMenu(f, null).second)
        // An install that didn't finish is said first, and its own screen is the way on.
        f.writeText("abl_a=stock: X\nabl_b=unknown\nunfinished=1\n")
        val (half, way) = RepairReport.bootMenu(f, null)
        assertEquals("A boot menu install didn't finish.", half.single().text)
        assertEquals(RepairReport.Fix.BootMenu, way)
        // OpenABL-PB: the fork build before it works, is called older, and OpenABL-PB is offered.
        f.writeText("abl_a=menu: OpenABL-PB 1.2\nabl_b=menu: OpenABL-PB 1.2\n")
        val (own, none) = RepairReport.bootMenu(f, "OpenABL-PB 1.2")
        assertEquals("Slot A: OpenABL-PB 1.2 boot menu.", own.first().text)
        assertNull(none)
        f.writeText("abl_a=menu: OpenABL 1.2 (PB-OS fork)\nabl_b=menu: OpenABL-PB 1.2\n")
        val (mixed, offered) = RepairReport.bootMenu(f, "OpenABL-PB 1.2")
        assertEquals("Slot A: OpenABL 1.2 (PB-OS fork) boot menu (older version).", mixed.first().text)
        assertEquals("OpenABL-PB 1.2 can replace this boot menu.", mixed.last().text)
        assertEquals(RepairReport.Fix.BootMenu, offered)
    }

    private fun assertValidShell(body: String) {
        val f = File.createTempFile("pbos-repair", ".sh")
        f.writeText(body)
        val p = ProcessBuilder("/bin/sh", "-n", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun checkOnlyReads() {
        val body = RepairScript.checkBody(RepairScript.CheckJob("c", "/x/status", "/x/found", AblKnown.stock.getValue(Soc.SM8550), AblKnown.menus.getValue(Soc.SM8550)))
        assertValidShell(body)
        assertFalse(Regex("mount -t \\S+ -o rw").containsMatchIn(body))
        assertFalse("of=" in body)
        assertFalse("sm unmount" in body)
        assertTrue("[ -L \"\$t/recovery/usr/bin/python3\" ]" in body) // a symlink to /usr/bin/python3.12 (seen on the Nova)
        listOf("losetup -f -s -r", "ro,noload", "/dev/block/by-name/ROCKNIX", "previous-KERNEL", "abl_", "card_disk_id", "\"\$D/pbos.conf\"", "\"\$D/entry-files\"")
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

    @Test fun theEntryFollowsTheKernel() {
        val job = RepairScript.KernelJob("k", "/x/status", RepairScript.Place.Internal, "/x/found/internal/KERNEL.bad", "a".repeat(64),
            "PARTLABEL=STORAGE", "b".repeat(64), entry = "/x/repair-kernel/entry")
        val body = RepairScript.kernelBody(job)
        assertValidShell(body)
        fun at(s: String) = body.indexOf(s).also { assertTrue(s, it >= 0) }
        assertTrue("ENTRY='/x/repair-kernel/entry'" in body)
        // Only where the boot partition has an entry; checked against the new kernel before anything changes.
        assertTrue("if [ -f \"\$MNT/boot/loader/entries/pbos.conf\" ]; then" in body)
        assertTrue(at("kernel_has \"\$NEW\" \"\$ENTRY/Image.gz\"") < at("status writing"))
        assertTrue(at("can't start from the boot menu's entry") < at("status writing"))
        // pbos-update.py's order: the KERNEL first, then its entry; both read back.
        assertTrue(at("mv -f \"\$MNT/boot/KERNEL.new\" \"\$MNT/boot/KERNEL\"") < at("put_entry \"\$MNT/boot\" \"\$MNT/boot/KERNEL\" \"\$ENTRY\""))
        assertTrue(at("put_entry \"\$MNT/boot\"") < at("status verifying"))
        assertTrue(at("entry_matches \"\$MNT/boot\" \"\$MNT/boot/KERNEL\" ||") > at("status verifying"))
        // A kernel without entry pieces still installs where there's no entry (ENTRY empty).
        assertValidShell(RepairScript.kernelBody(job.copy(entry = null)))
        assertTrue("ENTRY=''" in RepairScript.kernelBody(job.copy(entry = null)))
        // With an undo: the update's marks still come first.
        val undo = RepairScript.kernelBody(job.copy(undoUpdate = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"))
        assertValidShell(undo)
        assertTrue(undo.indexOf("can't start from the boot menu's entry") < undo.indexOf("rollback-requested"))
    }

    @Test fun entryOnlyLeavesTheKernel() {
        val job = RepairScript.KernelJob("k", "/x/status", RepairScript.Place.Card, "/x/found/card/KERNEL", "a".repeat(64),
            "PARTUUID=8a1b2c3d-02", "a".repeat(64), entry = "/x/repair-kernel/entry", entryOnly = true)
        val body = RepairScript.kernelBody(job)
        assertValidShell(body)
        assertFalse("retarget_kernel \"\$NEW\"" in body)
        assertFalse("KERNEL.new" in body)
        assertFalse("md5sum KERNEL >" in body)
        assertTrue(body.indexOf("changed since the check") < body.indexOf("status writing"))
        assertTrue(body.indexOf("put_entry \"\$MNT/boot\" \"\$MNT/boot/KERNEL\" \"\$ENTRY\"") in body.indexOf("status writing") until body.indexOf("status verifying"))
        assertTrue(body.indexOf("entry_matches \"\$MNT/boot\"") > body.indexOf("status verifying"))
        // Only for the KERNEL in place, with its pieces, never with an undo.
        assertTrue(runCatching { RepairScript.kernelBody(job.copy(entry = null)) }.isFailure)
        assertTrue(runCatching { RepairScript.kernelBody(job.copy(currentSha256 = "b".repeat(64))) }.isFailure)
        assertTrue(runCatching { RepairScript.kernelBody(job.copy(undoUpdate = "1b4e28ba-2fa1-11d2-883f-0016d3cca427")) }.isFailure)
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
