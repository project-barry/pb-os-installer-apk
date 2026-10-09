package org.projectbarry.pbosinstaller.device

import android.os.Build

/** What Android says about this handheld. Shown in the app so testers can send it to us. */
data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val socManufacturer: String,
    val socModel: String,
    val boardPlatform: String,
    val android: String,
) {
    val soc: Soc? get() = SocCheck.identify(socModel, boardPlatform)

    /** Plain text for the "Copy device info" button. */
    fun report(): String = """
        manufacturer=$manufacturer
        brand=$brand
        model=$model
        device=$device
        product=$product
        soc_manufacturer=$socManufacturer
        soc_model=$socModel
        board_platform=$boardPlatform
        android=$android
    """.trimIndent()

    companion object {
        /**
         * Debug builds only: pretend to be another device, to try every screen on an
         * emulator. adb shell am start -n org.projectbarry.pbosinstaller/.MainActivity
         *   --es fakeManufacturer Moorechip --es fakeModel "Retroid Pocket 6" --es fakeSoc QCS8550
         */
        var debugFake: DeviceInfo? = null

        fun read(): DeviceInfo = debugFake ?: DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            product = Build.PRODUCT,
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else getprop("ro.soc.manufacturer"),
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else getprop("ro.soc.model"),
            boardPlatform = getprop("ro.board.platform"),
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )

        private fun getprop(name: String): String = try {
            ProcessBuilder("getprop", name).start().inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) {
            ""
        }
    }
}
