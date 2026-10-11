package org.projectbarry.pbosinstaller.internal

import org.projectbarry.pbosinstaller.root.FirstUpdate
import org.projectbarry.pbosinstaller.root.ScriptKit

/**
 * The root scripts for installing over a Linux install on internal storage.
 *
 * [probeBody] only reads: it finds the disk Android's userdata is on and saves
 * its first and last MiB (the partition table and its backup) and the kernel's
 * view of its partitions, for the app to look at ([InternalLayout]).
 *
 * [installBody] writes PB-OS into the Linux space the app planned, never
 * outside it. Android's partitions stay mounted the whole time, so nothing is
 * written through partition nodes: loop devices over the disk at the planned
 * byte ranges instead (the kernel keeps its old view of the partitions until
 * the next boot). Steps:
 *  1. checks: the table is still exactly what the app read, nothing in the
 *     Linux space is in use, the download is intact, the image fits;
 *  2. the image is unpacked into STORAGE and read back against the signed
 *     checksum (from here on the old install is gone);
 *  3. ROCKNIX is formatted (FAT32 in the disk's own sector size, by the image's
 *     own mkfs.fat) and gets the
 *     image's boot files, its KERNEL pointed at root=PARTLABEL=STORAGE;
 *  4. the image's home is copied into HOME (unless an existing PB-OS HOME is
 *     kept), then its system to the start of STORAGE (always to a lower
 *     address, so nothing is overwritten before it is read), each checked
 *     against its source;
 *  5. fstab mounts by partition name, and both filesystems grow on first boot;
 *  6. only for ArmadaOS: the new partition table, backup copy first, read back.
 */
object InternalScript {
    const val PROBE = "internal-probe"
    const val INSTALL = "internal-install"

    data class ProbeJob(val id: String, val statusFile: String, val outDir: String)

    fun probeBody(job: ProbeJob): String {
        val q = ScriptKit::quote
        return """
${ScriptKit.prelude(PROBE, job.id, job.statusFile)}
OUT=${q(job.outDir)}
[ "${'$'}(id -u)" = 0 ] || fail "This script must be run as root."
status checking
$FIND_DISK
SS=${'$'}(cat /sys/block/${'$'}DISK/queue/logical_block_size)
SECTORS=${'$'}(cat /sys/block/${'$'}DISK/size)
BYTES=${'$'}(expr "${'$'}SECTORS" '*' 512)
TAIL_N=${'$'}(expr 1048576 / "${'$'}SS")
mkdir -p "${'$'}OUT"
dd if="${'$'}DEV" of="${'$'}OUT/head.bin" bs=1048576 count=1 2>/dev/null || fail "Couldn't read internal storage."
dd if="${'$'}DEV" of="${'$'}OUT/tail.bin" bs="${'$'}SS" skip=${'$'}(expr "${'$'}BYTES" / "${'$'}SS" - "${'$'}TAIL_N") count="${'$'}TAIL_N" 2>/dev/null ||
  fail "Couldn't read internal storage."
# The kernel's partitions: number, start and size (512-byte units), in use (mounted or held).
for p in /sys/block/${'$'}DISK/${'$'}DISK*; do
  [ -r "${'$'}p/partition" ] || continue
  used=0
  in_use "${'$'}(cat "${'$'}p/dev")" "${'$'}p" && used=1
  echo "${'$'}(cat "${'$'}p/partition") ${'$'}(cat "${'$'}p/start") ${'$'}(cat "${'$'}p/size") ${'$'}used"
done > "${'$'}OUT/parts.txt"
give "${'$'}OUT/head.bin" "${'$'}OUT/tail.bin" "${'$'}OUT/parts.txt"
EXTRA="disk=${'$'}DISK
sector=${'$'}SS
bytes=${'$'}BYTES"
status done
""".trimStart()
    }

    /** One write of the new partition table: [file] goes to [lba]. */
    data class TablePart(val file: String, val lba: Long, val sha256: String)

