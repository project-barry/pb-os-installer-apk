package org.projectbarry.pbosinstaller.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.projectbarry.pbosinstaller.internal.FakeDisk.Companion.GIB
import org.projectbarry.pbosinstaller.internal.FakeDisk.Part

class InternalLayoutTest {
    private val ss = 4096
    private val g = GIB / ss

    /** Android with [androidGib], then [names] splitting the rest of the disk (first two 512 MiB / 1 GiB like Armada). */
    private fun disk(androidGib: Long, names: List<String>?, bytes: Long = 476L shl 30): FakeDisk {
        val d = FakeDisk(bytes = bytes)
        val android = FakeDisk.android(ss, androidGib)
        val linux = mutableListOf<Part>()
        if (names != null) {
            var at = android.last().last + 1
            val sizes = listOf(g / 2, g)
            names.forEachIndexed { i, n ->
                val last = if (i == names.lastIndex) d.lastUsable else at + sizes[i] - 1
                linux += Part(n, at, last, if (i == 0) Guid.BASIC_DATA else Guid.LINUX)
                at = last + 1
            }
        }
        return d.write(android + linux)
    }

    private fun found(r: InternalLayout.Result) = (r as? InternalLayout.Result.Found)?.plan
        ?: throw AssertionError("expected Found, got $r")

    @Test fun parsesWhatItWrites() {
        val gpt = disk(64, InternalLayout.ARMADA_NAMES).gpt()
        assertEquals(listOf("ssd", "persist", "misc", "userdata") + InternalLayout.ARMADA_NAMES, gpt.partitions.map { it.name })
        assertEquals(32, gpt.entryCount)
        assertEquals(1, gpt.entrySectors)
    }

    @Test fun damagedTablesAreRefused() {
        val d = disk(64, InternalLayout.PBOS_NAMES)
        d.head[ss + 20] = (d.head[ss + 20] + 1).toByte()
        try { d.gpt(); fail() } catch (e: GptError) { assertTrue(e.message!!, "checksum" in e.message!!) }
        val e2 = runCatching { disk(64, InternalLayout.PBOS_NAMES).write(listOf(Part("userdata", 100, 200)), garbleBackup = true).gpt() }
        assertTrue(e2.exceptionOrNull() is GptError)
    }

    @Test fun nothingAfterAndroidIsRefused() {
        val r = InternalLayout.inspect(disk(400, null).gpt())
        assertTrue(r is InternalLayout.Result.Refused && r.kind == null)
    }

    @Test fun unknownPartitionsAreRefused() {
        val r = InternalLayout.inspect(disk(64, listOf("ROCKNIX", "STORAGE", "something")).gpt())
        assertTrue(r is InternalLayout.Result.Refused && "something" in r.reason)
    }

    @Test fun pbOsIsReusedAsItIs() {
        val gpt = disk(64, InternalLayout.PBOS_NAMES).gpt()
        // PB-OS's own layout: ROCKNIX 2 GiB, STORAGE 20 GiB, HOME the rest.
        val ud = gpt.partitions.first { it.name == "userdata" }
        val d = FakeDisk().write(
            FakeDisk.android(ss, 64) + listOf(
                Part("ROCKNIX", ud.lastLba + 1, ud.lastLba + 2 * g, Guid.BASIC_DATA),
                Part("STORAGE", ud.lastLba + 2 * g + 1, ud.lastLba + 22 * g),
                Part("HOME", ud.lastLba + 22 * g + 1, FakeDisk().lastUsable),
            )
        )
        val plan = found(InternalLayout.inspect(d.gpt()))
        assertEquals(InternalLayout.Kind.PbOs, plan.kind)
        assertNull(plan.newTable)
        assertEquals(listOf("ROCKNIX", "STORAGE", "HOME"), listOf(plan.boot, plan.storage, plan.home).map { it.name })
        assertEquals((ud.lastLba + 1) * ss, plan.bootArea.offset)
        assertEquals(20 * GIB, plan.storageArea.length)
    }

    @Test fun pbOsWithTooSmallStorageIsRefused() {
        // The Armada-sized split (512 MiB, 1 GiB, rest) under PB-OS names: STORAGE is too small.
        val r = InternalLayout.inspect(disk(64, InternalLayout.PBOS_NAMES).gpt())
        assertTrue(r is InternalLayout.Result.Refused && r.kind == InternalLayout.Kind.PbOs && "STORAGE" in r.reason)
    }

