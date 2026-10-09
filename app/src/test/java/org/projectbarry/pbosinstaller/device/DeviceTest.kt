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
    }

    @Test fun novaIsTested() {
        assertEquals("Retroid Pocket Nova", Devices.find(info("Moorechip", "Retroid Pocket Nova", "QCS8550"))?.name)
    }

    @Test fun rightModelWrongChipIsNotTested() {
        assertNull(Devices.find(info("Moorechip", "Retroid Pocket 6", "SM8250")))
    }

    @Test fun placeholdersNeverMatch() {
        assertNull(Devices.find(info("PLACEHOLDER", "PLACEHOLDER", "SM8650")))
        assertNull(Devices.find(info("Moorechip", "Retroid Pocket 5", "SM8250")))
    }
}
