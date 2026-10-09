package org.projectbarry.pbosinstaller.writer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WriteScriptTest {
    private val job = WriteScript.Job(
        id = "job-1",
        sevenZip = "/data/app/x/lib/arm64/lib7zzs.so",
        parts = listOf(
            "/sdcard/Android/data/p/files/images/a.7z.001" to "a".repeat(64),
            "/sdcard/Android/data/p/files/images/a.7z.002" to "b".repeat(64),
        ),
        imageSha256 = "c".repeat(64),
        cardBytes = 512711720960,
        statusFile = "/sdcard/Android/data/p/files/write/status",
    )

    /** /bin/sh -n parses the whole script without running it. */
    @Test fun bodyIsValidShell() {
        val f = File.createTempFile("pbos-write", ".sh")
        f.writeText(WriteScript.body(job))
        val p = ProcessBuilder("/bin/sh", "-n", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun bodyHasEveryCheck() {
        val body = WriteScript.body(job)
        listOf(
            "device/type", // only the SD card
            "512711720960", // the card the app saw
            "(/|/system|/vendor|/data|/metadata)", // never what the system runs from
            "card is too small",
            "check_part '/sdcard/Android/data/p/files/images/a.7z.002' '${"b".repeat(64)}'",
            "IMAGE_SHA='${"c".repeat(64)}'",
            "sm unmount",
            "expr \"${'$'}(cat /sys/block/${'$'}DEV/size)\" '*' 512", // 64-bit card size
            "echo 67108864 > ${'$'}VM/dirty_bytes", // no system stall while writing
            "trap restore_vm EXIT",
        ).forEach { assertTrue(it, it in body) }
        assertTrue("rereadpt" !in body)
        // No shell arithmetic or -ge/-gt on byte counts: Android's shell is 32-bit.
        assertTrue(Regex("""\* 512|-ge "\$\{?(SIZE|IMAGE_BYTES)""").find(body) == null)
    }

    @Test fun launcherIsOneLine() {
        val l = WriteScript.launcher("/data/media/0/x/pbos-write.sh")
        assertEquals(1, l.trimEnd().lines().size)
        assertEquals("cp '/data/media/0/x/pbos-write.sh' /data/local/tmp/pbos-write.sh && nohup sh /data/local/tmp/pbos-write.sh >/dev/null 2>&1 &\n", l)
    }

    @Test fun quotingSurvivesQuotes() {
        assertEquals("'it'\\''s'", WriteScript.quote("it's"))
    }

    @Test fun rootPath() {
        assertEquals("/data/media/0/Android/data/p/files/write/status",
            CardWriter.rootPath(File("/storage/emulated/0/Android/data/p/files/write/status")))
    }
}
