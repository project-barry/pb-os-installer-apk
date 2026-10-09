package org.projectbarry.pbosinstaller.device

/**
 * A handheld pb-os has been tested on.
 *
 * [manufacturer] and [model] must equal Build.MANUFACTURER and Build.MODEL
 * (case is ignored). [images] are the release image names for it, first
 * match wins (pb-os-<tag>-<image>.img.7z.001, ...).
 */
data class TestedDevice(
    val name: String,
    val soc: Soc,
    val manufacturer: String,
    val model: String,
    val images: List<String>,
)

object Devices {
    /** Not a real value yet: a device with this string can never match. */
    private const val PLACEHOLDER = "PLACEHOLDER"

    // Only devices pb-os was tested on (see the pb-os README). To add one, get
    // its strings from the app's "Copy device info" button.
    val tested = listOf(
        // Confirmed from getprop on stock Android 13: ro.soc.model QCS8550, ro.board.platform kalama.
        TestedDevice("Retroid Pocket 6", Soc.SM8550, "Moorechip", "Retroid Pocket 6", listOf("sm8550")),
        TestedDevice("Retroid Pocket Nova", Soc.SM8550, "Moorechip", "Retroid Pocket Nova", listOf("sm8550")),
        // Placeholders until someone copies the device info from the real device.
        TestedDevice("AYN Thor", Soc.SM8550, PLACEHOLDER, PLACEHOLDER, listOf("sm8550")),
        TestedDevice("KONKR Pocket FIT", Soc.SM8650, PLACEHOLDER, PLACEHOLDER, listOf("pocketfit")),
    )

    /** Image names to try for an untested device, by chip (only with ALLOW_UNTESTED). */
    val imagesBySoc = mapOf(
        Soc.SM8550 to listOf("sm8550"),
        Soc.SM8650 to listOf("pocketfit"),
    )

    fun find(info: DeviceInfo): TestedDevice? = tested.firstOrNull {
        it.manufacturer != PLACEHOLDER &&
            it.manufacturer.equals(info.manufacturer.trim(), ignoreCase = true) &&
            it.model.equals(info.model.trim(), ignoreCase = true) &&
            it.soc == info.soc
    }
}
