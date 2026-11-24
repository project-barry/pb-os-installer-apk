package org.projectbarry.pbosinstaller.abl

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** The boot loader scripts, run on this computer against files in place of the partitions. */
class AblRunTest {
    private lateinit var dir: File
    private val size = 1 shl 18
    private fun bytes(seed: Int) = ByteArray(size).also { java.util.Random(seed.toLong()).nextBytes(it) }
    private val origA = bytes(1)
    private val origB = bytes(2)
    private val menu = bytes(3).copyOf(33 * 4096)
    private fun withMenu(orig: ByteArray) = menu + orig.copyOfRange(menu.size, size)
    /** The boot menu's first [blocks] blocks over [orig]: a write that was cut off. */
    private fun half(orig: ByteArray, blocks: Int = 10) = menu.copyOf(blocks * 4096) + orig.copyOfRange(blocks * 4096, size)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        dir = Files.createTempDirectory("abl-run").toFile()
        listOf("bin", "dev", "tmp", "proc", "st", "backup/d1", "flash").forEach { File(dir, it).mkdirs() }
        slot('a').writeBytes(origA)
        slot('b').writeBytes(origB)
        File(dir, "backup/d1/abl_a.img").writeBytes(origA)
        File(dir, "backup/d1/abl_b.img").writeBytes(origB)
        File(dir, "menu.elf").writeBytes(menu)
        File(dir, "proc/boot_id").writeText("boot-1\n")
        File(dir, "proc/uuid").writeText("11111111-2222\n")
        tool("id", "echo 0")
        tool("getprop", "case \$1 in ro.board.platform) echo kalama;; ro.soc.model) echo QCS8550;; ro.product.device) cat '$dir/device' 2>/dev/null || echo kalama;; ro.boot.slot_suffix) cat '$dir/suffix' 2>/dev/null;; *) echo Model;; esac")
        tool("blockdev", "[ \"\$1\" = --getsize64 ] && wc -c < \"\$2\" | tr -d ' '; exit 0")
        tool("stat", "case \"\$2\" in %u) [ -e \"\$3\" ] && cat '$dir/uid' 2>/dev/null || echo 0;; *) echo 0:0;; esac")
        tool("chown", "exit 0")
        tool("sync", "exit 0")
        tool("sha256sum", "if command -v /usr/bin/sha256sum >/dev/null; then exec /usr/bin/sha256sum \"\$@\"; else exec shasum -a 256 \"\$@\"; fi")
        // Partitions aren't cut short by a write; one that says so fails half way.
        tool(
            "dd",
            "of=; for x; do case \"\$x\" in of=*) of=\${x#of=};; if=*) f=\${x#if=};; esac; done\n" +
                "case \"\$of\" in\n" +
                "  */dev/abl_*) [ -e '$dir/fail' ] && { case \"\$f\" in *.img) exit 1;; esac; head -c 50000 \"\$f\" | /bin/dd of=\"\$of\" conv=notrunc 2>/dev/null; exit 1; }\n" +
                "    echo \"\${of##*/}\" >> '$dir/writes'; exec /bin/dd if=\"\$f\" of=\"\$of\" bs=1048576 conv=notrunc;;\n" +
                "  *) exec /bin/dd \"\$@\";;\nesac",
        )
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun slot(s: Char) = File(dir, "dev/abl_$s")
    private fun tool(name: String, body: String) = File(dir, "bin/$name").run { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
    private val note get() = File(dir, "flash/journal")
    private val rootNote get() = File(dir, "tmp/pbos-abl-work/journal")
    private val writes get() = File(dir, "writes").takeIf { it.exists() }?.readLines().orEmpty()

    private val stock = listOf(AblScript.Known("A", sha(origA), null), AblScript.Known("B", sha(origB), null))
    private val menus get() = listOf(AblScript.Known("Menu", sha(menu), menu.size.toLong()))
    private fun install(backup: String?) = AblScript.installBody(
        AblScript.InstallJob(
            "i1", "$dir/st/status", "kalama", "$dir/menu.elf", "Menu", menu.size.toLong(), sha(menu),
            "$dir/backup", backup?.let { "$dir/backup/$it" }, "$dir/flash/journal", emptyList(), menus,
        )
    )
    private val fresh get() = install("d1")
    private val finish get() = install(null)
    private fun after(known: List<AblScript.Known> = emptyList()) = AblScript.AfterJob(
        "r1", "$dir/st/status", "kalama", "$dir/backup", "$dir/flash/journal", known, menus, "$dir/menu.elf",
    )
    private val restore get() = AblScript.restoreBody(after())
    private val check get() = AblScript.checkBody(after())

    /** Runs [body] as its detached copy; the status it ends with. */
    private fun run(body: String): Map<String, String> {
        val f = File(dir, "tmp/pbos-" + Regex("pbos-(abl-[a-z]+-[a-z0-9]+)\\.").find(body)!!.groupValues[1] + ".1.sh")
        f.writeText(
            body.replace("/dev/block/by-name", "$dir/dev").replace("/data/local/tmp", "$dir/tmp")
                .replace("/proc/sys/kernel/random", "$dir/proc").replace("/proc/sys/vm", "$dir/proc").replace("/sys/power", "$dir/proc")
        )
        File(dir, "writes").delete()
        val b = ProcessBuilder("/bin/sh", f.path).redirectErrorStream(true)
        b.environment()["PATH"] = "$dir/bin:" + System.getenv("PATH")
        val p = b.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val status = File(dir, "st/status")
        assertTrue(out, status.exists())
        return status.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /** An install that was cut off: the note is there, the slots hold [a] and [b]. */
    private fun interrupted(a: ByteArray, b: ByteArray) {
        File(dir, "fail").writeText("")
        // Every write fails, the restores too, so the note stays.
        val r = run(fresh)
        assertEquals("failed", r["restore"])
        assertTrue(note.exists() && rootNote.exists())
        File(dir, "fail").delete()
        slot('a').writeBytes(a)
        slot('b').writeBytes(b)
    }

    private fun slots(a: ByteArray, b: ByteArray) {
        assertArrayEquals(a, slot('a').readBytes())
        assertArrayEquals(b, slot('b').readBytes())
    }

    @Test fun installsAndForgetsTheNote() {
        assertEquals("done", run(fresh)["state"])
        slots(withMenu(origA), withMenu(origB))
        assertEquals(listOf("abl_b", "abl_a"), writes)
        assertFalse(note.exists() || rootNote.exists())
    }

    @Test fun halfWrittenGoesBackOrOn() {
        interrupted(origA, half(origB))
        assertEquals("half", run(check)["result"])
        assertTrue(note.exists() && writes.isEmpty())
        assertEquals("original", run(restore)["result"])
        slots(origA, origB)
        assertEquals(listOf("abl_b"), writes) // the whole one isn't touched
        assertFalse(note.exists() || rootNote.exists())
        interrupted(origA, half(origB))
        assertEquals("done", run(finish)["state"])
        slots(withMenu(origA), withMenu(origB))
        interruptedAgainIsRefused()
    }

    private fun interruptedAgainIsRefused() {
        assertEquals("0", run(restore)["unfinished"])
        assertTrue(writes.isEmpty())
    }

    /** Whole slots need nothing: only the note goes, whoever looks. */
    @Test fun wholeSlotsOnlyLoseTheNote() {
        for (job in listOf(check, restore, finish)) {
            interrupted(origA, origB)
            assertEquals("original", run(job)["result"])
            assertTrue(writes.isEmpty() && !note.exists() && !rootNote.exists())
            slots(origA, origB)
            interrupted(withMenu(origA), withMenu(origB))
            assertEquals("menu", run(job)["result"])
            assertTrue(writes.isEmpty() && !note.exists() && !rootNote.exists())
            slots(withMenu(origA), withMenu(origB))
            slot('a').writeBytes(origA)
            slot('b').writeBytes(origB)
        }
    }

    @Test fun oneOfEachIsMixed() {
        interrupted(origA, withMenu(origB))
        assertEquals("mixed", run(check)["result"])
        assertEquals("done", run(finish)["state"])
        slots(withMenu(origA), withMenu(origB))
    }

    /** Anything that isn't the original's or the boot menu's is never written over. */
    @Test fun somethingElseIsLeftAlone() {
        val update = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + bytes(9).copyOfRange(4, size)
        val flipped = half(origB).also { it[0] = (it[0] + 1).toByte() }
        val foreignTail = withMenu(origB).also { it[size - 1] = (it[size - 1] + 1).toByte() }
        for ((b, result) in listOf(update to "newer", flipped to "other", ByteArray(size) to "other", foreignTail to null)) {
            for (job in listOf(restore, finish)) {
                interrupted(half(origA), b)
                val r = run(job)
                assertEquals("failed", r["state"])
                assertTrue(r["message"]!!, "nothing is written over it" in r["message"]!!)
                assertTrue(writes.isEmpty())
                slots(half(origA), b)
                assertTrue(note.exists())
                slot('a').writeBytes(origA)
                slot('b').writeBytes(origB)
                note.delete()
                rootNote.delete()
            }
            if (result == "newer") {
                // Only updated slots and a start since: the note goes. Not before that start.
                interrupted(origA, b)
                assertEquals("newer", run(check)["result"])
                assertTrue(note.exists())
                File(dir, "proc/boot_id").writeText("boot-2\n")
                // Started from the other slot: nothing shows that the update is whole.
                File(dir, "suffix").writeText("_a\n")
                assertEquals("newer", run(check)["result"])
                File(dir, "suffix").delete()
                assertEquals("newer", run(check)["result"])
                assertTrue(note.exists() && rootNote.exists())
                File(dir, "suffix").writeText("_b\n")
                assertEquals("changed", run(check)["result"])
                File(dir, "suffix").delete()
                assertFalse(note.exists() || rootNote.exists())
                assertTrue(writes.isEmpty())
                File(dir, "proc/boot_id").writeText("boot-1\n")
                slot('b').writeBytes(origB)
            }
        }
        // A boot loader the app knows is whole, but not the install's: the note goes, nothing is written.
        interrupted(origA, update)
        assertEquals("changed", run(AblScript.restoreBody(after(listOf(AblScript.Known("U", sha(update), null)))))["result"])
        slots(origA, update)
        assertTrue(writes.isEmpty())
    }

    /** Another handheld's note never writes here: the app's, with another number. */
    @Test fun anotherHandheldsNote() {
        interrupted(origA, half(origB))
        val text = note.readText()
        File(dir, "tmp/pbos-abl-work").deleteRecursively()
        File(dir, "tmp/pbos-abl-id").writeText("99999999-8888\n")
        for (job in listOf(check, restore, finish)) {
            note.writeText(text)
            assertEquals("foreign", run(job)["result"])
            assertTrue(writes.isEmpty() && !note.exists())
            slots(origA, half(origB))
        }
    }

    /** Its own note stays its own: names change with updates, and root's note was made here. */
    @Test fun itsOwnNoteIsNeverForeign() {
        interrupted(origA, half(origB))
        File(dir, "device").writeText("renamed\n")
        assertEquals("half", run(check)["result"])
        assertTrue(note.exists() && rootNote.exists())
        // Root's is enough, whatever number there is now.
        File(dir, "tmp/pbos-abl-id").writeText("99999999-8888\n")
        assertEquals("half", run(check)["result"])
        // Only the app's, with the same number; or with the number gone (the slots are checked block by block).
        File(dir, "tmp/pbos-abl-id").writeText("11111111-2222\n")
        File(dir, "tmp/pbos-abl-work").deleteRecursively()
        assertEquals("half", run(check)["result"])
        File(dir, "tmp/pbos-abl-id").delete()
        assertEquals("original", run(restore)["result"])
        slots(origA, origB)
    }

    @Test fun aDamagedNoteHasAWayOut() {
        for (bad in listOf("", "dir=/etc\n", "junk")) {
            // Whole slots (a backup of each is there): the note goes.
            interrupted(origA, origB)
            note.writeText(bad)
            rootNote.writeText(bad)
            assertEquals("damaged", run(check)["result"])
            assertTrue(writes.isEmpty() && !note.exists() && !rootNote.exists())
            // A half-written one: it stays, with nothing written.
            interrupted(origA, half(origB))
            note.writeText(bad)
            rootNote.writeText(bad)
            for (job in listOf(check, restore, finish)) {
                val r = run(job)
                assertEquals("other", r["result"])
                assertTrue("don't restart" in r["message"]!!)
                assertTrue(writes.isEmpty() && note.exists())
            }
            note.delete()
            rootNote.delete()
            slot('b').writeBytes(origB)
            // Root's is enough.
            interrupted(origA, half(origB))
            note.writeText(bad)
            assertEquals("original", run(restore)["result"])
            slots(origA, origB)
        }
    }

    /** Finishing writes over a slot that's still the original only with a copy of it. */
    @Test fun finishKeepsTheOriginalSafe() {
        interrupted(origA, withMenu(origB))
        File(dir, "backup/d1").deleteRecursively()
        File(dir, "tmp/pbos-abl-work/abl_a.img").delete()
        File(dir, "tmp/pbos-abl-work/abl_b.img").delete()
        // Its own slot still has it, so it's copied first; a write that fails goes back from there.
        tool(
            "dd",
            "of=; for x; do case \"\$x\" in of=*) of=\${x#of=};; if=*) f=\${x#if=};; esac; done\n" +
                "case \"\$of\" in */dev/abl_a) case \"\$f\" in */payload) head -c 9999 \"\$f\" | /bin/dd of=\"\$of\" conv=notrunc 2>/dev/null; exit 1;; esac;; esac\n" +
                "case \"\$of\" in */dev/abl_*) exec /bin/dd if=\"\$f\" of=\"\$of\" bs=1048576 conv=notrunc;; *) exec /bin/dd \"\$@\";; esac",
        )
        assertEquals("mixed", run(finish)["result"]) // the other slot's boot menu is whole, and nothing is claimed to be broken
        slots(origA, withMenu(origB))
        assertTrue(note.exists())
        // Half written with no copy left: nothing says what it was.
        slot('a').writeBytes(half(origA))
        File(dir, "tmp/pbos-abl-work/abl_a.img").delete()
        val r = run(finish)
        assertEquals("other", r["result"])
        assertArrayEquals(half(origA), slot('a').readBytes())
    }

    @Test fun wrongFileIsRefusedBeforeAnything() {
        File(dir, "menu.elf").writeBytes(menu + 0)
        assertEquals("failed", run(fresh)["state"])
        assertTrue(writes.isEmpty() && !note.exists())
        slots(origA, origB)
    }

    /** No backup while an install is unfinished, with either note. */
    @Test fun noBackupOfHalfWrittenSlots() {
        interrupted(origA, half(origB))
        val backup = AblScript.backupBody(AblScript.BackupJob("b1", "$dir/st/status", "$dir/backup/d2", "$dir/flash/journal", "kalama", emptyList(), stock, menus))
        assertEquals("1", run(backup)["unfinished"])
        note.delete()
        assertEquals("1", run(backup)["unfinished"])
        assertFalse(File(dir, "backup/d2/abl_a.img").exists())
        // Someone else's folder in root's place isn't root's note.
        File(dir, "uid").writeText("2000\n")
        assertEquals("done", run(backup)["state"])
    }

    /** A slot whose write or restore keeps failing; everything else works. */
    private fun broken(slot: Char) = tool(
        "dd",
        "of=; for x; do case \"\$x\" in of=*) of=\${x#of=};; if=*) f=\${x#if=};; esac; done\n" +
            "case \"\$of\" in */dev/abl_*) echo \"\${of##*/}\" >> '$dir/writes';; esac\n" +
            "case \"\$of\" in */dev/abl_$slot) case \"\$f\" in *.img) exit 1;; esac; head -c 49152 \"\$f\" | /bin/dd of=\"\$of\" conv=notrunc 2>/dev/null; exit 1;;\n" +
            "  */dev/abl_*) exec /bin/dd if=\"\$f\" of=\"\$of\" bs=1048576 conv=notrunc;; *) exec /bin/dd \"\$@\";; esac",
    )

    /** With one slot that can't be fixed, the other, whole one isn't written to: by the install, the restore or the finish. */
    @Test fun aWholeSlotIsLeftAloneWhileAnotherIsntWhole() {
        // Install: b takes the menu, a doesn't and can't be put back. b stays as it is.
        broken('a')
        val r = run(fresh)
        assertEquals("failed", r["restore"])
        assertEquals("half", r["result"])
        assertTrue("don't restart" in r["message"]!!)
        assertEquals(listOf("abl_b", "abl_a", "abl_a", "abl_a"), writes)
        assertArrayEquals(withMenu(origB), slot('b').readBytes())
        // Restore: a first (it's the half one), and no further.
        val again = run(restore)
        assertEquals("half", again["result"])
        assertEquals(listOf("abl_a", "abl_a"), writes)
        assertArrayEquals(withMenu(origB), slot('b').readBytes())
        // Finish: the same.
        assertEquals("half", run(finish)["result"])
        assertEquals(listOf("abl_a", "abl_a", "abl_a"), writes)
        assertArrayEquals(withMenu(origB), slot('b').readBytes())
        assertTrue(note.exists() && rootNote.exists())
    }

    /** The half-written slot is written first, whatever the order is otherwise. */
    @Test fun halfComesFirst() {
        interrupted(half(origA), origB)
        assertEquals("done", run(finish)["state"])
        assertEquals(listOf("abl_a", "abl_b"), writes)
        slot('a').writeBytes(origA)
        slot('b').writeBytes(origB)
        interrupted(withMenu(origA), half(origB))
        assertEquals("original", run(restore)["result"])
        assertEquals(listOf("abl_b", "abl_a"), writes)
    }

    private fun careful(body: (AblScript.AfterJob) -> String) = body(after().copy(platform = "sun", socModel = "CQ7790S", careful = true))
    private val carefulFinish get() = AblScript.installBody(
        AblScript.InstallJob(
            "i1", "$dir/st/status", "sun", "$dir/menu.elf", "Menu", menu.size.toLong(), sha(menu),
            "$dir/backup", null, "$dir/flash/journal", emptyList(), menus, "CQ7790S", careful = true, onlyOverKnown = true,
        )
    )

    /** The Duo with a half-written slot: whatever else is in the way, the half result, the warning, and no word of a restart to do. */
    @Test fun halfOnTheDuoIsNeverToldToRestart() {
        interrupted(origA, half(origB))
        tool("getprop", "case \$1 in ro.board.platform) echo sun;; ro.soc.model) echo CQ7790S;; ro.boot.slot_suffix) cat '$dir/suffix' 2>/dev/null;; *) echo Model;; esac")
        tool("timeout", "shift; exec \"\$@\"")
        tool("dumpsys", "cat '$dir/battery'")
        tool("bootctl", "cat '$dir/next' 2>/dev/null")
        tool("update_engine_client", "cat '$dir/update' 2>/dev/null")
        val good = "  AC powered: true\n  level: 80\n"
        fun case(battery: String, suffix: String, next: String, update: String) {
            File(dir, "battery").writeText(battery)
            File(dir, "suffix").writeText(suffix)
            File(dir, "next").writeText(next)
            File(dir, "update").writeText(update)
        }
        val cases = listOf(
            listOf("  AC powered: false\n  level: 80\n", "_a", "0", ""),
            listOf("  AC powered: true\n  level: 5\n", "_a", "0", ""),
            listOf(good, "_a", "1", ""),
            listOf(good, "_a", "0", "UPDATE_STATUS_UPDATED_NEED_REBOOT"),
            listOf(good, "_a", "0", "UPDATE_STATUS_DOWNLOADING"),
            listOf(good, "_a", "", ""),
            listOf(good, "", "0", ""),
        )
        for (c in cases) {
            case(c[0], c[1], c[2], c[3])
            val r = run(carefulFinish)
            assertEquals("$c", "failed", r["state"])
            assertEquals("$c", "half", r["result"])
            val m = r["message"]!!
            assertTrue(m, "don't restart it" in m)
            assertFalse(m, "restart" in m.replace("don't restart it", ""))
            assertTrue(writes.isEmpty())
            slots(origA, half(origB))
        }
        // Putting the original back is the repair: only an update being written right now is in its way.
        case(good, "_a", "0", "UPDATE_STATUS_DOWNLOADING")
        val r = run(careful(AblScript::restoreBody))
        assertEquals("half", r["result"])
        assertFalse("restart" in r["message"]!!.replace("don't restart it", ""))
        assertTrue(writes.isEmpty())
        for (c in cases.filter { it[3] != "UPDATE_STATUS_DOWNLOADING" }) {
            slot('b').writeBytes(half(origB))
            if (!note.exists()) {
                slot('b').writeBytes(origB)
                tool("getprop", "case \$1 in ro.board.platform) echo kalama;; ro.soc.model) echo QCS8550;; *) echo Model;; esac")
                interrupted(origA, half(origB))
                tool("getprop", "case \$1 in ro.board.platform) echo sun;; ro.soc.model) echo CQ7790S;; ro.boot.slot_suffix) cat '$dir/suffix' 2>/dev/null;; *) echo Model;; esac")
            }
            case(c[0], c[1], c[2], c[3])
            assertEquals("$c", "original", run(careful(AblScript::restoreBody))["result"])
            slots(origA, origB)
        }
    }

    /** Both slots updated: the one it started from is whole, the other unproven, so root keeps the originals. */
    @Test fun bothUpdatedKeepsTheOriginals() {
        fun elf(seed: Int) = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + bytes(seed).copyOfRange(4, size)
        interrupted(elf(11), elf(12))
        assertEquals("newer", run(check)["result"])
        File(dir, "proc/boot_id").writeText("boot-2\n")
        File(dir, "suffix").writeText("_a\n")
        assertEquals("changed", run(check)["result"])
        assertFalse(note.exists() || rootNote.exists())
        assertArrayEquals(origA, File(dir, "tmp/pbos-abl-work.kept/abl_a.img").readBytes())
        assertArrayEquals(origB, File(dir, "tmp/pbos-abl-work.kept/abl_b.img").readBytes())
        assertTrue(writes.isEmpty())
    }
}
