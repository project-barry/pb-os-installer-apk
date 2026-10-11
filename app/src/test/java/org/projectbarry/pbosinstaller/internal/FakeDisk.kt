package org.projectbarry.pbosinstaller.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A GPT disk for tests, kept as its first and last MiB (what the probe saves).
 * Laid out like the handhelds' UFS LUN 0: 4 KiB sectors, 32 entries, Android's
 * small partitions first, userdata, then whatever Linux install the test wants.
 */
class FakeDisk(
    val sectorSize: Int = 4096,
    val bytes: Long = 476L shl 30,
    val entryCount: Int = 32,
) {
    data class Part(val name: String, val first: Long, val last: Long, val type: Guid = Guid.LINUX)

    val lastLba = bytes / sectorSize - 1
    val entrySectors = (entryCount * 128 + sectorSize - 1) / sectorSize
    val firstUsable = 2L + entrySectors
    val lastUsable = lastLba - entrySectors - 1
    var head = ByteArray(1 shl 20)
    var tail = ByteArray(1 shl 20)
    private val tailStart = lastLba + 1 - tail.size / sectorSize

    fun write(parts: List<Part?>, garbleBackup: Boolean = false): FakeDisk {
        val array = ByteArray(entrySectors * sectorSize)
        parts.forEachIndexed { i, p ->
            if (p == null) return@forEachIndexed
            val b = ByteBuffer.wrap(array, i * 128, 128).slice().order(ByteOrder.LITTLE_ENDIAN)
            b.put(p.type.bytes).put(Guid.random().bytes).putLong(p.first).putLong(p.last).putLong(0)
            b.put(p.name.toByteArray(Charsets.UTF_16LE))
        }
        val crc = Gpt.crc32(array, 0, entryCount * 128)
        val disk = Guid.random()
        fun header(my: Long, alt: Long, entries: Long): ByteArray {
            val h = ByteArray(sectorSize)
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            b.putLong(0, 0x5452415020494645L).putInt(8, 0x00010000).putInt(12, 92)
            b.putLong(24, my).putLong(32, alt).putLong(40, firstUsable).putLong(48, lastUsable)
            b.position(56); b.put(disk.bytes)
            b.putLong(72, entries).putInt(80, entryCount).putInt(84, 128).putInt(88, crc)
            b.putInt(16, Gpt.crc32(h, 0, 92))
            return h
        }
        put(1, header(1, lastLba, 2))
        put(2, array)
        put(lastLba - entrySectors, if (garbleBackup) array.copyOf().also { it[0] = (it[0] + 1).toByte() } else array)
        put(lastLba, header(lastLba, 1, lastLba - entrySectors))
        return this
    }

    /** Writes [data] at [lba], into whichever saved MiB holds it. */
    fun put(lba: Long, data: ByteArray) {
        if (lba < tailStart) data.copyInto(head, (lba * sectorSize).toInt())
        else data.copyInto(tail, ((lba - tailStart) * sectorSize).toInt())
    }

    fun gpt() = Gpt.parse(head, tail, bytes, sectorSize)

    /** Lays [table] down the way the install script does. */
    fun apply(table: Gpt) {
        put(table.backupEntriesLba, table.entryArrayBytes())
        put(table.backupLba, table.backupHeaderBytes())
        put(table.primaryEntriesLba, table.entryArrayBytes())
        put(1, table.primaryHeaderBytes())
    }

    companion object {
        const val GIB = 1L shl 30

        /** Android's small partitions plus userdata ending at [userdataGib] GiB from its start. */
        fun android(ss: Int, userdataGib: Long): List<Part> {
            val g = GIB / ss
            return listOf(
                Part("ssd", 6, 7, Guid.parse("2C86E742-745E-4FDD-BFD8-B6A7AC638772")),
                Part("persist", 8, 8 + 8192 - 1),
                Part("misc", 8192 + 8, 8192 + 8 + 255),
                Part("userdata", 65536, 65536 + userdataGib * g - 1),
            )
        }
    }
}
