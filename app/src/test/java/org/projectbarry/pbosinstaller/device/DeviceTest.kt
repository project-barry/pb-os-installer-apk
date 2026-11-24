package org.projectbarry.pbosinstaller.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceTest {
    @Test fun socNames() {
        assertEquals(Soc.SM8550, SocCheck.identify("QCS8550", ""))
        assertEquals(Soc.SM8550, SocCheck.identify("SM8550", ""))
        assertEquals(Soc.SM8650, SocCheck.identify("SM8650", ""))
        assertEquals(Soc.SM8650, SocCheck.identify("sm8650-ac", ""))
        // Pocket FIT 8 Elite edition and older chips: no image.
        assertNull(SocCheck.identify("SM8750", "sun"))
        assertNull(SocCheck.identify("SM8250", "kona"))
    }

    /** The Duo's platform, sun, is the 8 Elite's too: only the model makes an SM7750. */
    @Test fun sm7750OnlyByModel() {
        assertEquals(Soc.SM7750, SocCheck.identify("CQ7790S", "sun"))
        assertEquals(Soc.SM7750, SocCheck.identify("cq7790s", ""))
        assertEquals(Soc.SM7750, SocCheck.identify("SM7750", "sun"))
        assertNull(SocCheck.identify("SM8750", "sun"))
        assertNull(SocCheck.identify("", "sun"))
        assertNull(SocCheck.identify("unknown", "sun"))
        assertNull(SocCheck.identify("SM7735", "sun"))
        assertNull(SocCheck.identify("CQ8750S", "sun"))
    }

    /** Untested handhelds never with the Duo's chip, in any build. */
    @Test fun chipsAndTheirImages() {
        assertEquals(setOf(Soc.SM8550, Soc.SM8650), Devices.imagesBySoc.keys)
    }

    @Test fun boardPlatformOnlyWhenModelMissing() {
        assertEquals(Soc.SM8550, SocCheck.identify("", "kalama"))
        assertEquals(Soc.SM8650, SocCheck.identify("unknown", "pineapple"))
        // A reported model wins over the platform.
        assertNull(SocCheck.identify("SM8750", "kalama"))
        assertNull(SocCheck.identify("", ""))
    }

    private fun info(manufacturer: String, model: String, soc: String) =
        DeviceInfo(manufacturer, "", model, "", "", "QTI", soc, "", "13")

    @Test fun rp6IsTested() {
        assertEquals("Retroid Pocket 6", Devices.find(info("Moorechip", "Retroid Pocket 6", "QCS8550"))?.name)
        assertEquals("RP6", Devices.find(info("Moorechip", "Retroid Pocket 6", "QCS8550"))?.shortName)
    }

    @Test fun novaIsTested() {
        assertEquals("Retroid Pocket Nova", Devices.find(info("Moorechip", "Retroid Pocket Nova", "QCS8550"))?.name)
        assertEquals("Nova", Devices.find(info("Moorechip", "Retroid Pocket Nova", "QCS8550"))?.shortName)
    }

    @Test fun pocketFitIsTested() {
        // No ro.soc.model on the Pocket FIT: the platform decides the chip.
        val kpf = DeviceInfo("AYANEO", "AYANEO", "Pocket FIT", "PocketFIT", "AYANEO_Pocket_FIT", "QTI", "", "pineapple", "14")
        assertEquals("KONKR Pocket FIT", Devices.find(kpf)?.name)
        assertNull(Devices.find(kpf.copy(socModel = "SM8750", boardPlatform = "sun")))
    }

    @Test fun thor() {
        val thor = DeviceInfo("AYN", "qti", "AYN Thor", "kalama", "kalama", "QTI", "QCS8550", "kalama", "13")
        assertEquals("AYN Thor", Devices.find(thor)?.name)
    }

    @Test fun duo() {
        // Stock Android 16: ro.product.manufacturer Moorechip, ro.soc.model CQ7790S, ro.board.platform sun.
        val duo = DeviceInfo("Moorechip", "", "Retroid Pocket Duo", "", "", "QTI", "CQ7790S", "sun", "16")
        assertEquals("Retroid Pocket Duo", Devices.find(duo)?.name)
        assertEquals("Duo", Devices.find(duo)?.shortName)
        assertEquals(listOf("pb-os"), Devices.find(duo)?.images)
        assertNull(Devices.find(duo.copy(socModel = "SM8750")))
        assertNull(Devices.find(duo.copy(socModel = "")))
        assertNull(Devices.find(duo.copy(model = "Retroid Pocket 6")))
    }

    @Test fun odin2FamilyIsSupportedNotTested() {
        // Android's maker/model on stock: AYN / Odin2, Odin2 Portal, Odin2 Mini (kalama).
        for ((model, name) in listOf("Odin2" to "AYN Odin 2", "Odin2 Portal" to "AYN Odin 2 Portal", "Odin2 Mini" to "AYN Odin 2 Mini")) {
            val d = Devices.find(DeviceInfo("AYN", "qti", model, "kalama", "kalama", "QTI", "QCS8550", "kalama", "13"))
            assertEquals(name, d?.name)
            assertEquals(false, d?.hardwareTested)
        }
        assertNull(Devices.find(info("AYN", "Odin2", "SM8650")))
    }

    @Test fun shortNames() {
        assertEquals(listOf("RP6", "Nova", "Duo", "Thor", "KPF"), Devices.tested.map { it.shortName })
    }

    @Test fun rightModelWrongChipIsNotTested() {
        assertNull(Devices.find(info("Moorechip", "Retroid Pocket 6", "SM8250")))
    }

    @Test fun placeholdersNeverMatch() {
        assertNull(Devices.find(info("PLACEHOLDER", "PLACEHOLDER", "SM8650")))
        assertNull(Devices.find(info("Moorechip", "Retroid Pocket 5", "SM8250")))
    }
}