    data class InstallJob(
        val id: String,
        val statusFile: String,
        val sevenZip: String,
        /** All image parts in order, with their SHA-256. */
        val parts: List<Pair<String, String>>,
        val imageSha256: String,
        /** The disk the probe found, and what it saw. */
        val disk: String,
        val sectorSize: Int,
        val diskBytes: Long,
        /** Where the table and its backup are, and their SHA-256 when the app read them. */
        val primaryEntriesLba: Long,
        val backupLba: Long,
        val backupEntriesLba: Long,
        val entrySectors: Int,
        val primarySha256: String,
        val backupSha256: String,
        val boot: InternalLayout.Area,
        val storage: InternalLayout.Area,
        val home: InternalLayout.Area,
        /** The whole Linux space: nothing in it may be in use. */
        val linux: InternalLayout.Area,
        /** The new table, written in this order; empty when the table stays. */
        val table: List<TablePart>,
        /**
         * PB-OS over PB-OS only: leave HOME as it is (games, saves, Steam login), so
         * only boot and system are replaced.
         */
        val keepHome: Boolean = false,
        /** The newest patch, installed by PB-OS on its first start ([FirstUpdate]). */
        val firstUpdate: FirstUpdate.Files? = null,
        /**
         * Test only: a disk image file to install into instead of internal storage
         * (the real tools on a handheld, nothing of its own storage touched).
         */
        val testDiskFile: String? = null,
    )

    /** Status states, in order. */
    val STATES = listOf("started", "checking", "writing", "verifying", "boot", "home", "system", "finishing", "table", "done", "failed")

