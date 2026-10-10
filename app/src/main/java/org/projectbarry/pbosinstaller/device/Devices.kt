package org.projectbarry.pbosinstaller.device

/**
 * A handheld pb-os has been tested on.
 *
 * [manufacturer] and [model] must equal Build.MANUFACTURER and Build.MODEL
 * (case is ignored). [images] are the release image names for it, first
 * match wins (pb-os-<tag>-<image>.img.7z.001, ...). [shortName] is what the app
 * calls the handheld in its text ("Your Nova is supported").
 */
data class TestedDevice(
    val name: String,
    val shortName: String,
    val soc: Soc,
    val manufacturer: String,
    val model: String,
    val images: List<String>,
)

object Devices {
    /** Not a real value yet: a device with this string can never match. */
    private const val PLACEHOLDER = "PLACEHOLDER"

    // Only devices pb-os was tested on (see the pb-os README). To add one, get
    // its strings from a device report ("Send Device Report", or "Copy device info" in
    // the "What's in the Report?" pop-up).
    val tested = listOf(
        // Confirmed from getprop on stock Android 13: ro.soc.model QCS8550, ro.board.platform kalama.
        TestedDevice("Retroid Pocket 6", "RP6", Soc.SM8550, "Moorechip", "Retroid Pocket 6", listOf("sm8550")),
        TestedDevice("Retroid Pocket Nova", "Nova", Soc.SM8550, "Moorechip", "Retroid Pocket Nova", listOf("sm8550")),
        // getprop on stock Android 13: ro.soc.model QCS8550, ro.board.platform kalama.
        TestedDevice("AYN Thor", "Thor", Soc.SM8550, "AYN", "AYN Thor", listOf("sm8550")),
        // Android calls it AYANEO "Pocket FIT" (getprop on stock Android 14); it reports no
        // ro.soc.model, only the platform (pineapple). The 8 Elite edition (SM8750) is refused.
        TestedDevice("KONKR Pocket FIT", "KPF", Soc.SM8650, "AYANEO", "Pocket FIT", listOf("pocketfit")),
        // Not tested yet, so not listed: AYANEO Pocket S2 would be "S2" (SM8650, image "pocketfit").
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
