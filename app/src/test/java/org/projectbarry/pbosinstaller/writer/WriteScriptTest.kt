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
            "trap 'restore_vm; on_exit' EXIT", // settings back, wake lock released, copy gone
        ).forEach { assertTrue(it, it in body) }
        assertTrue("rereadpt" !in body)
        // The card's mounts are found by device number and must all be gone before
        // writing; a lazy unmount leaves the old filesystem writing into the image.
        assertTrue("/proc/self/mountinfo" in body)
        assertTrue("tr '_,' '::'" in body) // public:179_1 and public:179,1
        assertTrue("Android is still using the card" in body)
        assertTrue("umount -l" !in body)
        // No shell arithmetic or -ge/-gt on byte counts: Android's shell is 32-bit.
        assertTrue(Regex("""\* 512|-ge "\$\{?(SIZE|IMAGE_BYTES)""").find(body) == null)
    }

    @Test fun startsWithPrelude() {
        val body = WriteScript.body(job)
        assertTrue(body.startsWith("#!/system/bin/sh"))
        // Moves itself off shared storage and into the background.
        assertTrue("RUN_COPY='/data/local/tmp/pbos-write.sh'" in body)
        assertTrue("nohup sh" in body)
        // Stays awake until it exits: a sleep attempt mid-write hangs the system.
        assertTrue("> /sys/power/wake_lock" in body && "> /sys/power/wake_unlock" in body)
        // The dry run stays in the foreground (adb waits for it).
        assertTrue("RUN_COPY" !in WriteScript.body(job.copy(dryRunTarget = "/dev/null")).substringBefore("SEVENZIP="))
    }

    @Test fun quotingSurvivesQuotes() {
        assertEquals("'it'\\''s'", WriteScript.quote("it's"))
    }

    @Test fun rootPath() {
        assertEquals("/data/media/0/Android/data/p/files/write/status",
            org.projectbarry.pbosinstaller.root.RootJobs.rootPath(File("/storage/emulated/0/Android/data/p/files/write/status")))
    }
}
