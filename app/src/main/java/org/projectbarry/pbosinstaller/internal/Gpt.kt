package org.projectbarry.pbosinstaller.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * A GUID partition table as read from the handheld's internal disk: the
 * primary header (LBA 1) and its entry array, checked against the backup copy
 * at the end of the disk. Only what the internal install needs: reading,
 * replacing some entries and writing both copies back with fresh checksums.
 * Every other byte of the headers is kept as it was.
 */
class Gpt private constructor(
    val sectorSize: Int,
    /** The primary header sector exactly as read. */
    private val primaryHeader: ByteArray,
    /** The backup header sector exactly as read. */
    private val backupHeader: ByteArray,
    /** The entry array's sectors exactly as read (or as [with] changed them). */
    private val entryArray: ByteArray,
    /** The backup entry array's sectors exactly as read (same entries; padding may differ). */
    private val backupEntryArray: ByteArray,
    val entries: List<Entry>,
) {
    /** One slot of the entry array; [isEmpty] slots have an all-zero type. */
    data class Entry(
        /** 1-based slot number (the Linux partition number). */
        val number: Int,
        val type: Guid,
        val guid: Guid,
        val firstLba: Long,
        /** Inclusive. */
        val lastLba: Long,
        val attributes: Long,
        val name: String,
    ) {
        val isEmpty get() = type.isZero
        val sectors get() = lastLba - firstLba + 1
    }

    private val hdr = ByteBuffer.wrap(primaryHeader).order(ByteOrder.LITTLE_ENDIAN)
    private val bak = ByteBuffer.wrap(backupHeader).order(ByteOrder.LITTLE_ENDIAN)
    val firstUsableLba: Long get() = hdr.getLong(40)
    val lastUsableLba: Long get() = hdr.getLong(48)
    val backupLba: Long get() = hdr.getLong(32)
    val primaryEntriesLba: Long get() = hdr.getLong(72)
    val backupEntriesLba: Long get() = bak.getLong(72)
    val entryCount: Int get() = hdr.getInt(80)
    val entrySize: Int get() = hdr.getInt(84)
    /** Sectors the entry array takes. */
    val entrySectors: Int get() = ((entryCount.toLong() * entrySize + sectorSize - 1) / sectorSize).toInt()

    /** Non-empty entries, by where they start on the disk. */
    val partitions: List<Entry> get() = entries.filter { !it.isEmpty }.sortedBy { it.firstLba }

    /** The header sector and the entry array as written to the disk (primary copy). */
    fun primaryHeaderBytes(): ByteArray = primaryHeader.copyOf()
    fun backupHeaderBytes(): ByteArray = backupHeader.copyOf()
    fun entryArrayBytes(): ByteArray = entryArray.copyOf()
    fun backupEntryArrayBytes(): ByteArray = backupEntryArray.copyOf()

    /**
     * The same table with [replace] entries put in their slots (by number); every
     * other byte of the entry array stays. Both headers get the new entry checksum
     * and their own header checksum.
     */
    fun with(replace: List<Entry>): Gpt {
        val bySlot = replace.associateBy { it.number }
        require(bySlot.keys.all { it in 1..entryCount }) { "slot out of range" }
        val array = entryArray.copyOf()
        replace.forEach { e -> encodeEntry(e).copyInto(array, (e.number - 1) * entrySize) }
        val crc = crc32(array, 0, entryCount * entrySize)
        return Gpt(sectorSize, rehead(primaryHeader, crc), rehead(backupHeader, crc), array, array, entries.map { bySlot[it.number] ?: it })
    }

    private fun encodeEntry(e: Entry): ByteArray {
        val b = ByteBuffer.allocate(entrySize).order(ByteOrder.LITTLE_ENDIAN)
        if (e.isEmpty) return b.array()
        b.put(e.type.bytes).put(e.guid.bytes).putLong(e.firstLba).putLong(e.lastLba).putLong(e.attributes)
        val name = e.name.toByteArray(Charsets.UTF_16LE)
        require(name.size <= 72) { "partition name too long" }
        b.put(name)
        return b.array()
    }

    companion object {
        private const val SIGNATURE = 0x5452415020494645L // "EFI PART"

        /**
         * Reads the table from [head] (the disk's first bytes, at least to the end of
         * the primary entry array) and [tail] (its last bytes, at least from the
         * backup entry array on), [diskBytes] long in [sectorSize]-byte sectors.
         * Throws [GptError] unless both copies are intact and agree.
         */
        fun parse(head: ByteArray, tail: ByteArray, diskBytes: Long, sectorSize: Int): Gpt {
            if (sectorSize != 512 && sectorSize != 4096) throw GptError("unexpected sector size $sectorSize")
            val lastLba = diskBytes / sectorSize - 1
            val tailStartLba = lastLba + 1 - tail.size / sectorSize
            fun headSector(lba: Long): ByteArray {
                val off = lba * sectorSize
                if (off + sectorSize > head.size) throw GptError("table lies outside the bytes read")
                return head.copyOfRange(off.toInt(), off.toInt() + sectorSize)
            }
            fun tailSector(lba: Long): ByteArray {
                if (lba < tailStartLba || lba > lastLba) throw GptError("backup table lies outside the bytes read")
                val off = ((lba - tailStartLba) * sectorSize).toInt()
                return tail.copyOfRange(off, off + sectorSize)
            }

            val primary = headSector(1)
            checkHeader(primary, "primary", myLba = 1)
            val p = ByteBuffer.wrap(primary).order(ByteOrder.LITTLE_ENDIAN)
            if (p.getLong(32) != lastLba) throw GptError("the backup table isn't at the end of the disk")
            val backup = tailSector(lastLba)
            checkHeader(backup, "backup", myLba = lastLba)
            val b = ByteBuffer.wrap(backup).order(ByteOrder.LITTLE_ENDIAN)

            val count = p.getInt(80)
            val size = p.getInt(84)
            if (count !in 1..1024 || size < 128 || size % 8 != 0) throw GptError("odd entry array ($count × $size)")
            if (b.getInt(80) != count || b.getInt(84) != size) throw GptError("primary and backup tables differ")
            // Usable range, disk GUID and entry checksum: both copies must share them.
            if (!primary.copyOfRange(40, 72).contentEquals(backup.copyOfRange(40, 72)) || p.getInt(88) != b.getInt(88)) {
                throw GptError("primary and backup tables differ")
            }
            val sectors = ((count.toLong() * size + sectorSize - 1) / sectorSize).toInt()
            val pArray = (0 until sectors).map { headSector(p.getLong(72) + it) }.reduce(ByteArray::plus)
            val bArray = (0 until sectors).map { tailSector(b.getLong(72) + it) }.reduce(ByteArray::plus)
            val crc = p.getInt(88)
            if (crc32(pArray, 0, count * size) != crc) throw GptError("primary entry array checksum is wrong")
            if (!pArray.copyOf(count * size).contentEquals(bArray.copyOf(count * size))) {
                throw GptError("primary and backup partition lists differ")
            }

            val entries = (0 until count).map { i ->
                val at = i * size
                val e = ByteBuffer.wrap(pArray, at, size).slice().order(ByteOrder.LITTLE_ENDIAN)
                // Plain array copies: ByteBuffer's absolute bulk get needs Android 15.
                val type = Guid(pArray.copyOfRange(at, at + 16))
                val guid = Guid(pArray.copyOfRange(at + 16, at + 32))
                val name = String(pArray.copyOfRange(at + 56, at + 128), Charsets.UTF_16LE).substringBefore('\u0000')
                Entry(i + 1, type, guid, e.getLong(32), e.getLong(40), e.getLong(48), name)
            }
            return Gpt(sectorSize, primary, backup, pArray, bArray, entries)
        }

        private fun checkHeader(h: ByteArray, which: String, myLba: Long) {
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getLong(0) != SIGNATURE) throw GptError("no $which partition table")
            val size = b.getInt(12)
            if (size < 92 || size > h.size) throw GptError("odd $which header size $size")
            val copy = h.copyOf(size)
            ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0)
            if (crc32(copy, 0, size) != b.getInt(16)) throw GptError("$which header checksum is wrong")
            if (b.getLong(24) != myLba) throw GptError("$which header is in the wrong place")
        }

        /** [header] with entry checksum [crc] and its own checksum recomputed. */
        private fun rehead(header: ByteArray, crc: Int): ByteArray {
            val h = header.copyOf()
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(88, crc)
            b.putInt(16, 0)
            b.putInt(16, crc32(h, 0, b.getInt(12)))
            return h
        }

        fun crc32(data: ByteArray, off: Int, len: Int): Int =
            CRC32().apply { update(data, off, len) }.value.toInt()
    }
}

