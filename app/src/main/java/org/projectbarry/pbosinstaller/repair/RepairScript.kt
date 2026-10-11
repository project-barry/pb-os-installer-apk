package org.projectbarry.pbosinstaller.repair

import org.projectbarry.pbosinstaller.abl.AblScript
import org.projectbarry.pbosinstaller.root.ScriptKit

/**
 * Root scripts of Tools → Repair PB-OS.
 *
 * [checkBody] only reads. It looks for PB-OS on internal storage (the
 * partitions the kernel knows as ROCKNIX, STORAGE and HOME) and on the
 * microSD card (an MBR with FAT, Linux, Linux), mounts each read-only through
 * loop devices (never the partitions themselves: Android may have the card's
 * FAT mounted), and copies what the app judges into its folder: KERNEL,
 * KERNEL.md5, the version files, fstab, the updater's records and the
 * previous kernel an update keeps. It also says what each boot loader slot
 * holds.
 *
 * [kernelBody] replaces one install's KERNEL with a kernel the app checked
 * (the one from before the last update, or the same one re-pointed), keeping
 * the old one as KERNEL.bad, and reads it back.
 */
object RepairScript {
    const val CHECK = "repair-check"
    const val KERNEL = "repair-kernel"

    /** Where an install is; also the folder name of its findings. */
    enum class Place(val key: String) { Internal("internal"), Card("card") }

    data class CheckJob(
        val id: String,
        val statusFile: String,
        val outDir: String,
        val stock: List<AblScript.Known>,
        val rocknix: List<AblScript.Known>,
    )

