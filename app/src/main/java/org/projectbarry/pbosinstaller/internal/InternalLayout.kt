package org.projectbarry.pbosinstaller.internal

/**
 * What is on the internal disk after Android's userdata, and how PB-OS goes
 * there. Only an existing PB-OS or ArmadaOS install is replaced, inside the
 * space it already has: userdata and every partition before it stay exactly as
 * they are, so Android keeps the size the user gave it.
 *
 * - PB-OS (ROCKNIX, STORAGE, HOME): the same three partitions are reused, the
 *   table is not written at all.
 * - ArmadaOS (ARMADA, ARMADA_BOOT, ARMADA_ROOT): its space becomes ROCKNIX
 *   2 GiB, STORAGE 20 GiB, HOME the rest, in the same three table slots (the
 *   layout PB-OS's own internal installer makes).
 */
object InternalLayout {
    const val MIB = 1L shl 20
    const val GIB = 1L shl 30
    const val BOOT_BYTES = 2 * GIB
    const val STORAGE_BYTES = 20 * GIB
    const val MIN_HOME_BYTES = 16 * GIB
    /** The smallest Linux space PB-OS goes into: boot, system and the smallest home. */
    const val MIN_LINUX_BYTES = BOOT_BYTES + STORAGE_BYTES + MIN_HOME_BYTES
    /** An existing PB-OS install's own partitions must be at least this big. */
    const val MIN_BOOT_BYTES = 256 * MIB
    const val MIN_STORAGE_BYTES = 18 * GIB

    val PBOS_NAMES = listOf("ROCKNIX", "STORAGE", "HOME")
    val ARMADA_NAMES = listOf("ARMADA", "ARMADA_BOOT", "ARMADA_ROOT")

    enum class Kind(val label: String) { PbOs("PB-OS"), Armada("ArmadaOS") }

    /** A byte range of the internal disk. */
    data class Area(val offset: Long, val length: Long)

    data class Plan(
        val kind: Kind,
        val gpt: Gpt,
        val userdata: Gpt.Entry,
        /** The partitions being replaced, as they are now. */
        val old: List<Gpt.Entry>,
        /** The three partitions after the install. */
        val boot: Gpt.Entry,
        val storage: Gpt.Entry,
        val home: Gpt.Entry,
        /** The table to write, or null when it stays as it is (PB-OS). */
        val newTable: Gpt?,
    ) {
        private fun area(e: Gpt.Entry) = Area(e.firstLba * gpt.sectorSize, e.sectors * gpt.sectorSize)
        val bootArea get() = area(boot)
        val storageArea get() = area(storage)
        val homeArea get() = area(home)
        val linuxBytes get() = old.sumOf { it.sectors } * gpt.sectorSize
        val androidBytes get() = userdata.sectors * gpt.sectorSize
    }

    sealed interface Result {
        data class Found(val plan: Plan) : Result
        /** Nothing PB-OS can go over; [kind] when it was recognised but doesn't fit. */
        data class Refused(val reason: String, val kind: Kind? = null) : Result
    }

    fun inspect(gpt: Gpt): Result {
        val ss = gpt.sectorSize
        val userdata = gpt.partitions.filter { it.name == "userdata" }
        if (userdata.size != 1) return Result.Refused("Couldn't find Android's userdata partition on this disk.")
        val ud = userdata.single()
        // Nameless entries inside userdata are what the boot menu's UNINSTALL CFW leaves
        // behind; nothing uses them.
        val after = gpt.partitions.filter { it.firstLba > ud.firstLba }
            .filterNot { it.name.isEmpty() && it.lastLba <= ud.lastLba }
        if (after.isEmpty()) {
            return Result.Refused(
                "There is no Linux install after Android on internal storage. To install PB-OS next to " +
                    "Android, use the microSD card first, then PB-OS's own internal installer."
            )
        }
        val names = after.map { it.name }
        val kind = when (names) {
            PBOS_NAMES -> Kind.PbOs
            ARMADA_NAMES -> Kind.Armada
            else -> return Result.Refused(
                "Internal storage has partitions after Android that the app doesn't know " +
                    "(${names.joinToString(", ") { it.ifEmpty { "unnamed" } }}). It only installs over PB-OS or ArmadaOS."
            )
        }
        if (after.first().firstLba <= ud.lastLba || after.zipWithNext().any { (a, b) -> b.firstLba <= a.lastLba }) {
            return Result.Refused("The partitions after Android overlap. Nothing was changed.", kind)
        }
        val linuxBytes = (after.last().lastLba - after.first().firstLba + 1) * ss
        val tooSmall = "The ${kind.label} part of internal storage is ${gb(linuxBytes)}; PB-OS needs at least " +
            "${gb(MIN_LINUX_BYTES)} there. Nothing was changed."

        return when (kind) {
            Kind.PbOs -> {
                val (boot, storage, home) = after
                when {
                    linuxBytes < MIN_LINUX_BYTES -> Result.Refused(tooSmall, kind)
                    boot.sectors * ss < MIN_BOOT_BYTES || storage.sectors * ss < MIN_STORAGE_BYTES ||
                        home.sectors * ss < MIN_HOME_BYTES -> Result.Refused(
                        "This PB-OS install's partitions are too small for the new version (ROCKNIX " +
                            "${gb(boot.sectors * ss)}, STORAGE ${gb(storage.sectors * ss)}, HOME " +
                            "${gb(home.sectors * ss)}; needed ${gb(MIN_BOOT_BYTES)}, ${gb(MIN_STORAGE_BYTES)}, " +
                            "${gb(MIN_HOME_BYTES)}). Nothing was changed.",
                        kind,
                    )
                    else -> Result.Found(Plan(kind, gpt, ud, after, boot, storage, home, newTable = null))
                }
            }
            Kind.Armada -> {
                val mib = MIB / ss
                val start = (after.first().firstLba + mib - 1) / mib * mib
                val end = (after.last().lastLba + 1) / mib * mib        // exclusive
                if ((end - start) * ss < MIN_LINUX_BYTES) return Result.Refused(tooSmall, kind)
                val slots = after.map { it.number }
                val bootEnd = start + BOOT_BYTES / ss
                val storageEnd = bootEnd + STORAGE_BYTES / ss
                val boot = Gpt.Entry(slots[0], Guid.BASIC_DATA, Guid.random(), start, bootEnd - 1, 0, "ROCKNIX")
                val storage = Gpt.Entry(slots[1], Guid.LINUX, Guid.random(), bootEnd, storageEnd - 1, 0, "STORAGE")
                val home = Gpt.Entry(slots[2], Guid.LINUX, Guid.random(), storageEnd, end - 1, 0, "HOME")
                Result.Found(Plan(kind, gpt, ud, after, boot, storage, home, gpt.with(listOf(boot, storage, home))))
            }
        }
    }

    /** Decimal GB, as the rest of the app shows sizes. */
    fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1e9)
}
