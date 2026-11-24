package org.projectbarry.pbosinstaller.abl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.device.Soc
import java.io.File
import java.security.MessageDigest

class AblScriptTest {
    /** As the app is now, without OpenABL-PB and with a made-up one. */
    private val current = AblCatalog.current
    private val unfilled = AblCatalog(null)
    private val release = OpenAblRelease(
        label = "OpenABL-PB 1.2",
        files = Soc.entries.withIndex().associate { (i, soc) -> soc to OpenAblRelease.Binary(532480, "$i".repeat(64)) },
        firstDuoImage = "alpha-v0.9",
    )
    private val filled = AblCatalog(release)

    private val backup = AblScript.BackupJob(
        id = "b1", statusFile = "/data/media/0/x/abl-backup/status", backupDir = "/data/media/0/x/abl-backup/2026",
        journal = "/data/media/0/x/abl-flash/journal",
        platform = "pineapple", info = listOf("handheld=KONKR Pocket FIT", "model=Pocket FIT"),
        stock = unfilled.stock(Soc.SM8650), menus = unfilled.menus(Soc.SM8650),
    )
    private val install = AblScript.InstallJob(
        id = "i1", statusFile = "/data/media/0/x/abl-install/status", platform = "pineapple",
        payload = "/data/media/0/x/abl/abl_signed-SM8650.elf", label = AblKnown.FORK_12, payloadBytes = 532480,
        payloadSha256 = AblKnown.fork12.getValue(Soc.SM8650).sha256,
        backups = "/data/media/0/x/abl-backup", backupDir = "/data/media/0/x/abl-backup/2026",
        journal = "/data/media/0/x/abl-flash/journal",
        stock = unfilled.stock(Soc.SM8650), menus = unfilled.menus(Soc.SM8650),
    )
    private val restore = AblScript.AfterJob(
        id = "r1", statusFile = "/data/media/0/x/abl-restore/status", platform = "pineapple",
        backups = "/data/media/0/x/abl-backup", journal = "/data/media/0/x/abl-flash/journal",
        stock = unfilled.stock(Soc.SM8650), menus = unfilled.menus(Soc.SM8650), payload = "/data/media/0/x/abl/abl_signed-SM8650.elf",
    )