    fun checkBody(job: CheckJob): String {
        val q = ScriptKit::quote
        return """
${ScriptKit.prelude(CHECK, job.id, job.statusFile)}
# Looks at every PB-OS install it can find; changes nothing.
OUT=${q(job.outDir)}
[ "${'$'}(id -u)" = 0 ] || fail "This script must be run as root."
status checking
${AblScript.identifyFunctions(job.stock, job.rocknix)}
$COMMON
MNT=/data/local/tmp/pbos-repair
LOOPS=
ro_loop() { # var device: sets var to a read-only loop device over it
  l=${'$'}(losetup -f -s -r "${'$'}2") || return 1
  LOOPS="${'$'}l ${'$'}LOOPS"
  eval "${'$'}1=\${'$'}l"
}
cleanup() {
  for m in boot root home; do umount "${'$'}MNT/${'$'}m" 2>/dev/null; done
  for l in ${'$'}LOOPS; do losetup -d "${'$'}l" 2>/dev/null; done
  LOOPS=
  rmdir "${'$'}MNT"/* "${'$'}MNT" 2>/dev/null
}
trap 'cleanup; on_exit' EXIT
# Copies what the app judges from one install: place, boot, system, home partitions.
inspect() {
  D="${'$'}OUT/${'$'}1"
  mkdir -p "${'$'}D" "${'$'}MNT/boot" "${'$'}MNT/root" "${'$'}MNT/home"
  { echo "boot_sector=${'$'}(od -A n -t u2 -j 11 -N 2 "${'$'}2" | tr -d ' ')"; } > "${'$'}D/info"
  if ro_loop B "${'$'}2" && mount -t vfat -o ro "${'$'}B" "${'$'}MNT/boot"; then
    echo boot_mounts=1 >> "${'$'}D/info"
    for f in KERNEL KERNEL.md5 KERNEL.bad; do [ -f "${'$'}MNT/boot/${'$'}f" ] && cp "${'$'}MNT/boot/${'$'}f" "${'$'}D/"; done
    umount "${'$'}MNT/boot"
  else
    echo boot_mounts=0 >> "${'$'}D/info"
  fi
  if ro_loop R "${'$'}3" && mount -t ext4 -o ro,noload "${'$'}R" "${'$'}MNT/root"; then
    echo root_mounts=1 >> "${'$'}D/info"
    for f in usr/share/pb-os/version usr/share/pb-os/base etc/fstab var/lib/konkr-update/pending; do
      [ -f "${'$'}MNT/root/${'$'}f" ] && cp "${'$'}MNT/root/${'$'}f" "${'$'}D/${'$'}(basename "${'$'}f")"
    done
    { [ -e "${'$'}MNT/root/sbin/init" ] || [ -L "${'$'}MNT/root/sbin/init" ]; } && echo init=1 >> "${'$'}D/info"
    umount "${'$'}MNT/root"
  else
    echo root_mounts=0 >> "${'$'}D/info"
  fi
  if ro_loop H "${'$'}4" && mount -t ext4 -o ro,noload "${'$'}H" "${'$'}MNT/home"; then
    echo home_mounts=1 >> "${'$'}D/info"
    for t in "${'$'}MNT"/home/.konkr-updates/*-*-*-*-*; do
      [ -d "${'$'}t" ] || continue
      T="${'$'}D/updates/${'$'}{t##*/}"
      mkdir -p "${'$'}T"
      [ -f "${'$'}t/state.json" ] && cp "${'$'}t/state.json" "${'$'}T/"
      [ -f "${'$'}t/transaction.json" ] &&
        grep -o '"version": *"[^"]*"' "${'$'}t/transaction.json" | head -1 > "${'$'}T/version"
      [ -f "${'$'}t/previous-KERNEL" ] && cp "${'$'}t/previous-KERNEL" "${'$'}T/"
      # What the updater's own rollback needs (see konkr-update.py recover/restore).
      { { [ -f "${'$'}t/backup/delta-backup.json" ] || [ -f "${'$'}t/backup/root-presence.json" ]; } && echo backup=1
        # python3 there is a symlink to an absolute path, valid inside the updater's chroot.
        [ -f "${'$'}t/recovery/updater.py" ] && { [ -e "${'$'}t/recovery/usr/bin/python3" ] || [ -L "${'$'}t/recovery/usr/bin/python3" ]; } &&
          echo recovery=1
      } > "${'$'}T/flags"
    done
    umount "${'$'}MNT/home"
  else
    echo home_mounts=0 >> "${'$'}D/info"
  fi
  [ -n "${'$'}5" ] && echo "card_disk_id=${'$'}5" >> "${'$'}D/info"
  find "${'$'}D" -exec chown "${'$'}OWNER" {} + 2>/dev/null
  chmod -R u+rwX,g+rwX "${'$'}D" 2>/dev/null
}

FOUND=
if [ -b /dev/block/by-name/ROCKNIX ] && [ -b /dev/block/by-name/STORAGE ] && [ -b /dev/block/by-name/HOME ]; then
  inspect internal /dev/block/by-name/ROCKNIX /dev/block/by-name/STORAGE /dev/block/by-name/HOME ""
  FOUND="${'$'}FOUND internal"
fi
if find_card; then
  b() { od -A n -t x1 -j "${'$'}1" -N 1 "/dev/block/${'$'}CARD" | tr -d ' '; }
  P1=/dev/block/${'$'}{CARD}p1 P2=/dev/block/${'$'}{CARD}p2 P3=/dev/block/${'$'}{CARD}p3
  case "${'$'}(b 450)${'$'}(b 466)${'$'}(b 482)" in
    0c8383|0b8383)
      if [ -b "${'$'}P1" ] && [ -b "${'$'}P2" ] && [ -b "${'$'}P3" ]; then
        inspect card "${'$'}P1" "${'$'}P2" "${'$'}P3" "${'$'}(od -A n -t x4 -j 440 -N 4 "/dev/block/${'$'}CARD" | tr -d ' ')"
        FOUND="${'$'}FOUND card"
      fi ;;
  esac
fi
# The boot loader slots (the same identification as the backup).
for s in a b; do
  if [ -e /dev/block/by-name/abl_${'$'}s ]; then
    echo "abl_${'$'}s=${'$'}(identify /dev/block/by-name/abl_${'$'}s)"
  fi
done > "${'$'}OUT/abl"
give "${'$'}OUT/abl"
EXTRA="found=${'$'}{FOUND# }"
status done
""".trimStart()
    }

    data class KernelJob(
        val id: String,
        val statusFile: String,
        val place: Place,
        /** The kernel to install, as the app checked it, and its SHA-256. */
        val kernel: String,
        val kernelSha256: String,
        /** What root= must say for this install. */
        val root: String,
        /** SHA-256 of the KERNEL the check saw: refuses if it changed since. */
        val currentSha256: String?,
        /**
         * Undo this update (its id under /home/.konkr-updates) with PB-OS's own
         * updater: before the kernel goes in, the update is marked
         * rollback-requested and pending, so the boot hook of whichever kernel
         * starts next restores the files it replaced (konkr-update.py recover).
         */
        val undoUpdate: String? = null,
    )

    val KERNEL_STATES = listOf("started", "checking", "unmounting", "writing", "verifying", "done", "failed")

    /** Stands for $ in the templates below (no other script part uses it). */
    private const val D = "\$"