    fun installBody(job: InstallJob): String {
        require(job.parts.isNotEmpty())
        require(!job.keepHome || job.table.isEmpty()) { "HOME can only be kept where the table stays" }
        val q = ScriptKit::quote
        val partChecks = job.parts.joinToString("\n") { (path, sha) -> "check_part ${q(path)} ${q(sha)}" }
        val tableChecks = job.table.joinToString("\n") { "check_part ${q(it.file)} ${q(it.sha256)}" }
        val tableWrites = job.table.joinToString("\n") { "put_table ${q(it.file)} ${it.lba} ${q(it.sha256)}" }
        return """
${ScriptKit.prelude(INSTALL, job.id, job.statusFile)}
# Installs PB-OS over the Linux install on internal storage (see InternalScript.kt).
SEVENZIP=${q(job.sevenZip)}
PART1=${q(job.parts.first().first)}
IMAGE_SHA=${q(job.imageSha256)}
WANT_DISK=${q(job.disk)}
WANT_SS=${job.sectorSize}
WANT_BYTES=${job.diskBytes}
PRI_ENT_LBA=${job.primaryEntriesLba}
BAK_LBA=${job.backupLba}
BAK_ENT_LBA=${job.backupEntriesLba}
ENT_SECTORS=${job.entrySectors}
PRI_SHA=${q(job.primarySha256)}
BAK_SHA=${q(job.backupSha256)}
RK_OFF=${job.boot.offset} RK_LEN=${job.boot.length}
ST_OFF=${job.storage.offset} ST_LEN=${job.storage.length}
HM_OFF=${job.home.offset} HM_LEN=${job.home.length}
LX_OFF=${job.linux.offset} LX_LEN=${job.linux.length}
WORK=/data/local/tmp/pbos-internal
check_part() { # path sha256
  [ "${'$'}(sha "${'$'}1")" = "${'$'}2" ] || fail "A downloaded file is damaged. Download PB-OS again."
}
ppid() { sed -n 's/^PPid:[[:space:]]*//p' /proc/${'$'}1/status 2>/dev/null; }
# The process called $1 in the background job $2 ($! may be that process, a subshell
# that is its parent, or a sibling in the same pipeline).
find_proc() {
  [ "${'$'}(cat /proc/${'$'}2/comm 2>/dev/null)" = "${'$'}1" ] && { echo ${'$'}2; return; }
  parent=${'$'}(ppid ${'$'}2)
  for p in ${'$'}(pidof "${'$'}1"); do
    pp=${'$'}(ppid ${'$'}p)
    if [ "${'$'}pp" = "${'$'}2" ] || [ "${'$'}pp" = "${'$'}parent" ]; then echo ${'$'}p; return; fi
  done
}
io_bytes() { sed -n "s/^${'$'}2: //p" /proc/${'$'}1/io 2>/dev/null; }
# Waits for background job $6 and reports bytes its process $4 moved ($5: rchar or
# wchar), counted from $2 of $3, as state $1.
follow() {
  F=
  while kill -0 "${'$'}6" 2>/dev/null; do
    [ -n "${'$'}F" ] || F=${'$'}(find_proc "${'$'}4" "${'$'}6")
    N=${'$'}(io_bytes "${'$'}F" "${'$'}5")
    status "${'$'}1" ${'$'}(expr "${'$'}2" + "${'$'}{N:-0}") "${'$'}3"
    sleep 2
  done
  wait "${'$'}6"
}
# SHA-256 of the first $2 bytes of $1, reported as state $3 from $4 of $5.
sha_of() {
  echo 3 > /proc/sys/vm/drop_caches
  head -c "${'$'}2" "${'$'}1" | sha256sum > "${'$'}WORK/sum" &
  follow "${'$'}3" "${'$'}4" "${'$'}5" head rchar ${'$'}! || return 1
  cut -d' ' -f1 "${'$'}WORK/sum"
}
# Copies loop $1 (all of it, $2 bytes) to the start of loop $3, reported as state $4 from $5 of $6.
copy_to() {
  dd if="${'$'}1" of="${'$'}3" bs=4194304 2>/dev/null &
  follow "${'$'}4" "${'$'}5" "${'$'}6" dd wchar ${'$'}! && sync
}
# 32-bit numbers from a device: little-endian at byte $2 of $1.
u32() { od -A n -t u4 -j "${'$'}2" -N 4 "${'$'}1" | tr -d ' '; }
u8() { od -A n -t x1 -j "${'$'}2" -N 1 "${'$'}1" | tr -d ' '; }

LOOPS= MOUNTS=
loop() { # var offset length [ro]: sets var to a loop device over that byte range of the disk
  l=${'$'}(losetup -f -s ${'$'}{4:+-r} -o "${'$'}2" -S "${'$'}3" "${'$'}DEV") || return 1
  LOOPS="${'$'}l ${'$'}LOOPS"
  eval "${'$'}1=\${'$'}l"
}
mnt() { # device dir options type
  mkdir -p "${'$'}2" && mount -t "${'$'}4" -o "${'$'}3" "${'$'}1" "${'$'}2" && MOUNTS="${'$'}2 ${'$'}MOUNTS"
}
unmount_all() {
  for m in ${'$'}MOUNTS; do umount "${'$'}m" 2>/dev/null; done
  for l in ${'$'}LOOPS; do losetup -d "${'$'}l" 2>/dev/null; done
  MOUNTS= LOOPS=
}
# The work folder holds mount points: never delete into something still mounted there.
work_mounts() { while read -r _ _ _ _ mp _; do case "${'$'}mp" in "${'$'}WORK"/*) echo "${'$'}mp" ;; esac; done < /proc/self/mountinfo; }
clean_work() {
  for m in ${'$'}(work_mounts); do umount "${'$'}m" 2>/dev/null; done
  [ -z "${'$'}(work_mounts)" ] && rm -rf "${'$'}WORK"
}

[ "${'$'}(id -u)" = 0 ] || fail "This script must be run as root."
status checking
for t in losetup mount umount dd od head tail cmp sha256sum md5sum; do
  command -v ${'$'}t >/dev/null || fail "This handheld's Android has no ${'$'}t. Nothing was changed."
done
${findDisk(job)}
if [ -z "${'$'}TEST_DISK" ]; then
  [ "${'$'}DISK" = "${'$'}WANT_DISK" ] &&
    [ "${'$'}(cat /sys/block/${'$'}DISK/queue/logical_block_size)" = "${'$'}WANT_SS" ] &&
    [ "${'$'}(expr "${'$'}(cat /sys/block/${'$'}DISK/size)" '*' 512)" = "${'$'}WANT_BYTES" ] ||
    fail "Internal storage isn't what the app checked. Check internal storage again."
fi
gpt_sha() { # header LBA, entries LBA
  { dd if="${'$'}DEV" bs=${'$'}WANT_SS skip="${'$'}1" count=1 && dd if="${'$'}DEV" bs=${'$'}WANT_SS skip="${'$'}2" count=${'$'}ENT_SECTORS; } 2>/dev/null | sha256sum | cut -d' ' -f1
}
[ "${'$'}(gpt_sha 1 ${'$'}PRI_ENT_LBA)" = "${'$'}PRI_SHA" ] && [ "${'$'}(gpt_sha ${'$'}BAK_LBA ${'$'}BAK_ENT_LBA)" = "${'$'}BAK_SHA" ] ||
  fail "The internal partition table changed since the app checked it. Check internal storage again."
# Nothing in the Linux space may be mounted or held, whatever the kernel thinks its
# partitions are.
LX_END=${'$'}(expr "${'$'}LX_OFF" + "${'$'}LX_LEN")
for p in /sys/block/${'$'}DISK/${'$'}DISK*; do
  [ -z "${'$'}TEST_DISK" ] || break
  [ -r "${'$'}p/partition" ] || continue
  s=${'$'}(expr "${'$'}(cat "${'$'}p/start")" '*' 512)
  e=${'$'}(expr "${'$'}s" + "${'$'}(cat "${'$'}p/size")" '*' 512)
  if ge "${'$'}s" "${'$'}LX_END" || ge "${'$'}LX_OFF" "${'$'}e"; then continue; fi
  in_use "${'$'}(cat "${'$'}p/dev")" "${'$'}p" && fail "Android is using part of the Linux space (${'$'}{p##*/}). Nothing was changed."
done
IMAGE_BYTES=${'$'}("${'$'}SEVENZIP" l -slt "${'$'}PART1" 2>/dev/null | sed -n '/^----------/,${'$'} s/^Size = //p' | head -1)
[ -n "${'$'}IMAGE_BYTES" ] || fail "The downloaded image can't be opened. Download PB-OS again."
ge "${'$'}ST_LEN" "${'$'}IMAGE_BYTES" || fail "This PB-OS image doesn't fit the STORAGE partition. Nothing was changed."
$partChecks
$tableChecks
${firstUpdateChecks(job)}
clean_work && mkdir -p "${'$'}WORK" || fail "Couldn't make a work folder. Restart the handheld and try again."
${keepHomeCheck(job)}
# At most 64 MB of unwritten data at a time: more stalls Android (see WriteScript).
VM=/proc/sys/vm
OLD_RATIO=${'$'}(cat ${'$'}VM/dirty_ratio) OLD_BG_RATIO=${'$'}(cat ${'$'}VM/dirty_background_ratio)
OLD_BYTES=${'$'}(cat ${'$'}VM/dirty_bytes) OLD_BG_BYTES=${'$'}(cat ${'$'}VM/dirty_background_bytes)
restore_vm() {
  if [ "${'$'}OLD_BYTES" != 0 ]; then echo "${'$'}OLD_BYTES" > ${'$'}VM/dirty_bytes; else echo "${'$'}OLD_RATIO" > ${'$'}VM/dirty_ratio; fi
  if [ "${'$'}OLD_BG_BYTES" != 0 ]; then echo "${'$'}OLD_BG_BYTES" > ${'$'}VM/dirty_background_bytes; else echo "${'$'}OLD_BG_RATIO" > ${'$'}VM/dirty_background_ratio; fi
}
trap 'unmount_all; restore_vm; clean_work; on_exit' EXIT
echo 16777216 > ${'$'}VM/dirty_background_bytes
echo 67108864 > ${'$'}VM/dirty_bytes

# From here on the old Linux install is overwritten.
loop STAGE ${'$'}ST_OFF ${'$'}ST_LEN || fail "Couldn't open internal storage. Nothing was changed."
status writing 0 "${'$'}IMAGE_BYTES"
"${'$'}SEVENZIP" e -so -bd "${'$'}PART1" 2>/dev/null | dd of="${'$'}STAGE" bs=4194304 2>/dev/null &
follow writing 0 "${'$'}IMAGE_BYTES" dd wchar ${'$'}! || fail "Writing PB-OS failed. Try again."
sync
[ "${'$'}(sha_of "${'$'}STAGE" "${'$'}IMAGE_BYTES" verifying 0 "${'$'}IMAGE_BYTES")" = "${'$'}IMAGE_SHA" ] ||
  fail "PB-OS didn't read back correctly from internal storage. Try again."

# The image's three partitions (MBR: type at +4, first sector at +8, sectors at +12).
[ "${'$'}(u8 "${'$'}STAGE" 510)${'$'}(u8 "${'$'}STAGE" 511)" = 55aa ] || fail "The image has no partition table."
part() { # entry offset -> "type offset length" (bytes from the start of STAGE)
  e=${'$'}1
  echo "${'$'}(u8 "${'$'}STAGE" ${'$'}(expr "${'$'}e" + 4)) ${'$'}(expr "${'$'}(u32 "${'$'}STAGE" ${'$'}(expr "${'$'}e" + 8))" '*' 512) ${'$'}(expr "${'$'}(u32 "${'$'}STAGE" ${'$'}(expr "${'$'}e" + 12))" '*' 512)"
}
set -- ${'$'}(part 446); P1_TYPE=${'$'}1 P1_OFF=${'$'}2 P1_LEN=${'$'}3
set -- ${'$'}(part 462); P2_TYPE=${'$'}1 P2_OFF=${'$'}2 P2_LEN=${'$'}3
set -- ${'$'}(part 478); P3_TYPE=${'$'}1 P3_OFF=${'$'}2 P3_LEN=${'$'}3
case "${'$'}P1_TYPE${'$'}P2_TYPE${'$'}P3_TYPE" in 0c8383|0b8383) ;; *) fail "The image's partitions aren't what the app expects." ;; esac
ge "${'$'}IMAGE_BYTES" ${'$'}(expr "${'$'}P3_OFF" + "${'$'}P3_LEN") && ge "${'$'}P2_OFF" 1048576 && ge "${'$'}HM_LEN" "${'$'}P3_LEN" ||
  fail "The image's partitions aren't what the app expects."

# Boot files on a new ROCKNIX partition, the KERNEL pointed at STORAGE by name.
status boot
loop SRC_BOOT ${'$'}(expr ${'$'}ST_OFF + ${'$'}P1_OFF) ${'$'}P1_LEN ro && mnt "${'$'}SRC_BOOT" "${'$'}WORK/src" ro vfat ||
  fail "Couldn't open the image's boot files."
[ -f "${'$'}WORK/src/KERNEL" ] || fail "The image has no KERNEL."
# Android's newfs_msdos always takes the loop device's 512-byte sectors, and a FAT
# with smaller sectors than the disk's can't be mounted by PB-OS on 4K UFS. So the
# image's own mkfs.fat formats it, run from the image's system (read-only, through
# its own glibc loader).
loop IMG_ROOT ${'$'}(expr ${'$'}ST_OFF + ${'$'}P2_OFF) ${'$'}P2_LEN ro && mnt "${'$'}IMG_ROOT" "${'$'}WORK/img" ro,noload ext4 ||
  fail "Couldn't open the image's system."
I="${'$'}WORK/img"
[ -x "${'$'}I/usr/bin/mkfs.fat" ] && [ -x "${'$'}I/usr/lib/ld-linux-aarch64.so.1" ] || fail "The image has no mkfs.fat."
loop RK ${'$'}RK_OFF ${'$'}RK_LEN || fail "Couldn't open the ROCKNIX partition."
dd if=/dev/zero of="${'$'}RK" bs=1048576 count=8 2>/dev/null
"${'$'}I/usr/lib/ld-linux-aarch64.so.1" --library-path "${'$'}I/usr/lib" "${'$'}I/usr/bin/mkfs.fat" -F 32 -S ${'$'}WANT_SS -n ROCKNIX "${'$'}RK" >/dev/null 2>&1 &&
  [ "${'$'}(od -A n -t u2 -j 11 -N 2 "${'$'}RK" | tr -d ' ')" = "${'$'}WANT_SS" ] || fail "Couldn't format the ROCKNIX partition."
mnt "${'$'}RK" "${'$'}WORK/boot" rw vfat || fail "Couldn't mount the new ROCKNIX partition."
for f in "${'$'}WORK"/src/*; do
  cp -r "${'$'}f" "${'$'}WORK/boot/" || fail "Couldn't copy the boot files."
done
K="${'$'}WORK/boot/KERNEL"
${ScriptKit.RETARGET_KERNEL}
retarget_kernel "${'$'}K" PARTLABEL=STORAGE || fail "The image's KERNEL couldn't be pointed at STORAGE."
# Apart from the command line, byte for byte the image's.
rest() { { head -c 64 "${'$'}1"; tail -c +577 "${'$'}1"; } | sha256sum | cut -d' ' -f1; }
[ "${'$'}(rest "${'$'}K")" = "${'$'}(rest "${'$'}WORK/src/KERNEL")" ] || fail "The KERNEL didn't read back correctly."
(cd "${'$'}WORK/boot" && md5sum KERNEL > KERNEL.md5) || fail "Couldn't write KERNEL.md5."
printf '%s\n' "PB-OS on internal storage, installed by the PB-OS Installer app." \
  "The boot menu reads KERNEL from this partition. Do not rename it." \
  "root=PARTLABEL=STORAGE" > "${'$'}WORK/boot/README.txt"
sync
unmount_all

${if (job.keepHome) "# HOME stays as it is." else homeCopy}

# The system moves from inside the image to the start of STORAGE: every byte is read
# before anything is written over it.
loop SRC ${'$'}(expr ${'$'}ST_OFF + ${'$'}P2_OFF) ${'$'}P2_LEN ro && loop DST ${'$'}ST_OFF ${'$'}ST_LEN || fail "Couldn't open the STORAGE partition."
T=${'$'}(expr ${'$'}P2_LEN '*' 3)
A=${'$'}(sha_of "${'$'}SRC" ${'$'}P2_LEN system 0 ${'$'}T) && [ -n "${'$'}A" ] || fail "Reading the system failed. Try again."
copy_to "${'$'}SRC" ${'$'}P2_LEN "${'$'}DST" system ${'$'}P2_LEN ${'$'}T || fail "Copying the system failed. Try again."
[ "${'$'}(sha_of "${'$'}DST" ${'$'}P2_LEN system ${'$'}(expr ${'$'}P2_LEN '*' 2) ${'$'}T)" = "${'$'}A" ] ||
  fail "The system didn't read back correctly. Try again."
unmount_all

# Mounts by partition name (an SD card with PB-OS may be in too, with the same
# filesystem IDs), and both filesystems grow to their partitions on first boot.
status finishing
loop ROOT ${'$'}ST_OFF ${'$'}ST_LEN && mnt "${'$'}ROOT" "${'$'}WORK/root" rw,noatime ext4 || fail "Couldn't open the new system."
cat > "${'$'}WORK/fstab" <<'FSTAB'
$FSTAB
FSTAB
for f in etc/fstab var/lib/overlays/etc/upper/fstab; do
  [ -f "${'$'}WORK/root/${'$'}f" ] || continue
  # 0644: the image's own fstab is world-writable (0666).
  cat "${'$'}WORK/fstab" > "${'$'}WORK/root/${'$'}f" && chmod 644 "${'$'}WORK/root/${'$'}f" && cmp -s "${'$'}WORK/fstab" "${'$'}WORK/root/${'$'}f" ||
    fail "Couldn't write fstab."
done
[ -e "${'$'}WORK/root/sbin/init" ] || [ -L "${'$'}WORK/root/sbin/init" ] || fail "The new system is incomplete."
${firstUpdateBlock(job)}
sync
unmount_all

${tableBlock(job, tableWrites)}
status done
""".trimStart()
    }

