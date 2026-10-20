package org.projectbarry.pbosinstaller.repair

import org.json.JSONObject
import org.projectbarry.pbosinstaller.abl.AblKnown
import org.projectbarry.pbosinstaller.abl.AblScript
import java.io.File
import java.security.MessageDigest

/**
 * What the repair check found ([RepairScript.checkBody] copies the files, this
 * judges them): per PB-OS install a list of findings, and the fixes that fit.
 */
data class RepairReport(val installs: List<Install>, val bootMenu: List<Finding>, val fixes: List<Fix>) {
    enum class Level { Ok, Note, Problem }

    data class Finding(val level: Level, val text: String)

    /** One update the updater recorded on HOME. */
    data class Update(
        val id: String,
        val state: String?,
        val version: String?,
        val time: Double,
        val previousKernel: File?,
        /** The updater's backup of what it replaced, and its own copy for the boot hook: what an undo needs. */
        val backup: Boolean = false,
        val recovery: Boolean = false,
    )

    data class Install(
        val place: RepairScript.Place,
        val version: String?,
        val findings: List<Finding>,
        /** What root= must say here. */
        val root: String?,
        /** SHA-256 of the KERNEL as the check saw it. */
        val kernelSha256: String?,
    ) {
        val title get() = when (place) {
            RepairScript.Place.Internal -> "PB-OS on internal storage"
            RepairScript.Place.Card -> "PB-OS on the microSD card"
        } + (version?.let { " ($it)" } ?: "")
        val healthy get() = findings.none { it.level == Level.Problem }
    }

    sealed interface Fix {
        val label: String
        val explain: String

        /**
         * Undo the last update with PB-OS's own updater: the update is marked
         * rollback-requested, and its previous kernel goes in, so at the next start
         * the updater restores every file it replaced (from its backup on HOME).
         */
        data class UndoUpdate(val install: Install, val update: Update, val kernel: File) : Fix {
            override val label get() = "Undo Update ${update.version ?: ""}".trimEnd()
            override val explain get() =
                "Goes back to the PB-OS you had before ${update.version ?: "the last update"}, for when it stopped " +
                    "starting after that update. On the next start PB-OS's updater puts back every file the update " +
                    "changed, kernel included (its screen says it is restoring the previous version). Games, saves " +
                    "and settings stay. The current kernel is kept as KERNEL.before-undo."
        }

        /** The same kernel, pointed at this install. */
        data class Repoint(val install: Install, val kernel: File) : Fix {
            override val label get() = "Point the Kernel at This Install"
            override val explain get() =
                "The kernel looks for PB-OS in the wrong place. This changes only where it looks (root=${install.root}); " +
                    "the current kernel is kept as KERNEL.bad."
        }

        /** Undo an earlier kernel repair: KERNEL.bad back in place (the current kernel becomes KERNEL.bad). */
        data class Undo(val install: Install, val kernel: File) : Fix {
            override val label get() = "Put Back the Kernel from Before the Repair"
            override val explain get() =
                "Swaps the kernels back: the one an earlier repair replaced (KERNEL.bad) goes in again, and the " +
                    "current one becomes KERNEL.bad."
        }

        /** The boot menu (OpenABL), through the installer's backup → 5 taps → install steps. */
        data object BootMenu : Fix {
            override val label get() = "Reinstall the Boot Menu"
            override val explain get() =
                "Backs up the boot loader first, then installs the OpenABL boot menu (the same steps as installing)."
        }
    }

    companion object {
        /** Reads the check's folder: [out]/abl, [out]/internal/…, [out]/card/…. [found]: the status's found= list. */
        fun read(out: File, found: List<String>): RepairReport {
            val installs = mutableListOf<Install>()
            val fixes = mutableListOf<Fix>()
            for (place in RepairScript.Place.entries) {
                if (place.key !in found) continue
                val (install, installFixes) = judge(place, File(out, place.key))
                installs += install
                fixes += installFixes
            }
            val (menu, menuFix) = bootMenu(File(out, "abl"))
            return RepairReport(installs, menu, fixes + listOfNotNull(menuFix))
        }

        private fun info(dir: File): Map<String, String> = runCatching {
            File(dir, "info").readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }.toMap()
        }.getOrDefault(emptyMap())

        private fun text(f: File): String? = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