    fun kernelBody(job: KernelJob): String {
        val q = ScriptKit::quote
        job.undoUpdate?.let { require(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(it)) { "bad update id" } }
        val parts = when (job.place) {
            Place.Internal -> """
BOOT=/dev/block/by-name/ROCKNIX SYSTEM=/dev/block/by-name/STORAGE HOMEP=/dev/block/by-name/HOME
[ -b "§BOOT" ] && [ -b "§SYSTEM" ] && [ -b "§HOMEP" ] || fail "PB-OS's partitions aren't on internal storage any more."
"""
            Place.Card -> """
find_card || fail "No microSD card found. Put the card back in and try again."
BOOT=/dev/block/§{CARD}p1 SYSTEM=/dev/block/§{CARD}p2 HOMEP=/dev/block/§{CARD}p3
[ -b "§BOOT" ] && [ -b "§SYSTEM" ] && [ -b "§HOMEP" ] || fail "The card doesn't have PB-OS's partitions."
status unmounting
$CARD_UNMOUNT
"""
        }.replace("§", D)
        return """
${ScriptKit.prelude(KERNEL, job.id, job.statusFile)}
# Replaces the KERNEL of PB-OS (${job.place.key}) with one the app checked; the old one stays as KERNEL.bad.
${if (job.undoUpdate != null) "# First the update ${job.undoUpdate} is marked for PB-OS's updater to undo at the next start." else ""}
SRC=${q(job.kernel)}
SRC_SHA=${q(job.kernelSha256)}
ROOT=${q(job.root)}
WANT_OLD=${q(job.currentSha256 ?: "")}
UNDO=${q(job.undoUpdate ?: "")}
# The replaced kernel's name. After an undo it belongs to the newer system, so it gets
# a name the repair check never offers back (putting it back would mix versions).
OLD=${if (job.undoUpdate != null) "KERNEL.before-undo" else "KERNEL.bad"}
MNT=/data/local/tmp/pbos-repair
[ "§(id -u)" = 0 ] || fail "This script must be run as root."
status checking
$COMMON
${ScriptKit.RETARGET_KERNEL}
[ "§(sha "§SRC")" = "§SRC_SHA" ] || fail "The kernel the app prepared is damaged. Check again."
$parts
LOOPS=
NEW=/data/local/tmp/pbos-repair-KERNEL
cleanup() {
  for m in boot system home; do umount "§MNT/§m" 2>/dev/null; done
  for l in §LOOPS; do losetup -d "§l" 2>/dev/null; done
  rmdir "§MNT"/* "§MNT" 2>/dev/null; rm -f "§NEW"
}
trap 'cleanup; on_exit' EXIT
# open name device ro|rw type: a loop device over the partition, mounted at MNT/name.
# Always a new loop device: Android detaches one when it's unmounted.
open() {
  if [ "§3" = ro ]; then l=§(losetup -f -s -r "§2"); else l=§(losetup -f -s "§2"); fi || return 1
  LOOPS="§l §LOOPS"
  mkdir -p "§MNT/§1" && mount -t "§4" -o "§3§{5:+,§5}" "§l" "§MNT/§1"
}
cp "§SRC" "§NEW" && retarget_kernel "§NEW" "§ROOT" || fail "Couldn't point the kernel at this install. Nothing was changed."
NEW_SHA=§(sha "§NEW")
open boot "§BOOT" rw vfat || fail "Couldn't open PB-OS's boot partition. Nothing was changed."
if [ -n "§WANT_OLD" ] && [ "§(sha "§MNT/boot/KERNEL" 2>/dev/null)" != "§WANT_OLD" ]; then
  fail "PB-OS's kernel changed since the check. Check again. Nothing was changed."
fi
${undoMark()}
status writing
# The old kernel stays next to it, so this can be undone by hand.
if [ -f "§MNT/boot/KERNEL" ]; then
  cp "§MNT/boot/KERNEL" "§MNT/boot/§OLD" || fail "Couldn't keep a copy of the old kernel.§UNDO_NOTE"
  [ -f "§MNT/boot/KERNEL.md5" ] && cp "§MNT/boot/KERNEL.md5" "§MNT/boot/§OLD.md5"
fi
cp "§NEW" "§MNT/boot/KERNEL.new" && sync && [ "§(sha "§MNT/boot/KERNEL.new")" = "§NEW_SHA" ] ||
  fail "Couldn't write the new kernel. The old one is unchanged.§UNDO_NOTE"
mv -f "§MNT/boot/KERNEL.new" "§MNT/boot/KERNEL" && (cd "§MNT/boot" && md5sum KERNEL > KERNEL.md5) ||
  fail "Couldn't put the new kernel in place. The old one is in §OLD.§UNDO_NOTE"
sync
status verifying
umount "§MNT/boot" "§MNT/system" "§MNT/home" 2>/dev/null
echo 3 > /proc/sys/vm/drop_caches
open boot "§BOOT" ro vfat || fail "Couldn't read the boot partition back. The new kernel is in place; the old one is in §OLD."
[ "§(sha "§MNT/boot/KERNEL")" = "§NEW_SHA" ] && (cd "§MNT/boot" && md5sum -c KERNEL.md5 >/dev/null) ||
  fail "The new kernel didn't read back correctly. The old one is in §OLD."
${undoCheck()}
status done
""".replace("§", D).trimStart()
    }