    /** The image's home into HOME (first: the system copy overwrites the image in STORAGE). */
    private val homeCopy = """
# Home first: the system copy below overwrites the image in STORAGE.
loop SRC ${'$'}(expr ${'$'}ST_OFF + ${'$'}P3_OFF) ${'$'}P3_LEN ro && loop DST ${'$'}HM_OFF ${'$'}HM_LEN || fail "Couldn't open the HOME partition."
T=${'$'}(expr ${'$'}P3_LEN '*' 2)
copy_to "${'$'}SRC" ${'$'}P3_LEN "${'$'}DST" home 0 ${'$'}T || fail "Copying home failed. Try again."
A=${'$'}(sha_of "${'$'}SRC" ${'$'}P3_LEN home ${'$'}P3_LEN ${'$'}T) && [ -n "${'$'}A" ] || fail "Checking home failed. Try again."
[ "${'$'}(sha_of "${'$'}DST" ${'$'}P3_LEN home ${'$'}P3_LEN ${'$'}T)" = "${'$'}A" ] || fail "Home didn't read back correctly. Try again."
unmount_all
""".trim()

    /** With the system still mounted: HOME too, then the update goes in. Never fails the install. */
    private fun firstUpdateBlock(job: InstallJob): String {
        val update = job.firstUpdate ?: return ""
        return """
${FirstUpdate.block(update)}
if loop HOME_RW ${'$'}HM_OFF ${'$'}HM_LEN && mnt "${'$'}HOME_RW" "${'$'}WORK/home" rw,noatime ext4 && place_update "${'$'}WORK/root" "${'$'}WORK/home"; then
  U=first_update=placed
else
  U=first_update=failed
fi
EXTRA="${'$'}{EXTRA:+${'$'}EXTRA
}${'$'}U"
""".trim()
    }

