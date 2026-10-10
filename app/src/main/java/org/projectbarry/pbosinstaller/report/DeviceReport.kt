package org.projectbarry.pbosinstaller.report

import org.projectbarry.pbosinstaller.device.DeviceInfo
import java.io.File

/**
 * What "Send Device Report" sends. Built from a fixed list of fields that are
 * the same on every unit of a model: no serial numbers, accounts, network or
 * location data. The relay (relay/worker.js) accepts exactly these fields.
 */
object DeviceReport {
    /** Characters the relay accepts; anything else becomes "_". */
    private val UNSAFE = Regex("[^A-Za-z0-9 ._:/()+,=-]")
    const val MAX_LENGTH = 120

    fun clean(value: String): String = value.replace(UNSAFE, "_").take(MAX_LENGTH)

    /** Field name to value, in the order the relay posts them. */
    fun build(info: DeviceInfo, appVersion: String, sdBlock: String?, root: String): LinkedHashMap<String, String> =
        linkedMapOf(
            "app_version" to appVersion,
            "manufacturer" to info.manufacturer,
            "brand" to info.brand,
            "model" to info.model,
            "device" to info.device,
            "product" to info.product,
            "soc_manufacturer" to info.socManufacturer,
            "soc_model" to info.socModel,
            "board_platform" to info.boardPlatform,
            "android" to info.android,
            "fingerprint" to info.fingerprint,
            "sd_block" to (sdBlock ?: "none"),
            "sd_type" to (if (sdBlock != null) "SD" else "none"),
            "root" to root,
        ).mapValuesTo(LinkedHashMap()) { clean(it.value) }

    fun text(fields: Map<String, String>): String = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }

    /**
     * Which root helper the device has, by looking for it only: running `su`
     * could pop up a Magisk prompt. xsu = the KONKR/AYANEO vendor helper,
     * pservice = Retroid's root service.
     */
    fun rootKind(): String = when {
        File("/product/bin/xsu").exists() -> "xsu"
        org.projectbarry.pbosinstaller.root.PServer.present() -> "pservice"
        SU_PATHS.any { File(it).exists() } -> "su"
        else -> "none"
    }

    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/system_ext/bin/su")
}