    @Test fun armadaBecomesPbOsInTheSameSpace() {
        val d = disk(64, InternalLayout.ARMADA_NAMES)
        val before = d.gpt()
        val plan = found(InternalLayout.inspect(before))
        assertEquals(InternalLayout.Kind.Armada, plan.kind)
        val table = plan.newTable!!
        d.apply(table)
        val after = d.gpt()   // parses: both copies and all checksums are right

        // Android's partitions: every byte of their entries unchanged.
        val oldArray = before.entryArrayBytes()
        val newArray = after.entryArrayBytes()
        before.entries.filter { it.name !in InternalLayout.ARMADA_NAMES }.forEach { e ->
            val r = (e.number - 1) * 128 until e.number * 128
            assertArrayEquals(e.name, oldArray.sliceArray(r), newArray.sliceArray(r))
        }
        val armada = before.partitions.filter { it.name in InternalLayout.ARMADA_NAMES }
        val pbos = after.partitions.filter { it.name in InternalLayout.PBOS_NAMES }
        assertEquals(InternalLayout.PBOS_NAMES, pbos.map { it.name })
        assertEquals(armada.map { it.number }, pbos.map { it.number })      // same slots
        assertEquals(armada.first().firstLba, pbos.first().firstLba)          // already MiB-aligned
        assertTrue(pbos.last().lastLba <= armada.last().lastLba)              // never past Armada's end
        assertEquals(2 * g, pbos[0].sectors)
        assertEquals(20 * g, pbos[1].sectors)
        assertEquals(pbos[0].lastLba + 1, pbos[1].firstLba)
        assertEquals(pbos[1].lastLba + 1, pbos[2].firstLba)
        assertEquals(Guid.BASIC_DATA, pbos[0].type)
        assertEquals(Guid.LINUX, pbos[2].type)
        // The headers are the old ones apart from the checksums.
        assertArrayEquals(before.primaryHeaderBytes().copyOfRange(24, 88), after.primaryHeaderBytes().copyOfRange(24, 88))
        // A second look at the result now finds PB-OS, reused as it is.
        assertNull(found(InternalLayout.inspect(after)).newTable)
    }

    @Test fun tooLittleLinuxSpaceIsRefused() {
        // 64 GiB disk, Android 32 GiB: ~30 GiB of Armada is below the 38 GiB PB-OS needs.
        val r = InternalLayout.inspect(disk(32, InternalLayout.ARMADA_NAMES, bytes = 64L shl 30).gpt())
        assertTrue(r is InternalLayout.Result.Refused && r.kind == InternalLayout.Kind.Armada && "at least" in r.reason)
        // Just enough: 38 GiB of Armada after Android.
        val ok = disk(16, InternalLayout.ARMADA_NAMES, bytes = 60L shl 30)
        val plan = found(InternalLayout.inspect(ok.gpt()))
        assertTrue(plan.homeArea.length >= InternalLayout.MIN_HOME_BYTES)
    }

    @Test fun uninstallLeftoversInsideUserdataAreIgnored() {
        val d = FakeDisk()
        val android = FakeDisk.android(ss, 64)
        val ud = android.last()
        val stale = Part("", ud.first + 10 * g, ud.first + 11 * g)
        val parts = android + listOf(stale) + listOf(
            Part("ARMADA", ud.last + 1, ud.last + g / 2, Guid.BASIC_DATA),
            Part("ARMADA_BOOT", ud.last + g / 2 + 1, ud.last + g * 3 / 2),
            Part("ARMADA_ROOT", ud.last + g * 3 / 2 + 1, d.lastUsable),
        )
        assertEquals(InternalLayout.Kind.Armada, found(InternalLayout.inspect(d.write(parts).gpt())).kind)
    }

    @Test fun guidTextRoundTrips() {
        val s = "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"
        assertEquals(s, Guid.parse(s).toString())
        // On-disk order: first three fields little-endian.
        assertEquals(0xA2.toByte(), Guid.parse(s).bytes[0])
    }
}
