package org.projectbarry.pbosinstaller.device

/** The chips pb-os has an image for. */
enum class Soc(val label: String) {
    SM8550("Snapdragon 8 Gen 2 (SM8550)"),
    SM8650("Snapdragon 8 Gen 3 (SM8650)"),
}

object SocCheck {
    /**
     * Which pb-os chip this is, or null if pb-os has no image for it.
     *
     * [socModel] is Build.SOC_MODEL / ro.soc.model. Retroid reports the QCS
     * name (the RP6 and Nova say "QCS8550"), others may report "SM8550".
     * [boardPlatform] (ro.board.platform) is only used when the model is
     * missing: kalama = SM8550, pineapple = SM8650.
     */
    fun identify(socModel: String?, boardPlatform: String?): Soc? {
        val model = socModel.orEmpty().trim().uppercase()
        if (model.isNotEmpty() && model != "UNKNOWN") {
            return when {
                model.startsWith("SM8550") || model.startsWith("QCS8550") -> Soc.SM8550
                model.startsWith("SM8650") || model.startsWith("QCS8650") -> Soc.SM8650
                else -> null
            }
        }
        return when (boardPlatform.orEmpty().trim().lowercase()) {
            "kalama" -> Soc.SM8550
            "pineapple" -> Soc.SM8650
            else -> null
        }
    }
}
