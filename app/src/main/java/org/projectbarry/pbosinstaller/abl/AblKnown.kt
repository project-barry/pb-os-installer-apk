package org.projectbarry.pbosinstaller.abl

import org.projectbarry.pbosinstaller.device.Soc
import org.projectbarry.pbosinstaller.release.ReleaseTag

/** Boot loaders the app knows. Stock ones are the makers' (never shipped, only their checksums). */
object AblKnown {
    data class Payload(val asset: String, val bytes: Long, val sha256: String, val label: String)

    /**
     * Project Barry's earlier fork build (github.com/project-barry/OpenABL release v1.2):
     * the Open Handheld Collective's OpenABL 1.1 plus a fix for an occasional freeze on
     * the logo at startup. Its menu says 1.2; their own 1.2 is another build, which the
     * app would call "OpenABL 1.2". Bundled unchanged until [OpenAblRelease.CURRENT] is
     * there (each file matches its .sha256 and GitHub's asset digest). 532480 bytes, so it
     * fits the 1 MiB abl partitions; the install script checks.
     */
    const val FORK_12 = "OpenABL 1.2 (PB-OS fork)"
    val fork12: Map<Soc, Payload> = mapOf(
        Soc.SM8550 to Payload("abl/openabl-1.2/abl_signed-SM8550.elf", 532480, "c7d681004563b69d892b5f93d3fac66a5f555c273c39ebf05febc72f95699df8", FORK_12),
        Soc.SM8650 to Payload("abl/openabl-1.2/abl_signed-SM8650.elf", 532480, "c32434666ad5c725caa149151cfc5a66918c40f05e3f692c93795481910a6422", FORK_12),
    )

    /**
     * Boot menus, by the SHA-256 of their first [AblScript.Known.bytes] bytes (the rest
     * of the partition keeps what was there before). OpenABL 1.0 for SM8550 is left
     * out: its release .sha256 file doesn't match its .elf. ([AblCatalog.menus] adds OpenABL-PB.)
     */
    val menus: Map<Soc, List<AblScript.Known>> = mapOf(
        Soc.SM8550 to listOf(
            AblScript.Known(FORK_12, "c7d681004563b69d892b5f93d3fac66a5f555c273c39ebf05febc72f95699df8", 532480),
            AblScript.Known("OpenABL 1.1", "cd97748bbd1c5dc994b8a73a05e88955297f4c88384e3ca4028d043fc1b16848", 532480),
            AblScript.Known("ROCKNIX 1.2", "8c27a70961476b327071ada2fd9b9c55dd2dcdc913eff3d33f2576133d7e398c", 258048),
            AblScript.Known("ROCKNIX 1.1.8", "5f1018211feaa109563d5890ab52dccfb31db90f4bbe9e6b7fb39e94f860f8d4", 258048),
        ),
        Soc.SM8650 to listOf(
            AblScript.Known(FORK_12, "c32434666ad5c725caa149151cfc5a66918c40f05e3f692c93795481910a6422", 532480),
            AblScript.Known("OpenABL 1.1", "8fd6a18ec30a8c24751b7db73a6ecb0d9df9f2e1432973273d572841ff3f6216", 532480),
            AblScript.Known("OpenABL 1.0", "23046d198dddaab53d7afc17bd726de4d2c65b6eb24d984a6b72e9a0963ba32c", 532480),
            AblScript.Known("ROCKNIX 1.2", "053e1e9e8300c29de68866c6c09e5527b1222d2ee774757d9cfbe8346137d6b6", 258048),
            AblScript.Known("ROCKNIX 1.1.8", "4ed04dda4da2ac76bc27925ae68094c055a043697f1a1bee4b958b979905bed5", 258048),
        ),
        Soc.SM7750 to emptyList(),
    )

    /** Stock boot loaders seen on our handhelds, by the SHA-256 of the whole 1 MiB partition. */
    private val stock8x50 = listOf(
        AblScript.Known("Retroid Pocket Nova (firmware 2026-07-22)", "39615e0d5d6302a512c30c78e49c50004d331de30a3e0b07f619ff5b979e280a", null),
        AblScript.Known("KONKR Pocket FIT (firmware 2025-12-26)", "739202be4e3f781d0a5e0321c5923f05b66da694f59ae90745e0054b2cb02537", null),
        AblScript.Known("AYN Thor (firmware 2026-02-06)", "dcb65f18445d99ede96f7d613931fa1fb1b19f30076a117c9c47df7ad967151d", null),
    )

