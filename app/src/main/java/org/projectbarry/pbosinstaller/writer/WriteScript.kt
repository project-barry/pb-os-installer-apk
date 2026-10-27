package org.projectbarry.pbosinstaller.writer

import org.projectbarry.pbosinstaller.root.FirstUpdate
import org.projectbarry.pbosinstaller.root.ScriptKit

/**
 * The root script that writes the image to the microSD card. It starts with
 * [ScriptKit.prelude] (runs from /data/local/tmp in the background, reports
 * through [Job.statusFile]) and refuses to write unless every check passes.
 */
object WriteScript {
    data class Job(
        val id: String,
        /** 7-Zip (7zzs) shipped in the app's native library folder. */
        val sevenZip: String,
        /** All image parts in order; 7-Zip opens the rest from the first. */
        val parts: List<Pair<String, String>>,
        /** SHA-256 of the unpacked image, from the signed SHA256SUMS. */
        val imageSha256: String,
        /** The card's size as the app saw it, or null when the app couldn't read it. */
        val cardBytes: Long?,
        val statusFile: String,
        /** Test only: write here instead of the card, and skip unmounting it. */
        val dryRunTarget: String? = null,
        /** The newest patch, installed by PB-OS on its first start ([FirstUpdate]). */
        val firstUpdate: FirstUpdate.Files? = null,
    )

    /** Status file states, in order. */
    val STATES = listOf("started", "checking", "unmounting", "writing", "verifying", "done", "failed")

    const val NAME = "write"

