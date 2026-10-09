package org.projectbarry.pbosinstaller.writer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DryRunScriptTest {
    /** Also produces build/dry-run.sh for on-device dry runs. */
    @Test fun writesDryRunScript() {
        DryRunScript.main(arrayOf(System.getProperty("pbos.cardBytes") ?: "512711720960"))
        val text = File("build/dry-run.sh").readText()
        assertTrue("DRY_RUN_TARGET='/dev/null'" in text)
    }
}