    /** By chip. The Duo's two slots differ: slot b is the running firmware's, slot a the one from before its update. */
    val stock: Map<Soc, List<AblScript.Known>> = mapOf(
        Soc.SM8550 to stock8x50,
        Soc.SM8650 to stock8x50,
        Soc.SM7750 to listOf(
            AblScript.Known("Retroid Pocket Duo (older firmware)", "f96c4bdb2c1f554cc2a29fbd369406240bf5c34a92bc97b223936ad83061d4b7", null),
            AblScript.Known("Retroid Pocket Duo (firmware 1.0.0.157)", "291afdcf6acc7da034881c30b9bef70c3cbc260c33457a8b873d07cf4c96b4bd", null),
        ),
    )

    /** The [bundled] menu on both slots means there is nothing to install. */
    fun alreadyInstalled(slotA: String?, slotB: String?, bundled: String?): Boolean =
        bundled != null && slotA == AblScript.MENU + bundled && slotB == AblScript.MENU + bundled

    /** "OpenABL 1.1 boot menu (older version)": an OpenABL other than the [bundled] one is called older. */
    fun menuLabel(menu: String, bundled: String?): String =
        if (bundled != null && menu.startsWith("OpenABL") && menu != bundled) "$menu boot menu (older version)" else "$menu boot menu"

    /** "menu: OpenABL 1.1" → "OpenABL 1.1"; also reads "rocknix: …" from app versions before 0.4. */
    fun menuName(slot: String): String? = when {
        slot.startsWith(AblScript.MENU) -> slot.removePrefix(AblScript.MENU)
        slot.startsWith("rocknix: ") -> slot.removePrefix("rocknix: ")
        else -> null
    }
}

/**
 * Which boot menu each chip gets. With [release] (null until it's filled in, see
 * [OpenAblRelease.CURRENT]): that one, on every chip. Without it: the earlier fork
 * build on the SM8550 and SM8650, and nothing on the SM7750. A chip only ever gets
 * the file listed for it.
 */
class AblCatalog(private val release: OpenAblRelease?) {
    /** The boot menu the app installs on [soc], or null when it has none. */
    fun payload(soc: Soc): AblKnown.Payload? = if (release == null) AblKnown.fork12[soc] else release.files[soc]?.let {
        AblKnown.Payload("${release.folder}/abl_signed-${soc.name}.elf", it.bytes, it.sha256, release.label)
    }

    /** What the app calls the boot menu it installs on [soc], or null when it has none. */
    fun bundled(soc: Soc): String? = payload(soc)?.label

    /** The boot menus that can be on [soc], the one the app installs first. */
    fun menus(soc: Soc): List<AblScript.Known> =
        (listOfNotNull(payload(soc)?.let { AblScript.Known(it.label, it.sha256, it.bytes) }) + AblKnown.menus[soc].orEmpty()).distinct()

    fun stock(soc: Soc): List<AblScript.Known> = AblKnown.stock[soc].orEmpty()

    /**
     * Only replace boot loaders the app knows: the SM7750, where a failed flash has no way
     * back without Retroid's files. Elsewhere only a few stock ones are known yet.
     */
    fun strict(soc: Soc): Boolean = soc == Soc.SM7750

    /** The chips this version of the app has a boot menu for: "A, B or C". */
    val chips: String get() = Soc.entries.filter { payload(it) != null }.map { it.label }
        .let { it.dropLast(1).joinToString(", ") + " or " + it.last() }

    /**
     * Why the app won't replace [soc]'s boot loader, or null. [listed]: the handheld is
     * one PB-OS supports. The SM7750 build of OpenABL is for the Duo only, so any other
     * handheld with that chip is refused, in builds that allow untested ones too.
     */
    fun refusal(soc: Soc, listed: Boolean): String? = when {
        payload(soc) == null -> notReady(soc)
        soc == Soc.SM7750 && !listed -> "The boot menu for the ${soc.label} is only for the Retroid Pocket Duo. " +
            "The boot loader wasn't changed."
        else -> null
    }

    /** Why [soc] can't get PB-OS from this version of the app yet, or null: it has no boot menu for it. */
    fun notReady(soc: Soc?): String? = if (soc == null || payload(soc) != null) null else
        "This version of the app doesn't have the boot menu for the ${soc.label} yet, so PB-OS couldn't start on it. " +
            "Nothing was changed. A newer version of the app will have it."

    /**
     * Whether PB-OS release [tag] can start on [soc]. The Duo needs an image with Duo
     * support: the first one or a newer one, never a tag the app can't read. Other chips: any.
     */
    fun startsOn(soc: Soc?, tag: String): Boolean =
        soc != Soc.SM7750 || (release != null && ReleaseTag.atLeast(tag, release.firstDuoImage))

    companion object {
        val current = AblCatalog(OpenAblRelease.CURRENT)

        /** Shown before the Duo's boot loader is replaced. */
        const val DUO_WARNING =
            "If installing the boot menu fails on a Duo (for example, because it turns off in the middle), it may not " +
                "start any more, not even Android. Getting it back may then need Retroid's own recovery files, which the " +
                "app doesn't have. Keep it on the charger and don't turn it off until this is done."
    }
}
