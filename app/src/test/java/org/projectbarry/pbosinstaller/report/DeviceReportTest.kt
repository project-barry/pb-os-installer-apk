package org.projectbarry.pbosinstaller.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.device.DeviceInfo
import java.io.File

class DeviceReportTest {
    private val nova = DeviceInfo(
        "Moorechip", "qti", "Retroid Pocket Nova", "kalama", "kalama", "QTI", "QCS8550", "kalama", "13 (API 33)",
        "qti/kalama/kalama:13/TKQ1.231222.001/eng.RPN.20260722.081626:user/release-keys",
    )

    // Unit tests run in the app module; the relay lives next to it.
    private val worker = File("../relay/worker.js").readText()

    @Test fun fieldsMatchTheRelay() {
        val relayFields = Regex("const FIELDS = \\[([^\\]]*)\\]").find(worker)!!.groupValues[1]
            .let { Regex("'([a-z_]+)'").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(relayFields, DeviceReport.build(nova, "0.1.0", "mmcblk1", "none").keys.toList())
    }

    @Test fun valuesPassTheRelayCheck() {
        val pattern = Regex(Regex("const VALUE = /\\^(.*)\\$/;").find(worker)!!.groupValues[1].let { "^$it$" }.replace("\\/", "/"))
        val nasty = nova.copy(model = "@everyone <https://x.y> `code` " + "x".repeat(300), brand = "ünïcode™")
        DeviceReport.build(nasty, "0.1.0", null, "none").forEach { (k, v) ->
            assertTrue("$k=$v", pattern.matches(v))
        }
    }

    @Test fun novaReport() {
        val fields = DeviceReport.build(nova, "0.1.0", "mmcblk1", "none")
        assertEquals("Retroid Pocket Nova", fields["model"])
        assertEquals(nova.fingerprint, fields["fingerprint"])
        assertEquals("SD", fields["sd_type"])
        assertEquals("none", DeviceReport.build(nova, "0.1.0", null, "none")["sd_type"])
    }

    @Test fun cleanReplacesAndTrims() {
        assertEquals("_everyone _https://x.y_", DeviceReport.clean("@everyone <https://x.y>"))
        assertEquals(DeviceReport.MAX_LENGTH, DeviceReport.clean("a".repeat(500)).length)
    }
}