    private fun parses(body: String) {
        val f = File.createTempFile("pbos-abl", ".sh").apply { writeText(body) }
        val p = ProcessBuilder("/bin/sh", "-n", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun scriptsAreValidShell() {
        parses(AblScript.backupBody(backup))
        parses(AblScript.installBody(install))
        parses(AblScript.installBody(install.copy(backupDir = null)))
        parses(AblScript.restoreBody(restore))
        parses(AblScript.checkBody(restore.copy(payload = null)))
    }

    private val duoBackup = backup.copy(
        platform = "sun", stock = filled.stock(Soc.SM7750), menus = filled.menus(Soc.SM7750), socModel = "CQ7790S",
    )
    private val duoInstall = install.copy(
        platform = "sun", payload = "/data/media/0/x/abl/abl_signed-SM7750.elf",
        payloadSha256 = "a".repeat(64), socModel = "CQ7790S", careful = true, onlyOverKnown = true,
        stock = filled.stock(Soc.SM7750), menus = filled.menus(Soc.SM7750),
    )
    private val duoRestore = restore.copy(
        platform = "sun", socModel = "CQ7790S", careful = true, stock = filled.stock(Soc.SM7750), menus = filled.menus(Soc.SM7750),
    )

    @Test fun duoScriptsAreValidShell() {
        parses(AblScript.backupBody(duoBackup))
        parses(AblScript.installBody(duoInstall))
        parses(AblScript.restoreBody(duoRestore))
        parses(AblScript.checkBody(duoRestore))
    }

    /** sun is the 8 Elite's platform too, so the Duo's scripts check the model as well; the others don't change. */
    @Test fun duoScriptsCheckTheModel() {
        val check = "[ \"\$(getprop ro.soc.model)\" = 'CQ7790S' ] || fail"
        assertTrue(check in AblScript.backupBody(duoBackup))
        assertTrue(check in AblScript.installBody(duoInstall))
        assertTrue(check in AblScript.restoreBody(duoRestore) && check in AblScript.checkBody(duoRestore))
        assertFalse("ro.soc.model)\" =" in AblScript.backupBody(backup))
        assertFalse("ro.soc.model)\" =" in AblScript.installBody(install))
    }

    /** On the Duo, the slot in use is written last, and nothing is written without knowing which one that is. */
    @Test fun duoWritesTheInactiveSlotFirst() {
        val body = AblScript.installBody(duoInstall)
        fun at(s: String) = body.indexOf(s).also { assertTrue(s, it >= 0) }
        assertTrue(at("Couldn't tell which boot loader slot is in use") < at("status installing 0 2"))
        assertTrue(at("_a) FIRST=b SECOND=a") < at("_b) FIRST=a SECOND=b"))
        assertFalse("FIRST=b SECOND=a\n" in body)
        // The other chips: slot b, then slot a, as always.
        val other = AblScript.installBody(install)
        assertFalse("slot_suffix" in other)
        assertTrue("\nFIRST=b SECOND=a\n" in other)
    }

    /** On the Duo only known boot loaders are replaced, and not while an Android update waits; the other chips as before. */
    @Test fun duoReplacesOnlyKnownBootLoaders() {
        assertEquals(listOf(Soc.SM7750), Soc.entries.filter { current.strict(it) })
        val body = AblScript.installBody(duoInstall)
        fun at(s: String) = body.indexOf(s).also { assertTrue(s, it >= 0) }
        assertTrue(at("doesn't know, so it isn't replaced") < at("status installing 0 2"))
        assertTrue(at("doesn't know, so it isn't replaced") < at("mv -f \"\$JOURNAL.new\""))
        assertTrue("291afdcf6acc7da034881c30b9bef70c3cbc260c33457a8b873d07cf4c96b4bd" in body)
        assertTrue(release.files.getValue(Soc.SM7750).sha256 in body) // a Duo boot menu is known too
        assertTrue(at("bootctl get-active-boot-slot") < at("mv -f \"\$JOURNAL.new\""))
        assertTrue(at("UPDATED_NEED_REBOOT") < at("mv -f \"\$JOURNAL.new\""))
        assertTrue(at("Couldn't tell which slot the handheld starts from next") < at("mv -f \"\$JOURNAL.new\""))
        assertTrue(at("Connect the charger first") < at("mv -f \"\$JOURNAL.new\""))
        // Finishing an install that passed this isn't stopped by it: the slots hold half of it now.
        val finish = AblScript.installBody(duoInstall.copy(backupDir = null))
        parses(finish)
        assertFalse("doesn't know, so it isn't replaced" in finish)
        // But it needs the charger and no waiting update, and putting the original back no waiting update, too.
        assertTrue("Connect the charger first" in finish && "update_state\n" in finish)
        assertTrue("update_state\n" in AblScript.restoreBody(duoRestore))
        assertFalse("update_state" in AblScript.checkBody(duoRestore))
        val other = AblScript.installBody(install)
        assertFalse("isn't replaced" in other || "bootctl" in other || "update_engine" in other || "charger" in other)
        assertFalse("bootctl" in AblScript.restoreBody(restore))
    }

    /** identify on a Duo names only the Duo's: another handheld's stock boot loader is unknown there. */
    @Test fun unknownOnTheDuoIsUnknown() {
        val f = File.createTempFile("abl", ".img").apply { writeBytes(ByteArray(1048576) { 7 }) }
        val known = AblScript.Known("X", MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }, null)
        fun identify(stock: List<AblScript.Known>): String {
            val p = ProcessBuilder(
                "/bin/sh", "-c",
                "sha() { if command -v sha256sum >/dev/null; then sha256sum \"\$@\"; else shasum -a 256 \"\$@\"; fi | cut -d' ' -f1; }\n" +
                    AblScript.identifyFunctions(stock, filled.menus(Soc.SM7750)) + "\nidentify '${f.path}'",
            ).redirectErrorStream(true).start()
            return p.inputStream.bufferedReader().readText().trim().also { assertEquals(0, p.waitFor()) }
        }
        assertEquals("stock: X", identify(filled.stock(Soc.SM7750) + known))
        assertEquals("unknown", identify(filled.stock(Soc.SM7750)))
    }

    /** The Duo's two slots hold different stock boot loaders; both are known. */
    @Test fun duoStockIsKnown() {
        val body = AblScript.backupBody(duoBackup)
        assertTrue("'f96c4bdb2c1f554cc2a29fbd369406240bf5c34a92bc97b223936ad83061d4b7') echo 'stock: Retroid Pocket Duo" in body)
        assertTrue("'291afdcf6acc7da034881c30b9bef70c3cbc260c33457a8b873d07cf4c96b4bd') echo 'stock: Retroid Pocket Duo" in body)
        // By chip: another handheld's boot loader is nothing the app knows on a Duo, and the other way round.
        assertFalse("Nova" in body || "Thor" in body)
        assertFalse("Duo" in AblScript.backupBody(backup))
        assertEquals(Soc.entries.toSet(), AblKnown.stock.keys)
        val all = AblKnown.stock.values.flatten().distinct()
        assertEquals(5, all.size)
        assertEquals(all.size, all.map { it.sha256 }.toSet().size)
        assertTrue(all.all { Regex("[0-9a-f]{64}").matches(it.sha256) })
    }

    @Test fun duoNeedsItsCharger() {
        assertNull(PowerCheck.problem(plugged = true, percent = 30))
        assertNull(PowerCheck.problem(plugged = true, percent = 100))
        assertTrue("charger" in PowerCheck.problem(plugged = false, percent = 100)!!)
        assertTrue("30 %" in PowerCheck.problem(plugged = true, percent = 29)!!)
        assertNotNull(PowerCheck.problem(plugged = true, percent = null))
        assertNotNull(PowerCheck.problem(plugged = false, percent = null))
    }

    /** Without OpenABL-PB: the fork build on the SM8550 and SM8650 as before, and a Duo is refused. */
    @Test fun unfilledIsAsBeforeAndRefusesADuo() {
        for (soc in listOf(Soc.SM8550, Soc.SM8650)) {
            assertEquals(AblKnown.fork12[soc], unfilled.payload(soc))
            assertEquals("OpenABL 1.2 (PB-OS fork)", unfilled.bundled(soc))
            assertEquals(AblKnown.menus[soc], unfilled.menus(soc))
            for (listed in listOf(true, false)) assertNull(unfilled.refusal(soc, listed))
            assertNull(unfilled.notReady(soc))
            assertTrue(unfilled.startsOn(soc, "alpha-v0.5.2"))
        }
        assertNull(unfilled.payload(Soc.SM7750))
        assertNull(unfilled.bundled(Soc.SM7750))
        assertTrue(unfilled.menus(Soc.SM7750).isEmpty())
        assertNotNull(unfilled.refusal(Soc.SM7750, true))
        assertNotNull(unfilled.refusal(Soc.SM7750, false))
        assertNotNull(unfilled.notReady(Soc.SM7750))
        assertFalse(unfilled.startsOn(Soc.SM7750, "alpha-v9.9"))
        assertEquals("Snapdragon 8 Gen 2 (SM8550) or Snapdragon 8 Gen 3 (SM8650)", unfilled.chips)
        assertNull(unfilled.notReady(null))
        assertTrue(unfilled.startsOn(null, "x"))
    }

    /** With it: one version for every chip, each with its own file, and what was installed before stays known. */
    @Test fun filledIsOneVersionForEveryChip() {
        assertEquals("abl/openabl-pb-1.2", release.folder)
        for (soc in Soc.entries) {
            val p = filled.payload(soc)!!
            assertEquals("OpenABL-PB 1.2", p.label)
            assertEquals("abl/openabl-pb-1.2/abl_signed-${soc.name}.elf", p.asset)
            assertEquals(release.files.getValue(soc).sha256, p.sha256)
            assertEquals(AblScript.Known("OpenABL-PB 1.2", p.sha256, p.bytes), filled.menus(soc).first())
            assertTrue(filled.menus(soc).containsAll(AblKnown.menus.getValue(soc)))
            // No other chip's file is ever known or offered on this one.
            val others = release.files.filterKeys { it != soc }.values.map { it.sha256 }
            assertTrue(filled.menus(soc).none { it.sha256 in others })
            assertNull(filled.notReady(soc))
            assertNull(filled.refusal(soc, listed = true))
        }
        // Not a Duo: never the Duo's boot menu. The other chips as before, listed or not.
        assertTrue("only for the Retroid Pocket Duo" in filled.refusal(Soc.SM7750, listed = false)!!)
        for (soc in listOf(Soc.SM8550, Soc.SM8650)) assertNull(filled.refusal(soc, listed = false))
        assertEquals("Snapdragon 8 Gen 2 (SM8550), Snapdragon 8 Gen 3 (SM8650) or Snapdragon 7 Gen 4 (SM7750)", filled.chips)
        // The same OpenABL number under its other names isn't taken for it.
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.2 (PB-OS fork)", "menu: OpenABL 1.2 (PB-OS fork)", "OpenABL-PB 1.2"))
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.2", "menu: OpenABL 1.2", "OpenABL-PB 1.2"))
        assertTrue(AblKnown.alreadyInstalled("menu: OpenABL-PB 1.2", "menu: OpenABL-PB 1.2", "OpenABL-PB 1.2"))
        assertEquals("OpenABL 1.2 (PB-OS fork) boot menu (older version)", AblKnown.menuLabel("OpenABL 1.2 (PB-OS fork)", "OpenABL-PB 1.2"))
        assertEquals("OpenABL-PB 1.2 boot menu", AblKnown.menuLabel("OpenABL-PB 1.2", "OpenABL-PB 1.2"))
    }

    /** Half-filled or mixed-up values don't make a release at all. */
    @Test fun noHalfFilledRelease() {
        fun refused(make: () -> OpenAblRelease) = assertTrue(runCatching { make() }.exceptionOrNull() is IllegalArgumentException)
        for (label in listOf("OpenABL 1.2", AblKnown.FORK_12, "OpenABL-PB", "OpenABL-PB 1", "OpenABL-PB 1.2 ", "")) refused { release.copy(label = label) }
        refused { release.copy(files = release.files - Soc.SM7750) }
        refused { release.copy(files = emptyMap()) }
        refused { release.copy(files = release.files + (Soc.SM7750 to release.files.getValue(Soc.SM8550))) } // another chip's file
        for (b in listOf(OpenAblRelease.Binary(532480, "<sha256>"), OpenAblRelease.Binary(0, "9".repeat(64)), OpenAblRelease.Binary(1048577, "9".repeat(64)))) {
            refused { release.copy(files = release.files + (Soc.SM7750 to b)) }
        }
        for (tag in listOf("", "<tag>", "v0.9", "alpha-v0.5.4", "alpha-v0.5.3.9")) refused { release.copy(firstDuoImage = tag) }
    }

    /** The Duo is only offered releases with Duo support; nothing changes for the others. */
    @Test fun duoReleases() {
        assertTrue(filled.startsOn(Soc.SM7750, "alpha-v0.9"))
        assertTrue(filled.startsOn(Soc.SM7750, "alpha-v0.9.0"))
        assertTrue(filled.startsOn(Soc.SM7750, "alpha-v0.10"))
        assertFalse(filled.startsOn(Soc.SM7750, "alpha-v0.8.10"))
        // Published before Duo support: never for a Duo, whatever is filled in.
        assertFalse(current.startsOn(Soc.SM7750, "alpha-v0.5.4"))
        // A tag the app can't read, or of another series, is never new enough.
        for (odd in listOf("", "test", "v1.0", "beta-v1.0", "alpha-v", "alpha-v1.0-rc1", "alpha-v1.x", "alpha-v99999999999", "Alpha-v1.0", "alpha-v1.0 ", "alpha-v1..0")) {
            assertFalse(odd, filled.startsOn(Soc.SM7750, odd))
            for (soc in listOf(Soc.SM8550, Soc.SM8650, null)) assertTrue(filled.startsOn(soc, odd))
        }
        assertFalse(org.projectbarry.pbosinstaller.release.ReleaseTag.atLeast("alpha-v0.9", "alpha-v0.9.1"))
        assertTrue(org.projectbarry.pbosinstaller.release.ReleaseTag.atLeast("alpha-v0.9", "alpha-v0.9.0"))
    }

    /**
     * The files in the app and what the app knows about them are the same set: no file
     * without its size and checksum, no value without its file, no chip with another's.
     */
    @Test fun everyBootMenuFileIsAccountedFor() {
        val assets = File("src/main/assets")
        val files = File(assets, "abl").walk().filter { it.isFile }.map { it.relativeTo(assets).invariantSeparatorsPath }.toSet()
        val payloads = Soc.entries.mapNotNull { soc -> current.payload(soc)?.let { soc to it } }
        assertEquals(files, payloads.map { (_, p) -> p.asset }.toSet())
        for ((soc, p) in payloads) {
            val bytes = File(assets, p.asset).readBytes()
            assertEquals(p.asset, p.bytes, bytes.size.toLong())
            assertEquals(p.asset, p.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertEquals("${p.asset} is no ELF file", listOf(0x7f, 'E'.code, 'L'.code, 'F'.code), bytes.take(4).map { it.toInt() })
            assertEquals(p.asset, "abl_signed-${soc.name}.elf", p.asset.substringAfterLast('/'))
            assertTrue(p.asset, p.bytes <= 1048576)
            assertTrue(soc.name, current.menus(soc).any { it.label == p.label && it.sha256 == p.sha256 && it.bytes == p.bytes })
        }
    }

    /** The sizes and checksums once more, from the machine that built the files (abl-payloads.pin), not from the files. */
    @Test fun payloadsMatchThePins() {
        fun lines(f: File) = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val pins = lines(File("../abl-payloads.pin"))
        val values = Soc.entries.map { soc ->
            OpenAblRelease.CURRENT?.files?.getValue(soc)?.let { "${soc.name} ${it.bytes} ${it.sha256}" } ?: "${soc.name} UNFILLED UNFILLED"
        }
        assertEquals(values, pins)
        // pb-os's own list, when PBOS_PAYLOADS_PIN points at it.
        System.getenv("PBOS_PAYLOADS_PIN")?.let { assertEquals(pins, lines(File(it))) }
    }

    @Test fun backupOnlyReadsTheBootLoader() {
        val body = AblScript.backupBody(backup)
        assertTrue("getprop ro.board.platform)\" = 'pineapple'" in body)
        assertTrue("if=\"\$P\" of=\"\$F\"" in body) // partition -> file only
        assertFalse("of=/dev/block" in body)
        assertTrue("'739202be4e3f781d0a5e0321c5923f05b66da694f59ae90745e0054b2cb02537') echo 'stock: KONKR Pocket FIT" in body)
        assertTrue("head -c 532480" in body) // OpenABL
        assertTrue("head -c 258048" in body) // ROCKNIX
    }

    @Test fun installChecksEverything() {
        val body = AblScript.installBody(install)
        listOf(
            "[ \"\$(id -u)\" = 0 ]",
            "getprop ro.board.platform)\" = 'pineapple'",
            "The boot menu file is damaged",
            "The boot loader changed since the backup",
            "partition is too small",
            "FIRST=b SECOND=a", // slot b first
            "The original was put back and read back",
        ).forEach { assertTrue(it, it in body) }
        assertTrue(body.indexOf("put \"\$WORK/payload\" \$FIRST") in 0 until body.indexOf("put \"\$WORK/payload\" \$SECOND"))
    }

    /** Every write and every restore is read back from the storage, and nothing is claimed that wasn't read back. */
    @Test fun writesAndRestoresAreReadBack() {
        for (body in listOf(AblScript.installBody(install), AblScript.installBody(install.copy(backupDir = null)), AblScript.restoreBody(restore))) {
            // The only write goes through put, which flushes and empties the cache after it.
            assertEquals(1, Regex("of=\"\\$\\(part ").findAll(body).count())
            assertTrue("conv=fsync 2>/dev/null; reread " in body)
            assertTrue("blockdev --flushbufs" in body && "echo 3 > /proc/sys/vm/drop_caches" in body)
            // "put back" only after put_back compared the slot with the checksum from before the install.
            assertTrue("for s in \"\$@\"; do put_back \"\$s\" || { LEFT=\$s; return 1; }; done" in body)
            assertTrue("Don't turn your handheld off and don't restart it" in body)
        }
        assertFalse("of=\"\$(part " in AblScript.checkBody(restore).replace(Regex("put\\(\\) .*"), ""))
        assertFalse("restore_all " in AblScript.checkBody(restore).substringAfter("status checking"))
        val body = AblScript.installBody(install)
        assertTrue(body.indexOf("if restore_all") in 0 until body.indexOf("The original was put back and read back"))
        // The note of the install goes only where both slots were read back.
        assertEquals(2, Regex("^ *finished$", RegexOption.MULTILINE).findAll(body).count())
        // The file is the size and checksum the app knows, before anything else.
        assertTrue(body.indexOf("wc -c < \"\$PAYLOAD\"") in 0 until body.indexOf("status installing 0 2"))
        assertTrue(body.indexOf("{ finished; fail \"Couldn't note the install") in 0 until body.indexOf("status installing 0 2"))
        assertTrue("reread \"\$P\"" in AblScript.backupBody(backup))
    }

    /** The note is on the storage before the first write; while it's there, no backup is made and no new install starts. */
    @Test fun anInterruptedInstallKeepsItsOriginal() {
        val body = AblScript.installBody(install)
        fun at(s: String) = body.indexOf(s).also { assertTrue(s, it >= 0) }
        assertTrue(at("mv -f \"\$JOURNAL.new\" \"\$JOURNAL\" && sync") < at("status installing 0 2"))
        assertTrue(at("The last boot menu install didn't finish") < at("mv -f \"\$JOURNAL.new\""))
        val b = AblScript.backupBody(backup)
        assertTrue(b.indexOf("unfinished && {") in 0 until b.indexOf("status backing-up"))
        // Finishing it takes the original from the note, never from what the slots hold now.
        val finish = AblScript.installBody(install.copy(backupDir = null))
        assertTrue("read_note \"\$JOURNAL\"" in finish && "read_note \"\$WORK/journal\"" in finish)
        assertFalse("printf 'dir=%s" in finish)
        assertFalse("The boot loader changed since the backup" in finish)
        assertTrue("read_note \"\$JOURNAL\"" in AblScript.restoreBody(restore))
    }

    /** Two jobs never write at the same time, and starting one again does nothing. */
    @Test fun onlyOneAtATime() {
        for (body in listOf(AblScript.backupBody(backup), AblScript.installBody(install), AblScript.restoreBody(restore), AblScript.checkBody(restore))) {
            assertTrue("flock -n 8 8>&9 || busy" in body && "Another boot loader step is still running" in body)
            assertTrue(body.indexOf("flock -n 8 8>&9 || busy") in 0 until body.indexOf("status started"))
            assertTrue(Regex("RUN_COPY=/data/local/tmp/pbos-abl-[a-z]+-[a-z0-9]+\\.\\$\\$\\.sh").containsMatchIn(body))
            assertTrue("rm -f \"\$0\"; nohup sh" in body) // the saved script goes once it was started
            assertTrue("trap bye EXIT" in body)
            // Without flock: the lock appears with its owner in it.
            assertTrue("until ln \"\$LOCK.me.\$\$\" \"\$LOCK.held\"" in body)
        }
    }

    /** Earlier installs stay recognized: ROCKNIX 1.2 and 1.1.8 on both chips. */
    @Test fun rocknixStillKnown() {
        listOf(Soc.SM8550, Soc.SM8650).forEach { soc ->
            val labels = AblKnown.menus.getValue(soc).map { it.label }
            assertTrue(soc.name, "ROCKNIX 1.2" in labels && "ROCKNIX 1.1.8" in labels)
        }
        assertEquals("ROCKNIX 1.2", AblKnown.menuName("menu: ROCKNIX 1.2"))
        assertEquals("ROCKNIX 1.2", AblKnown.menuName("rocknix: ROCKNIX 1.2")) // app 0.3 status
        assertEquals(null, AblKnown.menuName("stock: AYN Thor (firmware 2026-02-06)"))
    }

    /** OpenABL 1.1 (app 0.4.1) stays recognized on both chips and is called older. */
    @Test fun openAbl11StillKnown() {
        mapOf(
            Soc.SM8550 to "cd97748bbd1c5dc994b8a73a05e88955297f4c88384e3ca4028d043fc1b16848",
            Soc.SM8650 to "8fd6a18ec30a8c24751b7db73a6ecb0d9df9f2e1432973273d572841ff3f6216",
        ).forEach { (soc, sha) ->
            assertTrue(soc.name, AblKnown.menus.getValue(soc).any { it.label == "OpenABL 1.1" && it.sha256 == sha && it.bytes == 532480L })
        }
        assertEquals("OpenABL 1.1 boot menu (older version)", AblKnown.menuLabel("OpenABL 1.1", AblKnown.FORK_12))
        assertEquals("OpenABL 1.2 (PB-OS fork) boot menu", AblKnown.menuLabel(AblKnown.FORK_12, AblKnown.FORK_12))
        assertEquals("ROCKNIX 1.2 boot menu", AblKnown.menuLabel("ROCKNIX 1.2", AblKnown.FORK_12))
        assertEquals("OpenABL 1.1 boot menu", AblKnown.menuLabel("OpenABL 1.1", null))
    }

    @Test fun alreadyInstalledOnlyWithBothSlotsOnTheBundledVersion() {
        assertTrue(AblKnown.alreadyInstalled("menu: OpenABL 1.2 (PB-OS fork)", "menu: OpenABL 1.2 (PB-OS fork)", AblKnown.FORK_12))
        // OpenABL 1.1 (what app 0.4.1 installed) gets the bundled one offered.
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.1", "menu: OpenABL 1.1", AblKnown.FORK_12))
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.2 (PB-OS fork)", "menu: OpenABL 1.1", AblKnown.FORK_12))
        // ROCKNIX 1.2 (what app 0.3 installed) gets OpenABL offered.
        assertFalse(AblKnown.alreadyInstalled("menu: ROCKNIX 1.2", "menu: ROCKNIX 1.2", AblKnown.FORK_12))
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.2 (PB-OS fork)", "menu: OpenABL 1.0", AblKnown.FORK_12))
        assertFalse(AblKnown.alreadyInstalled("stock: KONKR Pocket FIT (firmware 2025-12-26)", "menu: ROCKNIX 1.1.8", AblKnown.FORK_12))
        // No boot menu for this handheld: never "installed", and its own label is the only one that counts.
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.2 (PB-OS fork)", "menu: OpenABL 1.2 (PB-OS fork)", null))
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.2 (PB-OS fork)", "menu: OpenABL 1.2 (PB-OS fork)", "OpenABL Duo"))
        assertTrue(AblKnown.alreadyInstalled("menu: OpenABL Duo", "menu: OpenABL Duo", "OpenABL Duo"))
        // The label the script prints must be what alreadyInstalled looks for.
        assertTrue("echo 'menu: OpenABL 1.2 (PB-OS fork)'" in AblScript.backupBody(backup))
    }

    /** identify really names each known menu, run against files on this computer. */
    @Test fun identifyNamesTheMenus() {
        val dir = java.nio.file.Files.createTempDirectory("pbos-abl-id").toFile()
        fun ablFile(name: String, head: ByteArray): File = File(dir, name).apply {
            // A 1 MiB partition: the menu at the start, other bytes after it.
            writeBytes(head + ByteArray(1048576 - head.size) { 0x5a })
        }
        val openabl = File("src/main/assets/${current.payload(Soc.SM8650)!!.asset}").readBytes()
        val files = listOf(ablFile("openabl", openabl), ablFile("other", ByteArray(1000) { 1 }))
        val script = File(dir, "id.sh").apply {
            writeText(
                "sha() { if command -v sha256sum >/dev/null; then sha256sum \"\$@\"; else shasum -a 256 \"\$@\"; fi | cut -d' ' -f1; }\n" +
                    AblScript.identifyFunctions(current.stock(Soc.SM8650), current.menus(Soc.SM8650)) + "\n" +
                    "for f in \"\$@\"; do identify \"\$f\"; done\n"
            )
        }
        val p = ProcessBuilder(listOf("/bin/sh", script.path) + files.map { it.path }).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        assertEquals(0, p.waitFor())
        assertEquals(listOf("menu: " + current.bundled(Soc.SM8650), "unknown"), out)
        dir.deleteRecursively()
    }
}