        fun judge(place: RepairScript.Place, dir: File): Pair<Install, List<Fix>> {
            val i = info(dir)
            val findings = mutableListOf<Finding>()
            val fixes = mutableListOf<Fix>()
            val version = text(File(dir, "version"))
            val root = when (place) {
                RepairScript.Place.Internal -> "PARTLABEL=STORAGE"
                RepairScript.Place.Card -> i["card_disk_id"]?.takeIf { Regex("[0-9a-f]{8}").matches(it) }?.let { "PARTUUID=$it-02" }
            }
            fun ok(t: String) { findings += Finding(Level.Ok, t) }
            fun note(t: String) { findings += Finding(Level.Note, t) }
            fun problem(t: String) { findings += Finding(Level.Problem, t) }

            // The boot partition and its kernel.
            val kernelFile = File(dir, "KERNEL")
            val kernelBytes = runCatching { kernelFile.readBytes() }.getOrNull()
            val kernel = kernelBytes?.let(BootImage::parse)
            when {
                i["boot_mounts"] != "1" -> problem("The boot partition can't be read.")
                kernelBytes == null -> problem("The boot partition has no kernel (KERNEL is missing).")
                kernel == null || !kernel.idOk -> problem("The kernel is damaged.")
                else -> {
                    val md5 = text(File(dir, "KERNEL.md5"))?.substringBefore(' ')
                    if (md5 != null && md5 != hash("MD5", kernelBytes)) note("KERNEL.md5 doesn't match the kernel.")
                    if (root != null && kernel.root != root) {
                        problem("The kernel looks for PB-OS in the wrong place (root=${kernel.root}).")
                    } else {
                        ok("Kernel looks whole and points at this install.")
                    }
                }
            }
            if (place == RepairScript.Place.Internal && i["boot_sector"] != null && i["boot_sector"] != "4096") {
                note("The boot partition uses ${i["boot_sector"]}-byte sectors, so PB-OS can't mount it to update its kernel.")
            }
            val badFile = File(dir, "KERNEL.bad")
            val bad = runCatching { BootImage.parse(badFile.readBytes()) }.getOrNull()
            if (badFile.isFile) note("A kernel replaced by an earlier repair is kept as KERNEL.bad.")

            // System and home.
            when {
                i["root_mounts"] != "1" -> problem("The system partition can't be read.")
                i["init"] != "1" -> problem("The system is incomplete.")
                text(File(dir, "fstab")) == null -> problem("The system has no fstab, so it can't mount its partitions.")
                else -> ok("System: ${version ?: "unknown version"}.")
            }
            if (i["home_mounts"] != "1") problem("The home partition (games and files) can't be read.")
            text(File(dir, "pending"))?.let { note("An update is waiting to be installed on the next start.") }

            // The updater's records: the newest update decides.
            val updates = File(dir, "updates").listFiles().orEmpty().filter { it.isDirectory }.map { d ->
                val state = runCatching { JSONObject(File(d, "state.json").readText()) }.getOrNull()
                val v = text(File(d, "version"))?.let { Regex("\"version\": *\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
                val flags = runCatching { File(d, "flags").readLines() }.getOrDefault(emptyList())
                Update(d.name, state?.optString("state"), v, state?.optDouble("time", 0.0) ?: 0.0,
                    File(d, "previous-KERNEL").takeIf { it.isFile }, "backup=1" in flags, "recovery=1" in flags)
            }.sortedByDescending { it.time }
            val last = updates.firstOrNull()
            when (last?.state) {
                null -> {}
                "applying", "rolling-back", "rollback-requested" ->
                    problem("Update ${last.version ?: ""} stopped halfway; PB-OS finishes or undoes it on its next start.")
                "committed" -> note("Last update: ${last.version ?: "unknown"} installed.")
                "rolled-back" -> note("Last update: ${last.version ?: "unknown"} was undone.")
                else -> {}
            }

            val sha = kernelBytes?.let { hash("SHA-256", it) }
            val install = Install(place, version, findings, root, sha)
            // The last update can be undone while the updater still has everything for it
            // (it deletes the backup once Game Mode ran), and no other update is waiting.
            if (last?.state == "committed" && last.previousKernel != null && last.backup && last.recovery &&
                text(File(dir, "pending")) == null
            ) {
                val prev = runCatching { BootImage.parse(last.previousKernel.readBytes()) }.getOrNull()
                if (prev != null && prev.idOk && root != null) fixes += Fix.UndoUpdate(install, last, last.previousKernel)
            }
            if (kernel != null && kernel.idOk && root != null && kernel.root != root) fixes += Fix.Repoint(install, kernelFile)
            if (bad != null && bad.idOk && root != null) fixes += Fix.Undo(install, badFile)
            return install to fixes
        }

        /** Both slots must hold a boot menu (OpenABL, or ROCKNIX's from earlier installs). */
        fun bootMenu(file: File): Pair<List<Finding>, Fix?> {
            val slots = runCatching { file.readLines() }.getOrDefault(emptyList())
                .mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }.toMap()
            if (slots.isEmpty()) return listOf(Finding(Level.Note, "Couldn't read the boot loader.")) to null
            val findings = slots.toSortedMap().map { (slot, what) ->
                val name = "Slot ${slot.removePrefix("abl_").uppercase()}"
                val menu = AblKnown.menuName(what)
                when {
                    menu != null -> Finding(Level.Ok, "$name: $menu boot menu.")
                    what.startsWith("stock: ") -> Finding(Level.Problem, "$name: the original boot loader (no boot menu). An Android update may have put it back.")
                    else -> Finding(Level.Problem, "$name: a boot loader the app doesn't know.")
                }
            }
            // A working older menu (ROCKNIX's) can still be switched to the bundled one.
            val older = findings.none { it.level == Level.Problem } &&
                slots.values.any { it != AblScript.MENU + AblKnown.BUNDLED }
            val notes = if (older) {
                listOf(Finding(Level.Note, "${AblKnown.BUNDLED} can replace this boot menu."))
            } else {
                emptyList()
            }
            return findings + notes to if (older || findings.any { it.level == Level.Problem }) Fix.BootMenu else null
        }

        fun hash(algorithm: String, b: ByteArray): String =
            MessageDigest.getInstance(algorithm).digest(b).joinToString("") { "%02x".format(it) }
    }
}