    fun body(job: Job): String {
        require(job.parts.isNotEmpty())
        val q = ScriptKit::quote
        val partChecks = job.parts.joinToString("\n") { (path, sha) ->
            """check_part ${q(path)} ${q(sha)}"""
        }
        return """
${ScriptKit.prelude(NAME, job.id, job.statusFile, dryRun = job.dryRunTarget != null)}
# Writes PB-OS to the microSD card. Reads through /data/media/0, not /sdcard: if
# Android's system server restarts, vold kills processes with files open under
# /storage/emulated.
SEVENZIP=${q(job.sevenZip)}
PART1=${q(job.parts.first().first)}
IMAGE_SHA=${q(job.imageSha256)}
CARD_BYTES=${q(job.cardBytes?.toString() ?: "")}
DRY_RUN_TARGET=${q(job.dryRunTarget ?: "")}
CHECKSUM=/data/local/tmp/pbos-write.sha
[ -n "${'$'}DRY_RUN_TARGET" ] && CHECKSUM=${'$'}STATUS.sha
check_part() { # path sha256
  [ "${'$'}(sha "${'$'}1")" = "${'$'}2" ] || fail "A downloaded part is damaged. Download PB-OS again."
}
ppid() { sed -n 's/^PPid:[[:space:]]*//p' /proc/${'$'}1/status 2>/dev/null; }
# The process called $1 in the background pipeline $2. Depending on how the shell
# builds the pipeline, $! is that process itself, a subshell that is its parent, or
# another process of the same pipeline (its sibling, with the same parent).
find_proc() {
  [ "${'$'}(cat /proc/${'$'}2/comm 2>/dev/null)" = "${'$'}1" ] && { echo ${'$'}2; return; }
  parent=${'$'}(ppid ${'$'}2)
  for p in ${'$'}(pidof "${'$'}1"); do
    pp=${'$'}(ppid ${'$'}p)
    if [ "${'$'}pp" = "${'$'}2" ] || [ "${'$'}pp" = "${'$'}parent" ]; then echo ${'$'}p; return; fi
  done
}
# Bytes a process has written (dd) or read (sha256 check) so far.
io_bytes() { sed -n "s/^${'$'}2: //p" /proc/${'$'}1/io 2>/dev/null; }

[ -n "${'$'}DRY_RUN_TARGET" ] || [ "${'$'}(id -u)" = 0 ] || fail "This script must be run as root."

# The card: the mmcblk disk whose type is SD (not mmcblk0 on every handheld). With
# no card in, a USB drive: an sd disk on the USB bus (the handheld's own UFS
# storage is sd too, so the bus decides, never the name).
DEV=
for b in /sys/block/mmcblk*; do
  [ "${'$'}(cat "${'$'}b/device/type" 2>/dev/null)" = SD ] && DEV=${'$'}{b##*/}
done
if [ -z "${'$'}DEV" ]; then
  for b in /sys/block/sd*; do
    case "${'$'}(readlink -f "${'$'}b")" in */usb*) DEV=${'$'}{b##*/} ;; esac
  done
fi
[ -n "${'$'}DEV" ] || fail "No microSD card or USB drive found. Put it back in and try again."
# Byte counts: multiplied with expr (64-bit), compared with ge (never $(( )) or -ge).
SIZE=${'$'}(expr "${'$'}(cat /sys/block/${'$'}DEV/size)" '*' 512)
if [ -n "${'$'}CARD_BYTES" ] && [ "${'$'}SIZE" != "${'$'}CARD_BYTES" ]; then
  fail "This isn't the card the app checked. Open the app and start again."
fi
# Never the handheld's own storage: nothing the system runs from may be on the card.
if grep -E "^/dev/block/${'$'}DEV[^ ]* (/|/system|/vendor|/data|/metadata) " /proc/mounts >/dev/null; then
  fail "The system is running from this card. Refusing to write."
fi

status checking
# The image's own size: the first "Size =" after the "----------" line (the lines
# above it describe the split archive).
IMAGE_BYTES=${'$'}("${'$'}SEVENZIP" l -slt "${'$'}PART1" 2>/dev/null | sed -n '/^----------/,${'$'} s/^Size = //p' | head -1)
[ -n "${'$'}IMAGE_BYTES" ] || fail "The downloaded image can't be opened. Download PB-OS again."
ge "${'$'}SIZE" "${'$'}IMAGE_BYTES" || fail "The card is too small for PB-OS."
$partChecks
${job.firstUpdate?.files.orEmpty().joinToString("\n") { (path, sha) -> "check_part ${q(path)} ${q(sha)}" }}

if [ -z "${'$'}DRY_RUN_TARGET" ]; then
  TARGET=/dev/block/${'$'}DEV
  status unmounting
  # The card and its partitions as major:minor, e.g. "179:0 179:1". Mounts are found
  # by these numbers, never by name: Android names a volume public:179,1 on some
  # versions and public:179_1 on others (Android 14 on the KONKR Pocket FIT).
  CARD_MM=${'$'}(cat /sys/block/${'$'}DEV/dev)
  for p in /sys/block/${'$'}DEV/${'$'}DEV*; do
    [ -r "${'$'}p/dev" ] && CARD_MM="${'$'}CARD_MM ${'$'}(cat "${'$'}p/dev")"
  done
  on_card() { case " ${'$'}CARD_MM " in *" ${'$'}1 "*) return 0 ;; esac; return 1; }
  card_mounts() { # mount points backed by the card, from /proc/self/mountinfo
    while read -r _ _ mm _ mp _; do on_card "${'$'}mm" && echo "${'$'}mp"; done < /proc/self/mountinfo
  }
  # Let vold unmount its volumes: it also stops the apps using them. A lazy unmount
  # is not enough: the old filesystem stays alive and writes into the new image.
  for v in ${'$'}(sm list-volumes public 2>/dev/null | cut -d' ' -f1); do
    on_card "${'$'}(echo "${'$'}{v#public:}" | tr '_,' '::')" && sm unmount "${'$'}v"
  done
  n=0
  while [ -n "${'$'}(card_mounts)" ] && [ ${'$'}n -lt 10 ]; do sleep 1; n=${'$'}((n + 1)); done
  for m in ${'$'}(card_mounts); do umount "${'$'}m" 2>/dev/null; done
  [ -z "${'$'}(card_mounts)" ] || fail "Android is still using the card. Eject it in Settings → Storage, then try again."
else
  TARGET=${'$'}DRY_RUN_TARGET
fi

# Writing gigabytes faster than a card can take them fills memory with unwritten
# data and stalls the whole system (Android's watchdog then restarts it). Keep at
# most 64 MB unwritten while writing; restore the handheld's settings afterwards.
if [ -z "${'$'}DRY_RUN_TARGET" ]; then
  VM=/proc/sys/vm
  OLD_RATIO=${'$'}(cat ${'$'}VM/dirty_ratio) OLD_BG_RATIO=${'$'}(cat ${'$'}VM/dirty_background_ratio)
  OLD_BYTES=${'$'}(cat ${'$'}VM/dirty_bytes) OLD_BG_BYTES=${'$'}(cat ${'$'}VM/dirty_background_bytes)
  restore_vm() {
    if [ "${'$'}OLD_BYTES" != 0 ]; then echo "${'$'}OLD_BYTES" > ${'$'}VM/dirty_bytes; else echo "${'$'}OLD_RATIO" > ${'$'}VM/dirty_ratio; fi
    if [ "${'$'}OLD_BG_BYTES" != 0 ]; then echo "${'$'}OLD_BG_BYTES" > ${'$'}VM/dirty_background_bytes; else echo "${'$'}OLD_BG_RATIO" > ${'$'}VM/dirty_background_ratio; fi
  }
  trap 'restore_vm; on_exit' EXIT
  echo 16777216 > ${'$'}VM/dirty_background_bytes
  echo 67108864 > ${'$'}VM/dirty_bytes
fi

status writing 0 "${'$'}IMAGE_BYTES"
"${'$'}SEVENZIP" e -so -bd "${'$'}PART1" 2>/dev/null | dd of="${'$'}TARGET" bs=4194304 2>/dev/null &
PIPE=${'$'}!
DD=
while kill -0 ${'$'}PIPE 2>/dev/null; do
  [ -n "${'$'}DD" ] || DD=${'$'}(find_proc dd ${'$'}PIPE)
  W=${'$'}(io_bytes "${'$'}DD" wchar)
  status writing "${'$'}{W:-0}" "${'$'}IMAGE_BYTES"
  sleep 2
done
wait ${'$'}PIPE || fail "Writing to the card failed. Check the card and try again."
sync

if [ -z "${'$'}DRY_RUN_TARGET" ]; then
  status verifying 0 "${'$'}IMAGE_BYTES"
  echo 3 > /proc/sys/vm/drop_caches
  head -c "${'$'}IMAGE_BYTES" "${'$'}TARGET" | sha256sum > "${'$'}CHECKSUM" &
  CHECK=${'$'}!
  HEAD=
  while kill -0 ${'$'}CHECK 2>/dev/null; do
    [ -n "${'$'}HEAD" ] || HEAD=${'$'}(find_proc head ${'$'}CHECK)
    R=${'$'}(io_bytes "${'$'}HEAD" rchar)
    status verifying "${'$'}{R:-0}" "${'$'}IMAGE_BYTES"
    sleep 2
  done
  [ "${'$'}(cut -d' ' -f1 "${'$'}CHECKSUM")" = "${'$'}IMAGE_SHA" ] || fail "The card didn't read back correctly. Try another card."
  rm -f "${'$'}CHECKSUM"
  # No partition re-read here: Android would mount PB-OS's partitions read-write
  # and change them. The handheld picks the new partitions up on the next boot.
fi

${firstUpdateBlock(job)}
status done "${'$'}IMAGE_BYTES" "${'$'}IMAGE_BYTES"
""".trimStart()
    }

