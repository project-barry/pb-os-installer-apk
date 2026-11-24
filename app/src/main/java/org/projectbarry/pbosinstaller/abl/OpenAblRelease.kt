package org.projectbarry.pbosinstaller.abl

import org.projectbarry.pbosinstaller.device.Soc
import org.projectbarry.pbosinstaller.release.ReleaseTag

/**
 * OpenABL-PB, Project Barry's build of OpenABL: the same version for every chip
 * (its number is the OpenABL version it is based on). All of it or nothing: the
 * app knows no half-filled release.
 */
data class OpenAblRelease(
    /** What its menu and the app call it: "OpenABL-PB 1.2". */
    val label: String,
    /** Each chip's abl_signed-<chip>.elf: size and SHA-256. */
    val files: Map<Soc, Binary>,
    /** Tag of the first PB-OS feature release a Retroid Pocket Duo can start: the first image with Duo support. */
    val firstDuoImage: String,
) {
    data class Binary(val bytes: Long, val sha256: String)

    /** Where the files are in the app's assets: "abl/openabl-pb-1.2". */
    val folder: String get() = "abl/" + label.lowercase().replace(' ', '-')

    init {
        require(Regex("OpenABL-PB \\d+(\\.\\d+)+").matches(label)) { "label: $label" }
        require(files.keys == Soc.entries.toSet()) { "every chip needs its file" }
        require(files.values.all { it.bytes in 1..1048576 && Regex("[0-9a-f]{64}").matches(it.sha256) }) { "sizes and checksums" }
        require(files.values.map { it.sha256 }.toSet().size == files.size) { "two chips with the same file" }
        require(ReleaseTag.parse(firstDuoImage) != null) { "firstDuoImage: $firstDuoImage" }
        require(!ReleaseTag.atLeast("alpha-v0.5.4", firstDuoImage)) { "alpha-v0.5.4 has no Duo support" }
    }

    companion object {
        /**
         * The release in this app; a new one is filled in here:
         *
         *  1. Put the three files, unchanged, in app/src/main/assets/abl/<folder>/ and delete the
         *     folder of the release before (add it to AblKnown, so the app still recognizes it).
         *  2. Set the values below.
         *  3. abl-payloads.pin (repo root): the same three sizes and checksums, taken from the machine
         *     that built the files, not from the files in step 1; compare with pb-os's abl/payloads.pin
         *     (PBOS_PAYLOADS_PIN=<pb-os>/abl/payloads.pin ./gradlew testDebugUnitTest).
         *  4. docs/LICENSES.md and README.md (The boot menu).
         *
         * The unit tests check the files against these values. Null: no OpenABL-PB, and a Duo is refused.
         */
        val CURRENT: OpenAblRelease? = OpenAblRelease(
            label = "OpenABL-PB 1.2",
            files = mapOf(
                Soc.SM8550 to Binary(532480, "8059e73aec1d6349fdd58ccab60d1f003cecf07c0b0a378108200e66254c242a"),
                Soc.SM8650 to Binary(532480, "3ad73fd935d2a95f5128f3f4f74c6bc297f548a2dc62d2fabb71198752233f01"),
                Soc.SM7750 to Binary(532480, "125448c6a8b8d24b60dca7868ce53782b3ca114f0d4f3f1044856afc2579f52d"),
            ),
            firstDuoImage = "alpha-v0.5.5",
        )
    }
}
