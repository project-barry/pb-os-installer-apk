package org.projectbarry.pbosinstaller.repair

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The ABL's KERNEL: an Android boot image with a version 0 header (as PB-OS
 * builds it, see pb-os external-and-mods/ufs-install/ufs-bootimg.py). Only
 * what the repair check needs: is it whole, and which root does it boot.
 */
class BootImage private constructor(
    val cmdline: String,
    /** The header's id matches the kernel, ramdisk and second stage (mkbootimg's SHA-1). */
    val idOk: Boolean,
    val kernelSize: Int,
) {
    /** The root= argument, e.g. PARTLABEL=STORAGE or PARTUUID=1a2b3c4d-02. */
    val root: String? get() = cmdline.split(' ').firstOrNull { it.startsWith("root=") }?.removePrefix("root=")

    companion object {
        private const val HEADER = 8 + 10 * 4 + 16 + 512 + 32 + 1024

        /** Null when [data] isn't a whole version 0 boot image. */
        fun parse(data: ByteArray): BootImage? {
            if (data.size < HEADER || String(data, 0, 8, Charsets.US_ASCII) != "ANDROID!") return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val kernelSize = b.getInt(8)
            val ramdiskSize = b.getInt(16)
            val secondSize = b.getInt(24)
            val pageSize = b.getInt(36)
            if (b.getInt(40) != 0 || (pageSize != 2048 && pageSize != 4096)) return null
            if (kernelSize < 0 || ramdiskSize < 0 || secondSize < 0) return null
            fun pages(n: Int) = (n.toLong() + pageSize - 1) / pageSize * pageSize
            val kernelAt = pageSize.toLong()
            val ramdiskAt = kernelAt + pages(kernelSize)
            val secondAt = ramdiskAt + pages(ramdiskSize)
            if (secondAt + secondSize > data.size) return null
            val cmd = String(data, 64, 512, Charsets.US_ASCII).substringBefore('\u0000')
            val sha = MessageDigest.getInstance("SHA-1")
            for ((at, size) in listOf(kernelAt to kernelSize, ramdiskAt to ramdiskSize, secondAt to secondSize)) {
                sha.update(data, at.toInt(), size)
                sha.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array())
            }
            val id = sha.digest().copyOf(32)
            val idOk = id.contentEquals(data.copyOfRange(576, 608))
            return BootImage(cmd, idOk, kernelSize)
        }
    }
}