    /**
     * Marks the update for PB-OS's updater to undo: its state.json says
     * rollback-requested (HOME), and the pending marker names it (system). Both
     * in the updater's own formats; the boot hook (konkr-update-recover) then runs
     * the updater copy kept with the update, which restores every file it
     * replaced and its previous kernel. Only when the update is committed and
     * still has its backup and that updater copy.
     */
    private fun undoMark() = """
UNDO_NOTE=
if [ -n "§UNDO" ]; then
  open home "§HOMEP" rw ext4 noatime || fail "Couldn't open PB-OS's home partition. Nothing was changed."
  open system "§SYSTEM" rw ext4 noatime || fail "Couldn't open PB-OS's system partition. Nothing was changed."
  W="§MNT/home/.konkr-updates/§UNDO"
  P="§MNT/system/var/lib/konkr-update/pending"
  [ -d "§W" ] && [ "§(stat -c %u "§W")" = 0 ] || fail "The update's folder is gone. Nothing was changed."
  grep -q '"state": *"committed"' "§W/state.json" || fail "The update isn't in a state that can be undone. Nothing was changed."
  { [ -f "§W/backup/delta-backup.json" ] || [ -f "§W/backup/root-presence.json" ]; } && [ -f "§W/previous-KERNEL" ] &&
    [ -f "§W/recovery/updater.py" ] || fail "The update's backup is incomplete, so it can't be undone. Nothing was changed."
  [ ! -e "§P" ] || fail "Another update is waiting to be installed. Nothing was changed."
  status writing
  printf '{"state": "rollback-requested", "time": %s}\n' "§(date +%s)" > "§W/state.json.new" &&
    chown 0:0 "§W/state.json.new" && chmod 644 "§W/state.json.new" && mv -f "§W/state.json.new" "§W/state.json" ||
    fail "Couldn't mark the update to be undone. Nothing was changed."
  mkdir -p "§MNT/system/var/lib/konkr-update" && printf '%s\n' "§UNDO" > "§P" && chown 0:0 "§P" && chmod 600 "§P" ||
    fail "Couldn't mark the update to be undone. Its state now says rollback-requested; check again."
  sync
  UNDO_NOTE=" The update is marked to be undone at the next start."
fi
""".replace("§", D).trim()

    /** Reads the marks back. */
    private fun undoCheck() = """
if [ -n "§UNDO" ]; then
  open home "§HOMEP" ro ext4 noload && open system "§SYSTEM" ro ext4 noload ||
    fail "Couldn't read the update's marks back. The new kernel is in place."
  grep -q '"state": *"rollback-requested"' "§MNT/home/.konkr-updates/§UNDO/state.json" &&
    [ "§(cat "§MNT/system/var/lib/konkr-update/pending")" = "§UNDO" ] &&
    [ "§(stat -c %u%a "§MNT/system/var/lib/konkr-update/pending")" = 0600 ] ||
    fail "The update's marks didn't read back correctly."
fi
""".replace("§", D).trim()

    /** find_card: sets CARD to the mmcblk disk whose type is SD (not always mmcblk0). */
    private val COMMON = """
find_card() {
  CARD=
  for b in /sys/block/mmcblk*; do
    [ "${'$'}(cat "${'$'}b/device/type" 2>/dev/null)" = SD ] && CARD=${'$'}{b##*/}
  done
  [ -n "${'$'}CARD" ]
}
""".trim()

    /**
     * Android must let go of the card's partitions before one is written (it mounts
     * the FAT partition as a public volume). Same method as the card write.
     */
    private val CARD_UNMOUNT = """
CARD_MM=${'$'}(cat /sys/block/${'$'}CARD/dev)
for p in /sys/block/${'$'}CARD/${'$'}CARD*; do
  [ -r "${'$'}p/dev" ] && CARD_MM="${'$'}CARD_MM ${'$'}(cat "${'$'}p/dev")"
done
on_card() { case " ${'$'}CARD_MM " in *" ${'$'}1 "*) return 0 ;; esac; return 1; }
card_mounts() { while read -r _ _ mm _ mp _; do on_card "${'$'}mm" && echo "${'$'}mp"; done < /proc/self/mountinfo; }
for v in ${'$'}(sm list-volumes public 2>/dev/null | cut -d' ' -f1); do
  on_card "${'$'}(echo "${'$'}{v#public:}" | tr '_,' '::')" && sm unmount "${'$'}v"
done
n=0
while [ -n "${'$'}(card_mounts)" ] && [ ${'$'}n -lt 10 ]; do sleep 1; n=${'$'}((n + 1)); done
for m in ${'$'}(card_mounts); do umount "${'$'}m" 2>/dev/null; done
[ -z "${'$'}(card_mounts)" ] || fail "Android is still using the card. Eject it in Settings → Storage, then try again."
""".trim()
}