class GptError(message: String) : Exception(message)

/** A GUID in its on-disk byte order (first three fields little-endian). */
class Guid(val bytes: ByteArray) {
    init { require(bytes.size == 16) }
    val isZero get() = bytes.all { it.toInt() == 0 }

    override fun equals(other: Any?) = other is Guid && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()

    override fun toString(): String {
        fun hex(range: IntProgression) = range.joinToString("") { "%02X".format(bytes[it]) }
        return "${hex(3 downTo 0)}-${hex(5 downTo 4)}-${hex(7 downTo 6)}-${hex(8..9)}-${hex(10..15)}"
    }

    companion object {
        fun parse(s: String): Guid {
            val h = s.replace("-", "")
            require(h.length == 32) { "bad GUID $s" }
            fun byte(i: Int) = h.substring(2 * i, 2 * i + 2).toInt(16).toByte()
            val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
            return Guid(ByteArray(16) { byte(order[it]) })
        }

        fun random(): Guid {
            val u = java.util.UUID.randomUUID()
            return parse(u.toString())
        }

        /** Microsoft basic data: the FAT partition the ROCKNIX boot menu boots from. */
        val BASIC_DATA = parse("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
        val LINUX = parse("0FC63DAF-8483-4772-8E79-3D69D8477DE4")
    }
}
