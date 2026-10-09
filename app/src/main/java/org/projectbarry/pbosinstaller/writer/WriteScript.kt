package org.projectbarry.pbosinstaller.writer

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

# The card: the mmcblk disk whose type is SD (not mmcblk0 on every handheld).
DEV=
for b in /sys/block/mmcblk*; do
  [ "${'$'}(cat "${'$'}b/device/type" 2>/dev/null)" = SD ] && DEV=${'$'}{b##*/}
done
[ -n "${'$'}DEV" ] || fail "No microSD card found. Put the card back in and try again."
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

if [ -z "${'$'}DRY_RUN_TARGET" ]; then
  TARGET=/dev/block/${'$'}DEV
  status unmounting
  # Unmount only this card's volumes (same major number, minors of this disk).
  DMIN=${'$'}(cut -d: -f2 /sys/block/${'$'}DEV/dev)
  for v in ${'$'}(sm list-volumes public 2>/dev/null | cut -d' ' -f1); do
    mm=${'$'}{v#public:}; maj=${'$'}{mm%,*}; min=${'$'}{mm#*,}
    [ "${'$'}maj" = 179 ] && [ "${'$'}min" -ge "${'$'}DMIN" ] && [ "${'$'}min" -lt ${'$'}((DMIN + 8)) ] && sm unmount "${'$'}v"
  done
  sleep 2
  for m in ${'$'}(grep -E "^/dev/block/(vold/public:179,|${'$'}DEV)" /proc/mounts | cut -d' ' -f2); do umount -l "${'$'}m"; done
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
  trap 'restore_vm; rm -f "${'$'}RUN_COPY"' EXIT
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

status done "${'$'}IMAGE_BYTES" "${'$'}IMAGE_BYTES"
""".trimStart()
    }

    fun quote(s: String): String = ScriptKit.quote(s)
}
