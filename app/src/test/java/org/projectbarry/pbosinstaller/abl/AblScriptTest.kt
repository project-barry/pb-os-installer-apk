package org.projectbarry.pbosinstaller.abl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.device.Soc
import java.io.File
import java.security.MessageDigest

class AblScriptTest {
    private val backup = AblScript.BackupJob(
        id = "b1", statusFile = "/data/media/0/x/abl-backup/status", backupDir = "/data/media/0/x/abl-backup/2026",
        platform = "pineapple", info = listOf("handheld=KONKR Pocket FIT", "model=Pocket FIT"),
        stock = AblKnown.stock, menus = AblKnown.menus.getValue(Soc.SM8650),
    )
    private val install = AblScript.InstallJob(
        id = "i1", statusFile = "/data/media/0/x/abl-install/status", platform = "pineapple",
        payload = "/data/media/0/x/abl/abl_signed-SM8650.elf", payloadBytes = 532480,
        payloadSha256 = AblKnown.payload.getValue(Soc.SM8650).sha256, backupDir = "/data/media/0/x/abl-backup/2026",
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
            "of=/dev/block/by-name/abl_b", // slot b first
            "the originals were put back",
        ).forEach { assertTrue(it, it in body) }
        assertTrue(body.indexOf("of=/dev/block/by-name/abl_b") < body.indexOf("if=\"\$PAYLOAD\" of=/dev/block/by-name/abl_a"))
    }

    /** The bundled OpenABL files are exactly the approved ones. */
    @Test fun bundledPayloadsMatch() {
        AblKnown.payload.values.forEach { p ->
            val f = File("src/main/assets/${p.asset}")
            assertEquals(p.asset, p.bytes, f.length())
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(p.asset, p.sha256, sha)
        }
    }

    /** OpenABL 1.1 for each chip, and the bundled file is also what the backup recognizes. */
    @Test fun bundledIsOpenAbl11AndKnown() {
        assertEquals("OpenABL 1.1", AblKnown.BUNDLED)
        AblKnown.payload.forEach { (soc, p) ->
            assertTrue(p.asset, p.asset.startsWith("abl/openabl-1.1/"))
            assertTrue(p.asset, p.asset.endsWith("abl_signed-${soc.name}.elf"))
            assertEquals(p.label, AblKnown.BUNDLED)
            assertTrue(soc.name, AblKnown.menus.getValue(soc).any { it.label == p.label && it.sha256 == p.sha256 && it.bytes == p.bytes })
            // Fits the 1 MiB abl partitions of the tested handhelds.
            assertTrue(p.bytes <= 1048576)
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

    @Test fun alreadyInstalledOnlyWithBothSlotsOnTheBundledVersion() {
        assertTrue(AblKnown.alreadyInstalled("menu: OpenABL 1.1", "menu: OpenABL 1.1"))
        // ROCKNIX 1.2 (what app 0.3 installed) gets OpenABL offered.
        assertFalse(AblKnown.alreadyInstalled("menu: ROCKNIX 1.2", "menu: ROCKNIX 1.2"))
        assertFalse(AblKnown.alreadyInstalled("menu: OpenABL 1.1", "menu: OpenABL 1.0"))
        assertFalse(AblKnown.alreadyInstalled("stock: KONKR Pocket FIT (firmware 2025-12-26)", "menu: ROCKNIX 1.1.8"))
        // The label the script prints must be what alreadyInstalled looks for.
        assertTrue("echo 'menu: OpenABL 1.1'" in AblScript.backupBody(backup))
    }

    /** identify really names each known menu, run against files on this computer. */
    @Test fun identifyNamesTheMenus() {
        val dir = java.nio.file.Files.createTempDirectory("pbos-abl-id").toFile()
        fun ablFile(name: String, head: ByteArray): File = File(dir, name).apply {
            // A 1 MiB partition: the menu at the start, other bytes after it.
            writeBytes(head + ByteArray(1048576 - head.size) { 0x5a })
        }
        val openabl = File("src/main/assets/${AblKnown.payload.getValue(Soc.SM8650).asset}").readBytes()
        val files = listOf(ablFile("openabl", openabl), ablFile("other", ByteArray(1000) { 1 }))
        val script = File(dir, "id.sh").apply {
            writeText(
                "sha() { if command -v sha256sum >/dev/null; then sha256sum \"\$@\"; else shasum -a 256 \"\$@\"; fi | cut -d' ' -f1; }\n" +
                    AblScript.identifyFunctions(AblKnown.stock, AblKnown.menus.getValue(Soc.SM8650)) + "\n" +
                    "for f in \"\$@\"; do identify \"\$f\"; done\n"
            )
        }
        val p = ProcessBuilder(listOf("/bin/sh", script.path) + files.map { it.path }).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines()
        assertEquals(0, p.waitFor())
        assertEquals(listOf("menu: OpenABL 1.1", "unknown"), out)
        dir.deleteRecursively()
    }
}
