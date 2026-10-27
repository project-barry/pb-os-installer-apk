package org.projectbarry.pbosinstaller.storage

import java.io.File

/**
 * The target disk: the microSD card, or (when no card is in) a USB drive, the sd*
 * disk on the USB bus (the handheld's own UFS storage is sd* too, so the bus
 * decides). The microSD card as a disk: the mmcblk device whose type is SD (the Nova's
 * card is mmcblk1, not mmcblk0, and reports removable=0). Null when there is no
 * card or the system doesn't let apps read /sys/block.
 */
data class SdBlock(val name: String, val sizeBytes: Long) {
    companion object {
        fun find(): SdBlock? = runCatching {
            val disks = File("/sys/block").listFiles().orEmpty()
            (disks.firstOrNull { it.name.startsWith("mmcblk") && File(it, "device/type").readText().trim() == "SD" }
                ?: disks.firstOrNull { it.name.startsWith("sd") && "/usb" in it.canonicalPath })
                ?.let { SdBlock(it.name, File(it, "size").readText().trim().toLong() * 512) }
        }.getOrNull()
    }
}
