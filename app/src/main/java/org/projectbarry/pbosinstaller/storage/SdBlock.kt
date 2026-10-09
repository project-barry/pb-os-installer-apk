package org.projectbarry.pbosinstaller.storage

import java.io.File

/**
 * The microSD card as a disk: the mmcblk device whose type is SD (the Nova's
 * card is mmcblk1, not mmcblk0, and reports removable=0). Null when there is no
 * card or the system doesn't let apps read /sys/block.
 */
data class SdBlock(val name: String, val sizeBytes: Long) {
    companion object {
        fun find(): SdBlock? = runCatching {
            File("/sys/block").listFiles { f -> f.name.startsWith("mmcblk") }
                ?.firstOrNull { File(it, "device/type").readText().trim() == "SD" }
                ?.let { SdBlock(it.name, File(it, "size").readText().trim().toLong() * 512) }
        }.getOrNull()
    }
}