    /** The update's files, checked with the download before anything is written. */
    private fun firstUpdateChecks(job: InstallJob): String =
        job.firstUpdate?.files.orEmpty().joinToString("\n") { (path, sha) -> "check_part ${ScriptKit.quote(path)} ${ScriptKit.quote(sha)}" }

    /** A kept HOME must be PB-OS's: ext4 that mounts, with /steamos in it. Checked before anything is written. */
    private fun keepHomeCheck(job: InstallJob): String {
        if (!job.keepHome) return ""
        return """
[ "${'$'}(od -A n -t x2 -j ${'$'}(expr ${'$'}HM_OFF + 1080) -N 2 "${'$'}DEV" | tr -d ' ')" = ef53 ] &&
  loop HOME_CHECK ${'$'}HM_OFF ${'$'}HM_LEN ro && mnt "${'$'}HOME_CHECK" "${'$'}WORK/home" ro,noload ext4 && [ -d "${'$'}WORK/home/steamos" ] ||
  fail "The HOME partition isn't a PB-OS home, so it can't be kept. Nothing was changed."
unmount_all
""".trim()
    }

    private fun tableBlock(job: InstallJob, writes: String): String {
        if (job.table.isEmpty()) return ""
        return """
# The new partition table: backup copy first, so the primary still describes the old
# layout until the very last write.
status table
put_table() { # file lba sha256
  dd if="${'$'}1" of="${'$'}DEV" bs=${'$'}WANT_SS seek="${'$'}2" conv=notrunc 2>/dev/null || fail "Writing the partition table failed."
}
$writes
sync
echo 3 > /proc/sys/vm/drop_caches
check_table() { # file lba sha256
  n=${'$'}(expr "${'$'}(wc -c < "${'$'}1")" / ${'$'}WANT_SS)
  [ "${'$'}(dd if="${'$'}DEV" bs=${'$'}WANT_SS skip="${'$'}2" count="${'$'}n" 2>/dev/null | sha256sum | cut -d' ' -f1)" = "${'$'}3" ] ||
    fail "The new partition table didn't read back correctly."
}
${writes.replace("put_table", "check_table")}
"""
    }

