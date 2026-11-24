package org.projectbarry.pbosinstaller.repair

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.root.ScriptKit
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class BootEntryTest {
    companion object {
        /** A flattened devicetree whose root node has a model and [compatible] (NUL-separated). */
        fun dtb(compatible: String): ByteArray {
            val strings = "compatible\u0000model\u0000".toByteArray()
            fun prop(nameAt: Int, value: ByteArray): ByteArray {
                val b = ByteBuffer.allocate(12 + (value.size + 3) / 4 * 4).order(ByteOrder.BIG_ENDIAN)
                b.putInt(3).putInt(value.size).putInt(nameAt).put(value)
                return b.array()
            }
            val struct = ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0))                     // root node, empty name
                write(prop(11, "Model X\u0000".toByteArray()))
                write(prop(0, (compatible + "\u0000").toByteArray()))
                write(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 9))                     // end node, end
            }.toByteArray()
            val at = 40 + 16
            val total = at + struct.size + strings.size
            val h = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
            h.putInt(0xd00dfeed.toInt()).putInt(total).putInt(at).putInt(at + struct.size).putInt(40)
                .putInt(17).putInt(16).putInt(0).putInt(strings.size).putInt(struct.size)
            return h.array() + ByteArray(16) + struct + strings
        }

        /** An arm64 Image: "MZ", the EFI stub's "ARMd" at 0x38, then data. */
        fun image(seed: Int = 1): ByteArray =
            ByteArray(0x40).also { it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte(); "ARMd".toByteArray().copyInto(it, 0x38) } +
                Random(seed).nextBytes(30000) + ByteArray(50000)

        fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

        val DTBS = listOf(dtb("retroid,pocket6\u0000qcom,sm8650"), dtb("Retroid,Pocket6"), dtb("AYN Thor!!\u0000qcom,sm8550"))

        /** A version 0 boot image like PB-OS's KERNEL (its id right), with [cmdline] and [extra] (bytes 608…). */
        fun kernel(
            cmdline: String,
            extra: String = "",
            kernel: ByteArray = gzip(image()) + DTBS.reduce { a, b -> a + b },
            ramdisk: ByteArray = Random(2).nextBytes(9000),
            version: Int = 0,
        ): ByteArray {
            val page = 4096
            fun pad(b: ByteArray) = b + ByteArray((page - b.size % page) % page)
            val sha = MessageDigest.getInstance("SHA-1")
            for (blob in listOf(kernel, ramdisk, ByteArray(0))) {
                sha.update(blob)
                sha.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.size).array())
            }
            val h = ByteBuffer.allocate(page).order(ByteOrder.LITTLE_ENDIAN)
            h.put("ANDROID!".toByteArray())
            h.putInt(kernel.size).putInt(0x8000).putInt(ramdisk.size).putInt(0x1000000).putInt(0).putInt(0).putInt(0x100).putInt(page).putInt(version).putInt(0)
            h.position(64); h.put(cmdline.toByteArray())
            h.position(576); h.put(sha.digest())
            h.position(608); h.put(extra.toByteArray())
            return pad(h.array()) + pad(kernel) + pad(ramdisk)
        }

        /** The same KERNEL with root= changed, as retarget_kernel does (only bytes 64-575). */
        fun repoint(k: ByteArray, cmdline: String): ByteArray =
            k.copyOf().also { ByteArray(512).also { c -> cmdline.toByteArray().copyInto(c) }.copyInto(it, 64) }

        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }

    private val card = "console=tty0 quiet root=PARTUUID=1a2b3c4d-02 rootfstype=ext4 errors=remount-ro rw"

    @Test fun splitsLikePbosUpdate() {
        val gz = gzip(image())
        val k = kernel(card, " extra=1")
        val s = BootEntry.split(k)
        assertArrayEquals(gz, s.gz)
        assertArrayEquals(Random(2).nextBytes(9000), s.initrd)
        assertEquals("$card extra=1", s.cmdline)   // both header parts, joined as they are
        // The first root compatible, lowercased, other characters a "-"; a repeated name gets -2, in KERNEL order.
        assertEquals(listOf("retroid-pocket6.dtb", "retroid-pocket6-2.dtb", "ayn-thor.dtb"), s.dtbs.map { it.first })
        s.dtbs.zip(DTBS).forEach { (named, dtb) -> assertArrayEquals(dtb, named.second) }
        assertEquals(sha256(k).substring(0, 12), BootEntry.tag(k))
        assertEquals(
            "title PB-OS\nsort-key pbos\narchitecture aa64\nlinux /pbos/Image-abc.gz\ninitrd /pbos/initrd-abc.gz\noptions $card extra=1\n",
            BootEntry.conf("abc", s.cmdline),
        )
    }

    @Test fun refusesWhatCantStartFromAnEntry() {
        fun no(k: ByteArray) = assertNull(BootEntry.splitOrNull(k))
        no(ByteArray(5000))
        no(kernel(card, version = 2))
        no(kernel(card, kernel = gzip(ByteArray(0x40 + 1000))))                         // no EFI stub
        no(kernel(card, kernel = gzip(image())))                                       // no DTBs
        no(kernel(card, kernel = gzip(image()) + DTBS[0] + ByteArray(7)))             // junk after them
        no(kernel(card, kernel = ByteArray(5000) { it.toByte() }))                     // not gzip
        no(kernel(card, ramdisk = ByteArray(0)))
        no(kernel("console=tty0 quiet"))                                               // no root=
        no(kernel(card, kernel = gzip(image()).let { it.copyOf(it.size - 1) } + DTBS[0])) // trailer damaged
    }

    /** The real KERNEL, when PBOS_KERNEL points at it (not in the repo). */
    @Test fun realKernel() {
        val f = System.getenv("PBOS_KERNEL")?.let(::File) ?: return
        val s = BootEntry.split(f.readBytes())
        assertTrue(s.dtbs.isNotEmpty())
        assertTrue("root=" in s.cmdline)
    }

    // The shell functions the root jobs use, run here with the host's tools
    // (the same commands toybox has: od, dd, tr, head, cut, cmp, sha256sum).

    private fun sh(script: String): Pair<Int, String> {
        val f = File.createTempFile("pbos-entry", ".sh").apply { writeText("${ScriptKit.BOOT_ENTRY}\n$script\n") }
        val p = ProcessBuilder("/bin/sh", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun hostHasTools() = sh("command -v sha256sum && command -v od && command -v cmp").first == 0

    private fun q(f: File) = ScriptKit.quote(f.path)

    @Test fun shellWritesTheSameEntry() {
        assumeTrue(hostHasTools())
        val dir = Files.createTempDirectory("entry").toFile()
        val boot = File(dir, "boot").apply { mkdirs() }
        val pieces = File(dir, "pieces")
        // The image's entry, as pbos-update.py wrote it for the card.
        val image = kernel(card, " extra=1")
        File(boot, "KERNEL").writeBytes(image)
        BootEntry.split(image).writeTo(pieces)
        assertEquals(0, sh("put_entry ${q(boot)} ${q(File(boot, "KERNEL"))} ${q(pieces)}").first)
        fun expect(k: ByteArray) {
            val s = BootEntry.split(k)
            val tag = BootEntry.tag(k)
            assertEquals(BootEntry.conf(tag, s.cmdline), File(boot, BootEntry.CONF).readText())
            assertEquals(setOf("Image-$tag.gz", "initrd-$tag.gz"), File(boot, "pbos").list()!!.toSet())
            assertArrayEquals(s.gz, File(boot, "pbos/Image-$tag.gz").readBytes())
            assertArrayEquals(s.initrd, File(boot, "pbos/initrd-$tag.gz").readBytes())
            assertEquals(s.dtbs.map { it.first }.toSet(), File(boot, BootEntry.DTBS).list()!!.toSet())
            s.dtbs.forEach { (name, dtb) -> assertArrayEquals(dtb, File(boot, "${BootEntry.DTBS}/$name").readBytes()) }
        }
        expect(image)
        assertEquals(0, sh("entry_matches ${q(boot)} ${q(File(boot, "KERNEL"))}").first)

        // Re-pointed (the internal install): the same pieces, a new tag and command line;
        // leftovers go, Android's files stay.
        val internal = repoint(image, "console=tty0 quiet rw root=PARTLABEL=STORAGE rootfstype=ext4 errors=remount-ro")
        File(boot, "KERNEL").writeBytes(internal)
        assertEquals(1, sh("entry_matches ${q(boot)} ${q(File(boot, "KERNEL"))}").first)
        File(boot, "FSCK0000.000").writeText("x")
        File(boot, "pbos/Image-000000000000.gz").writeText("old")
        File(boot, "${BootEntry.DTBS}/gone.dtb").writeText("old")
        File(boot, "${BootEntry.DTBS}/ayn-thor.dtb.new").writeText("half")
        File(boot, "${BootEntry.DTBS}/notes.txt").writeText("kept")
        assertEquals(0, sh("put_entry ${q(boot)} ${q(File(boot, "KERNEL"))} ${q(pieces)}").first)
        File(boot, "${BootEntry.DTBS}/notes.txt").delete()
        expect(internal)
        assertTrue(File(boot, "FSCK0000.000").isFile)
        assertTrue(File(boot, BootEntry.CONF).readText().contains("options console=tty0 quiet rw root=PARTLABEL=STORAGE rootfstype=ext4 errors=remount-ro extra=1\n"))

        // A damaged entry doesn't match.
        val initrd = File(boot, "pbos/initrd-${BootEntry.tag(internal)}.gz")
        initrd.appendText("x")
        assertEquals(1, sh("entry_matches ${q(boot)} ${q(File(boot, "KERNEL"))}").first)

        // Pieces of another kernel are refused before anything is written.
        val other = kernel(card, kernel = gzip(image(seed = 7)) + DTBS[2])
        val before = File(boot, BootEntry.CONF).readText()
        File(dir, "other").writeBytes(other)
        assertEquals(1, sh("put_entry ${q(boot)} ${q(File(dir, "other"))} ${q(pieces)}").first)
        assertEquals(before, File(boot, BootEntry.CONF).readText())
    }

    @Test fun shellReadsTheCommandLineUpToItsNul() {
        assumeTrue(hostHasTools())
        val k = Files.createTempFile("kernel", "").toFile()
        val bytes = kernel("root=X a", "b").also { "junk".toByteArray().copyInto(it, 64 + 9 + 3) }
        k.writeBytes(bytes)
        val (code, out) = sh("kernel_cmdline ${q(k)}; echo; kernel_tag ${q(k)}")
        assertEquals(0, code)
        assertEquals("root=X ab\n${sha256(bytes).substring(0, 12)}\n", out)
        assertFalse(out.contains("junk"))
    }
}
