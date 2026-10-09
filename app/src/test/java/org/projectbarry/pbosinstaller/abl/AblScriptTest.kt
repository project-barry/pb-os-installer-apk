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
        stock = AblKnown.stock, rocknix = AblKnown.rocknix.getValue(Soc.SM8650),
    )
    private val install = AblScript.InstallJob(
        id = "i1", statusFile = "/data/media/0/x/abl-install/status", platform = "pineapple",
        payload = "/data/media/0/x/abl/abl_signed-SM8650.elf", payloadBytes = 258048,
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
        assertTrue("head -c 258048" in body)
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

    /** The bundled ROCKNIX ABL files are exactly the approved ones. */
    @Test fun bundledPayloadsMatch() {
        AblKnown.payload.values.forEach { p ->
            val f = File("src/main/assets/${p.asset}")
            assertEquals(p.asset, p.bytes, f.length())
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(p.asset, p.sha256, sha)
        }
    }

    @Test fun alreadyInstalledOnlyWithBothSlotsOnTheBundledVersion() {
        assertTrue(AblKnown.alreadyInstalled("rocknix: ROCKNIX 1.2", "rocknix: ROCKNIX 1.2"))
        assertFalse(AblKnown.alreadyInstalled("stock: KONKR Pocket FIT (firmware 2025-12-26)", "rocknix: ROCKNIX 1.1.8"))
        // The label the script prints must be what alreadyInstalled looks for.
        assertTrue("echo 'rocknix: ROCKNIX 1.2'" in AblScript.backupBody(backup))
    }
}