    private fun findDisk(job: InstallJob): String {
        val test = job.testDiskFile ?: return "TEST_DISK=\n$FIND_DISK"
        return """
TEST_DISK=${ScriptKit.quote(test)}
DEV=${'$'}TEST_DISK DISK=${'$'}WANT_DISK
[ -f "${'$'}DEV" ] || fail "No test disk."
""".trim()
    }

    /** Finds Android's userdata partition and the whole disk it is on ($DISK, $DEV); defines in_use. */
    private val FIND_DISK = """
UD=${'$'}(readlink -f /dev/block/by-name/userdata 2>/dev/null)
[ -b "${'$'}UD" ] || UD=${'$'}(readlink -f /dev/block/bootdevice/by-name/userdata 2>/dev/null)
[ -b "${'$'}UD" ] || fail "Couldn't find Android's userdata partition."
DISK=${'$'}(basename "${'$'}(readlink -f /sys/class/block/${'$'}{UD##*/}/..)")
DEV=/dev/block/${'$'}DISK
case "${'$'}DISK" in sd[a-z]) ;; *) fail "Android's storage isn't the kind this app knows (${'$'}DISK)." ;; esac
[ -b "${'$'}DEV" ] || fail "Couldn't open internal storage."
in_use() { # major:minor sysfs-dir: mounted, or held by another device (dm, loop…)
  while read -r _ _ mm _; do [ "${'$'}mm" = "${'$'}1" ] && return 0; done < /proc/self/mountinfo
  [ -n "${'$'}(ls "${'$'}2/holders" 2>/dev/null)" ]
}
""".trim()

    private val FSTAB = """
# PB-OS on internal storage (partitions by GPT name), written by the PB-OS Installer app
PARTLABEL=STORAGE  /      ext4  defaults,noatime,x-systemd.growfs            0 1
PARTLABEL=ROCKNIX  /boot  vfat  defaults,umask=0077,nofail                   0 2
PARTLABEL=HOME     /home  ext4  defaults,noatime,commit=30,x-systemd.growfs  0 2
""".trim()
}
