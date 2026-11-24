package org.projectbarry.pbosinstaller.repair

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * OpenABL's boot entry (Boot Loader Specification), as PB-OS writes it next to
 * KERNEL on the boot partition (pb-os external-and-mods/pbos-update/pbos-update.py:
 * split_kernel_image, dtb_file_name, refresh_boot_entry; this matches it byte for byte).
 *
 * OpenABL newer than 1.2 no longer boots KERNEL: it starts Linux through its EFI
 * stub from loader/entries/pbos.conf, with the DTB its device menu picks from
 * dtbs/qcom/. OpenABL 1.2 reads the entry too, before KERNEL. So whatever
 * writes or re-points KERNEL must make the entry start that same KERNEL.
 *
 * The files, from KERNEL (an Android boot image, header v0, whose kernel is
 * gzip(Image) with the DTBs appended):
 *  - pbos/Image-<tag>.gz: the gzip stream only; pbos/initrd-<tag>.gz: the ramdisk;
 *    <tag> = the first 12 hex digits of KERNEL's SHA-256;
 *  - dtbs/qcom/<first root compatible>.dtb per DTB ([dtbFileName]; a repeated
 *    name gets -2, -3… in KERNEL order);
 *  - loader/entries/pbos.conf ([conf]), options = the whole command line.
 *
 * The root jobs write them with [ScriptKit.BOOT_ENTRY][org.projectbarry.pbosinstaller.root.ScriptKit.BOOT_ENTRY]
 * from the pieces [Split.writeTo] leaves in a folder.
 */
object BootEntry {
    const val CONF = "loader/entries/pbos.conf"
    const val DIR = "pbos"
    const val DTBS = "dtbs/qcom"

    /** KERNEL in pieces: [gz] the gzip Image, [dtbs] by file name in KERNEL order, [initrd], [cmdline]. */
    class Split(val gz: ByteArray, val dtbs: List<Pair<String, ByteArray>>, val initrd: ByteArray, val cmdline: String) {
        /**
         * The pieces for a root job: Image.gz, initrd.gz and dtbs/<name>.dtb in [dir]
         * (emptied first). The tag and command line come from the KERNEL the job
         * ends up with (re-pointing changes only the command line).
         */
        fun writeTo(dir: File) {
            dir.deleteRecursively()
            File(dir, "dtbs").mkdirs()
            File(dir, "Image.gz").writeBytes(gz)
            File(dir, "initrd.gz").writeBytes(initrd)
            for ((name, dtb) in dtbs) File(dir, "dtbs/$name").writeBytes(dtb)
        }
    }

    class NotSplittable(message: String) : Exception(message)

    /** [split], or null when this KERNEL can't start from an entry. */
    fun splitOrNull(data: ByteArray): Split? = try { split(data) } catch (e: NotSplittable) { null }

    /** KERNEL -> its entry's pieces; [NotSplittable] says why not. */
    fun split(data: ByteArray): Split {
        fun no(why: String): Nothing = throw NotSplittable(why)
        if (data.size < 1632 || String(data, 0, 8, Charsets.ISO_8859_1) != "ANDROID!") no("KERNEL is not an Android boot image")
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val kernelSize = b.getInt(8).toLong() and 0xffffffffL
        val ramdiskSize = b.getInt(16).toLong() and 0xffffffffL
        val page = b.getInt(36).toLong() and 0xffffffffL
        val version = b.getInt(40)
        if (version != 0) no("KERNEL header v$version, expected v0")
        if (page == 0L) no("KERNEL has no page size")
        val cmdline = upToNul(data, 64, 512) + upToNul(data, 608, 1024)
        // Like Python slices: a section past the end of the file is cut short, not an error.
        fun slice(at: Long, size: Long): ByteArray {
            val from = minOf(at, data.size.toLong()).toInt()
            val to = minOf(at + size, data.size.toLong()).toInt()
            return data.copyOfRange(from, to)
        }
        val kernel = slice(page, kernelSize)
        val initrd = slice(page + (kernelSize + page - 1) / page * page, ramdiskSize)
        val gzEnd = gzipEnd(kernel) ?: no("KERNEL has no gzip Image with an EFI stub")
        var rest = kernel.copyOfRange(gzEnd, kernel.size)
        val dtbs = mutableListOf<ByteArray>()
        while (rest.isNotEmpty()) {
            if (rest.size < 8) no("junk after the appended DTBs")
            val r = ByteBuffer.wrap(rest).order(ByteOrder.BIG_ENDIAN)
            if (r.getInt(0) != 0xd00dfeed.toInt()) no("junk after the appended DTBs")
            val total = r.getInt(4).toLong() and 0xffffffffL
            if (total == 0L) no("junk after the appended DTBs")
            val n = minOf(total, rest.size.toLong()).toInt()
            dtbs += rest.copyOfRange(0, n)
            rest = rest.copyOfRange(n, rest.size)
        }
        if (dtbs.isEmpty() || initrd.isEmpty() || "root=" !in cmdline) no("KERNEL lacks DTBs, initrd or root=")
        if (cmdline.any { it.code > 127 }) no("KERNEL's command line isn't ASCII")
        return Split(kernel.copyOfRange(0, gzEnd), names(dtbs), initrd, cmdline)
    }

