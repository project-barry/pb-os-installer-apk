package org.projectbarry.pbosinstaller.abl

/** The charger and battery, before the Duo's boot loader is replaced (a power loss in the middle has no way back). */
object PowerCheck {
    const val MIN_PERCENT = 30

    /** What to do first, or null. Not being able to tell counts as not enough. */
    fun problem(plugged: Boolean, percent: Int?): String? = when {
        !plugged -> "Connect the charger first, and leave it in until this is done."
        percent == null || percent < MIN_PERCENT -> "Charge to at least $MIN_PERCENT % first, and leave the charger in until this is done."
        else -> null
    }
}