    /**
     * Mounts the card's system and home (partitions 2 and 3 of the image's MBR,
     * through loop devices: the kernel still has the card's old partitions) and
     * puts the update in. Never fails the job; the outcome goes in first_update=.
     */
    private fun firstUpdateBlock(job: Job): String {
        val update = job.firstUpdate ?: return ""
        if (job.dryRunTarget != null) return ""
        return FirstUpdate.block(update) + "\n" + """
first_update() {
  b() { od -A n -t x1 -j "${'$'}1" -N 1 "${'$'}TARGET" | tr -d ' '; }
  w() { od -A n -t u4 -j "${'$'}1" -N 4 "${'$'}TARGET" | tr -d ' '; }
  [ "${'$'}(b 466)${'$'}(b 482)" = 8383 ] || return 1
  L2=${'$'}(losetup -f -s -o ${'$'}(expr "${'$'}(w 470)" '*' 512) -S ${'$'}(expr "${'$'}(w 474)" '*' 512) "${'$'}TARGET") || return 1
  L3=${'$'}(losetup -f -s -o ${'$'}(expr "${'$'}(w 486)" '*' 512) -S ${'$'}(expr "${'$'}(w 490)" '*' 512) "${'$'}TARGET") || { losetup -d "${'$'}L2"; return 1; }
  M=/data/local/tmp/pbos-first-update
  mkdir -p "${'$'}M/root" "${'$'}M/home"
  OK=1
  if mount -t ext4 -o rw,noatime "${'$'}L2" "${'$'}M/root"; then
    if mount -t ext4 -o rw,noatime "${'$'}L3" "${'$'}M/home"; then
      place_update "${'$'}M/root" "${'$'}M/home" || OK=0
      umount "${'$'}M/home"
    else OK=0; fi
    umount "${'$'}M/root"
  else OK=0; fi
  losetup -d "${'$'}L3" 2>/dev/null; losetup -d "${'$'}L2" 2>/dev/null
  rmdir "${'$'}M/root" "${'$'}M/home" "${'$'}M" 2>/dev/null
  [ "${'$'}OK" = 1 ]
}
if first_update; then U="first_update=placed"; else U="first_update=failed"; fi
EXTRA="${'$'}{EXTRA:+${'$'}EXTRA
}${'$'}U"
""".replace("${'$'}", "${'$'}")
    }

    fun quote(s: String): String = ScriptKit.quote(s)
}