    /** "<tag>": the first 12 hex digits of KERNEL's SHA-256. */
    fun tag(kernel: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(kernel).joinToString("") { "%02x".format(it) }.substring(0, 12)

    /** loader/entries/pbos.conf for KERNEL [tag] and its command line. */
    fun conf(tag: String, cmdline: String): String =
        "title PB-OS\nsort-key pbos\narchitecture aa64\n" +
            "linux /$DIR/Image-$tag.gz\ninitrd /$DIR/initrd-$tag.gz\n" +
            "options $cmdline\n"

    /** File names for [dtbs] in KERNEL order: a repeated name gets -2, -3… (models can share a compatible). */
    fun names(dtbs: List<ByteArray>): List<Pair<String, ByteArray>> {
        val taken = mutableSetOf<String>()
        return dtbs.map { dtb ->
            val stem = dtbFileName(dtb).removeSuffix(".dtb")
            var name = "$stem.dtb"
            var n = 2
            while (name in taken) name = "$stem-${n++}.dtb"
            taken += name
            name to dtb
        }
    }

    /** "<first root compatible>.dtb", lowercased, every run of other characters than a-z0-9 a "-", e.g. ayn-thor.dtb. */
    fun dtbFileName(dtb: ByteArray): String {
        fun no(): Nothing = throw NotSplittable("a DTB without a root compatible")
        try {
            val b = ByteBuffer.wrap(dtb).order(ByteOrder.BIG_ENDIAN)
            val strings = b.getInt(12)
            var pos = b.getInt(8)
            var depth = 0
            fun nul(from: Int): Int = (from until dtb.size).firstOrNull { dtb[it] == 0.toByte() } ?: no()
            while (true) {
                val token = b.getInt(pos); pos += 4
                when (token) {
                    1 -> {
                        pos = (nul(pos) + 4) and 3.inv(); depth++
                        if (depth > 1) no()
                    }
                    3 -> {
                        val n = b.getInt(pos); val nameAt = b.getInt(pos + 4); pos += 8
                        val name = String(dtb, strings + nameAt, nul(strings + nameAt) - strings - nameAt, Charsets.ISO_8859_1)
                        if (name == "compatible") {
                            val value = dtb.copyOfRange(pos, pos + n)
                            val end = value.indexOf(0.toByte()).let { if (it < 0) value.size else it }
                            // Python's decode('ascii', 'replace'): a non-ASCII byte is U+FFFD, which isn't a-z0-9 either.
                            val first = String(CharArray(end) { i -> value[i].toInt().let { c -> if (c in 0..127) c.toChar() else '�' } })
                            return first.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".dtb"
                        }
                        pos = (pos + n + 3) and 3.inv()
                    }
                    4 -> {}
                    else -> no()
                }
            }
        } catch (e: RuntimeException) {
            no()   // a damaged DTB: offsets or sizes outside it
        }
    }

    private fun upToNul(data: ByteArray, at: Int, size: Int): String {
        var end = at
        while (end < at + size && data[end] != 0.toByte()) end++
        return String(data, at, end - at, Charsets.ISO_8859_1)
    }

    /**
     * Where the gzip stream at the start of [kernel] ends (its trailer checked),
     * or null when it isn't one whole stream of an arm64 Image with an EFI stub
     * ("MZ" at 0, "ARMd" at 0x38).
     */
    private fun gzipEnd(kernel: ByteArray): Int? {
        if (kernel.size < 18 || kernel[0] != 0x1f.toByte() || kernel[1] != 0x8b.toByte() || kernel[2] != 8.toByte()) return null
        val flags = kernel[3].toInt()
        var pos = 10
        fun u16(at: Int) = (kernel[at].toInt() and 0xff) or ((kernel[at + 1].toInt() and 0xff) shl 8)
        fun skipString() { while (pos < kernel.size && kernel[pos] != 0.toByte()) pos++; pos++ }
        try {
            if (flags and 4 != 0) pos += 2 + u16(pos)
            if (flags and 8 != 0) skipString()
            if (flags and 16 != 0) skipString()
            if (flags and 2 != 0) pos += 2
        } catch (e: IndexOutOfBoundsException) {
            return null
        }
        if (pos >= kernel.size) return null
        val inflater = Inflater(true)
        val crc = CRC32()
        val head = ByteArray(0x40)
        var headLen = 0
        var total = 0L
        try {
            inflater.setInput(kernel, pos, kernel.size - pos)
            val buf = ByteArray(1 shl 16)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                crc.update(buf, 0, n)
                if (headLen < head.size) {
                    val m = minOf(n, head.size - headLen)
                    System.arraycopy(buf, 0, head, headLen, m)
                    headLen += m
                }
                total += n
            }
            val end = kernel.size - inflater.remaining
            if (end + 8 > kernel.size) return null
            val t = ByteBuffer.wrap(kernel, end, 8).order(ByteOrder.LITTLE_ENDIAN)
            if (t.getInt() != crc.value.toInt() || t.getInt() != total.toInt()) return null
            if (headLen < 0x3c || head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte() ||
                String(head, 0x38, 4, Charsets.ISO_8859_1) != "ARMd"
            ) return null
            return end + 8
        } catch (e: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
