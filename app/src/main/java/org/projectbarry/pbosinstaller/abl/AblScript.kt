package org.projectbarry.pbosinstaller.abl

import org.projectbarry.pbosinstaller.root.ScriptKit

/**
 * Root scripts for the boot loader (ABL): back up both slots, then install the
 * ROCKNIX ABL (the boot menu that starts PB-OS from the card).
 *
 * The ABL lives in the abl_a and abl_b partitions on the handheld's own storage.
 * A wrong ABL can stop the handheld from starting, so both scripts check the
 * handheld, the files and every write.
 */
object AblScript {
    const val BACKUP = "abl-backup"
    const val INSTALL = "abl-install"

    /** Known boot loaders: SHA-256 of what identifies them, and what to call them. */
    data class Known(val label: String, val sha256: String, val bytes: Long?)

    data class BackupJob(
        val id: String,
        val statusFile: String,
        /** Where the copies go (the app's folder, as root sees it). */
        val backupDir: String,
        /** ro.board.platform the app checked (kalama, pineapple). */
        val platform: String,
        /** Written into info.txt next to the copies. */
        val info: List<String>,
        /** Stock boot loaders, by the SHA-256 of the whole partition. */
        val stock: List<Known>,
        /** ROCKNIX boot loaders, by the SHA-256 of their first [Known.bytes] bytes. */
        val rocknix: List<Known>,
    )

    data class InstallJob(
        val id: String,
        val statusFile: String,
        val platform: String,
        /** The ROCKNIX ABL to install, its size and SHA-256. */
        val payload: String,
        val payloadBytes: Long,
        val payloadSha256: String,
        /** The backup the user saved: both slots must still match it. */
        val backupDir: String,
    )

    private val q = ScriptKit::quote

    private fun checks(platform: String) = """
[ "${'$'}(id -u)" = 0 ] || fail "This script must be run as root."
[ "${'$'}(getprop ro.board.platform)" = ${q(platform)} ] || fail "This isn't the handheld the app checked."
for s in a b; do
  [ -e /dev/block/by-name/abl_${'$'}s ] || fail "The boot loader partition abl_${'$'}s wasn't found."
done
"""

    fun backupBody(job: BackupJob): String {
        val stock = job.stock.joinToString("\n") { "    ${q(it.sha256)}) echo ${q("stock: " + it.label)};;" }
        val rocknix = job.rocknix.joinToString("\n") {
            "  [ \"\$(head -c ${it.bytes} \"\$1\" | sha)\" = ${q(it.sha256)} ] && { echo ${q("rocknix: " + it.label)}; return; }"
        }
        val info = job.info.joinToString("\n") { "  echo ${q(it)}" }
        return """
${ScriptKit.prelude(BACKUP, job.id, job.statusFile)}
# Backs up the boot loader (both ABL slots) and says what each slot holds.
DIR=${q(job.backupDir)}
${checks(job.platform)}
# What a boot loader image is: a known stock one, a ROCKNIX version, or unknown.
identify() {
  case "${'$'}(sha "${'$'}1")" in
$stock
    *) identify_rocknix "${'$'}1";;
  esac
}
identify_rocknix() {
$rocknix
  echo unknown
}

status backing-up
mkdir -p "${'$'}DIR" && give "${'$'}DIR"
chmod 770 "${'$'}DIR"
for s in a b; do
  P=/dev/block/by-name/abl_${'$'}s
  F="${'$'}DIR/abl_${'$'}s.img"
  dd if="${'$'}P" of="${'$'}F" bs=1048576 2>/dev/null || fail "Couldn't read the boot loader (slot ${'$'}s)."
  sync
  # The copy must match the partition, read again.
  [ "${'$'}(sha "${'$'}F")" = "${'$'}(sha "${'$'}P")" ] || fail "The backup of slot ${'$'}s didn't match. Nothing was changed."
  give "${'$'}F"
  EXTRA="${'$'}EXTRA${'$'}{EXTRA:+
}slot_${'$'}s=${'$'}(identify "${'$'}P")
sha_${'$'}s=${'$'}(sha "${'$'}F")"
done
{
$info
  echo "date=${'$'}(date '+%Y-%m-%d %H:%M:%S %z')"
  printf '%s\n' "${'$'}EXTRA"
} > "${'$'}DIR/info.txt" && give "${'$'}DIR/info.txt"
status done
""".trimStart()
    }

    fun installBody(job: InstallJob): String = """
${ScriptKit.prelude(INSTALL, job.id, job.statusFile)}
# Installs the ROCKNIX ABL in both slots, slot b first. If slot a can't be written
# correctly, the backed-up original goes back into it, so the handheld still starts.
PAYLOAD=${q(job.payload)}
PAYLOAD_BYTES=${q(job.payloadBytes.toString())}
PAYLOAD_SHA=${q(job.payloadSha256)}
DIR=${q(job.backupDir)}
${checks(job.platform)}
[ "${'$'}(sha "${'$'}PAYLOAD")" = "${'$'}PAYLOAD_SHA" ] || fail "The boot menu file is damaged. Reinstall the app."
for s in a b; do
  [ -f "${'$'}DIR/abl_${'$'}s.img" ] || fail "The boot loader backup is missing. Back it up again."
  # Only install over exactly what was backed up.
  [ "${'$'}(sha "${'$'}DIR/abl_${'$'}s.img")" = "${'$'}(sha /dev/block/by-name/abl_${'$'}s)" ] ||
    fail "The boot loader changed since the backup. Back it up again."
  ge "${'$'}(blockdev --getsize64 /dev/block/by-name/abl_${'$'}s)" "${'$'}PAYLOAD_BYTES" || fail "The boot loader partition is too small."
done
written() { [ "${'$'}(head -c "${'$'}PAYLOAD_BYTES" "${'$'}1" | sha)" = "${'$'}PAYLOAD_SHA" ]; }

status installing 0 2
dd if="${'$'}PAYLOAD" of=/dev/block/by-name/abl_b bs=1048576 conv=fsync 2>/dev/null
sync
written /dev/block/by-name/abl_b || {
  dd if="${'$'}DIR/abl_b.img" of=/dev/block/by-name/abl_b bs=1048576 conv=fsync 2>/dev/null; sync
  fail "Installing the boot menu didn't work, so the original was put back. Nothing else was changed."
}
status installing 1 2
dd if="${'$'}PAYLOAD" of=/dev/block/by-name/abl_a bs=1048576 conv=fsync 2>/dev/null
sync
written /dev/block/by-name/abl_a || {
  dd if="${'$'}DIR/abl_a.img" of=/dev/block/by-name/abl_a bs=1048576 conv=fsync 2>/dev/null; sync
  dd if="${'$'}DIR/abl_b.img" of=/dev/block/by-name/abl_b bs=1048576 conv=fsync 2>/dev/null; sync
  fail "Installing the boot menu didn't work, so the originals were put back. Nothing else was changed."
}
status done 2 2
""".trimStart()
}
