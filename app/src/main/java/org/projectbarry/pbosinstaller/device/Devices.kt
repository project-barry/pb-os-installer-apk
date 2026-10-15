package org.projectbarry.pbosinstaller.device

/**
 * A handheld the app installs pb-os on.
 *
 * [manufacturer] and [model] must equal Build.MANUFACTURER and Build.MODEL
 * (case is ignored). [images] are the release image names for it, first
 * match wins (pb-os-<tag>-<image>.img.7z.001, ...): "pb-os", the one image
 * for every device since alpha v0.5.3, then the chip's image before it.
 * [shortName] is what the app
 * calls the handheld in its text ("Your Nova is supported").
 * [hardwareTested] is false for a handheld pb-os supports (its own DTB,
 * input and charging setup) but nobody has run it on one yet: the app
 * installs it and says so.
 */
data class SupportedDevice(
    val name: String,
    val shortName: String,
    val soc: Soc,
    val manufacturer: String,
    val model: String,
    val images: List<String>,
    val hardwareTested: Boolean = true,
)

object Devices {
    /** The one image for every device (alpha v0.5.3 and later). */
    private const val PB_OS = "pb-os"

    /** Not a real value yet: a device with this string can never match. */
    private const val PLACEHOLDER = "PLACEHOLDER"

    // Devices pb-os supports (see the pb-os README). To add one, get its strings
    // from a device report ("Send Device Report", or "Copy device info" in the
    // "What's in the Report?" pop-up).
    val supported = listOf(
        // Confirmed from getprop on stock Android 13: ro.soc.model QCS8550, ro.board.platform kalama.
        SupportedDevice("Retroid Pocket 6", "RP6", Soc.SM8550, "Moorechip", "Retroid Pocket 6", listOf(PB_OS, "sm8550")),
        SupportedDevice("Retroid Pocket Nova", "Nova", Soc.SM8550, "Moorechip", "Retroid Pocket Nova", listOf(PB_OS, "sm8550")),
        // getprop on stock Android 13: ro.soc.model QCS8550, ro.board.platform kalama.
        SupportedDevice("AYN Thor", "Thor", Soc.SM8550, "AYN", "AYN Thor", listOf(PB_OS, "sm8550")),
        // Android calls it AYANEO "Pocket FIT" (getprop on stock Android 14); it reports no
        // ro.soc.model, only the platform (pineapple). The 8 Elite edition (SM8750) is refused.
        SupportedDevice("KONKR Pocket FIT", "KPF", Soc.SM8650, "AYANEO", "Pocket FIT", listOf(PB_OS, "pocketfit")),
        // Supported, never run on one yet. Android's maker and model from the stock
        // builds (Odin 2 1.0.0.387, Portal 20250312, Mini 2024-06-26): odm/vendor
        // build.prop, which property_source_order=odm,vendor,... puts first.
        SupportedDevice("AYN Odin 2", "Odin 2", Soc.SM8550, "AYN", "Odin2", listOf(PB_OS), hardwareTested = false),
        SupportedDevice("AYN Odin 2 Portal", "Odin 2 Portal", Soc.SM8550, "AYN", "Odin2 Portal", listOf(PB_OS), hardwareTested = false),
        SupportedDevice("AYN Odin 2 Mini", "Odin 2 Mini", Soc.SM8550, "AYN", "Odin2 Mini", listOf(PB_OS), hardwareTested = false),
        // Not tested yet, so not listed: AYANEO Pocket S2 would be "S2" (SM8650, image "pocketfit").
    )

    /** The ones pb-os has run on. */
    val tested get() = supported.filter { it.hardwareTested }

    /** Image names to try for an untested device, by chip (only with ALLOW_UNTESTED). */
    val imagesBySoc = mapOf(
        Soc.SM8550 to listOf(PB_OS, "sm8550"),
        Soc.SM8650 to listOf(PB_OS, "pocketfit"),
    )

    fun find(info: DeviceInfo): SupportedDevice? = supported.firstOrNull {
        it.manufacturer != PLACEHOLDER &&
            it.manufacturer.equals(info.manufacturer.trim(), ignoreCase = true) &&
            it.model.equals(info.model.trim(), ignoreCase = true) &&
            it.soc == info.soc
    }
}
