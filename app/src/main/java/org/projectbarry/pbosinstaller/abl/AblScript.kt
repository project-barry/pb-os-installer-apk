package org.projectbarry.pbosinstaller.abl

import org.projectbarry.pbosinstaller.root.ScriptKit

/**
 * Root scripts for the boot loader (ABL): back up both slots, install the boot
 * menu (OpenABL) that starts PB-OS, and deal with an install that didn't finish.
 *
 * The ABL lives in the abl_a and abl_b partitions on the handheld's own storage.
 * A wrong ABL can stop the handheld from starting, so the scripts check the
 * handheld, the files and every write.
 *
 * While an install is writing, a note (the journal) says where the original is,
 * its checksums, what is being written and on which handheld. It is there before
 * the first byte is written and goes only once both slots were read back as whole:
 * the boot menu, the original, or something newer. While it is there, no new backup
 * is made (the slots may be half written).
 *
 * After an interruption nothing is written unless each slot is, block by block, its
 * own original or the boot menu that was being written: anything else (an Android
 * update since, another handheld's note) is left alone.
 */
object AblScript {
    const val BACKUP = "abl-backup"
    const val INSTALL = "abl-install"
    const val RESTORE = "abl-restore"
    const val CHECK = "abl-check"

    /** What `identify` prints before a boot menu's label ("menu: OpenABL 1.1"). */
    const val MENU = "menu: "

    /** Root's own copies while an install is under way (the app's folder can be cleared). */
    private const val WORK = "/data/local/tmp/pbos-abl-work"
    private const val LOCK = "/data/local/tmp/pbos-abl.lock"

    /** Known boot loaders: SHA-256 of what identifies them, and what to call them. */
    data class Known(val label: String, val sha256: String, val bytes: Long?)

    data class BackupJob(
        val id: String,
        val statusFile: String,
        /** Where the copies go (the app's folder, as root sees it). */
        val backupDir: String,
        /** The note of an unfinished install ([journal][AblScript]). */
        val journal: String,
        /** ro.board.platform the app checked (kalama, pineapple, sun). */
        val platform: String,
        /** Written into info.txt next to the copies. */
        val info: List<String>,
        /** Stock boot loaders, by the SHA-256 of the whole partition. */
        val stock: List<Known>,
        /** Boot menus (OpenABL, ROCKNIX), by the SHA-256 of their first [Known.bytes] bytes. */
        val menus: List<Known>,
        /** ro.soc.model the app checked, where the platform isn't enough (SM7750 shares sun with the 8 Elite). */
        val socModel: String? = null,
    )

    data class InstallJob(
        val id: String,
        val statusFile: String,
        val platform: String,
        /** The boot menu to install, its label, size and SHA-256. */
        val payload: String,
        val label: String,
        val payloadBytes: Long,
        val payloadSha256: String,
        /** The folder all backups are in; a resumed install's backup must be in it. */
        val backups: String,
        /** The backup the user saved: both slots must still match it. Null: finish the install the journal is about. */
        val backupDir: String?,
        val journal: String,
        val stock: List<Known>,
        val menus: List<Known>,
        val socModel: String? = null,
        /**
         * The Duo: write the slot Android isn't running from first, instead of slot b first
         * (until that one is written and read back, the active slot is untouched); only on
         * the charger, and not while an Android update waits to switch slots.
         */
        val careful: Boolean = false,
        /** Refuse unless both slots hold a known boot loader ([stock], [menus]). */
        val onlyOverKnown: Boolean = false,
    )

    /** After an install that didn't finish: [checkBody] looks, [restoreBody] puts the original back. */
    data class AfterJob(
        val id: String,
        val statusFile: String,
        val platform: String,
        val backups: String,
        val journal: String,
        val stock: List<Known>,
        val menus: List<Known>,
        /** The app's boot menu file, to tell a half-written slot by (root has its own copy too). */
        val payload: String? = null,
        val socModel: String? = null,
        /** As in [InstallJob.careful]. */
        val careful: Boolean = false,
    )

    private val q = ScriptKit::quote

    private fun String.sh() = trim().replace("§", "$")

    private fun checks(platform: String, socModel: String?): String {
        val model = socModel?.let {
            "[ \"\$(getprop ro.soc.model)\" = ${q(it)} ] || fail \"This isn't the handheld the app checked.\"\n"
        }.orEmpty()
        return """
[ "§(id -u)" = 0 ] || fail "This script must be run as root."
[ "§(getprop ro.board.platform)" = ${q(platform)} ] || fail "This isn't the handheld the app checked."
${model}for s in a b; do
  [ -e /dev/block/by-name/abl_§s ] || fail "The boot loader partition abl_§s wasn't found."
done
""".sh()
    }

    /**
     * One boot loader script at a time: two would write over each other. The kernel lets
     * go of the lock when the script ends, however it ends. The same job started a second
     * time does nothing.
     *
     * Without flock, a file that appears with its owner's number already in it (a hard
     * link). Taking over a dead owner's can't be one step in a shell: it's replaced only
     * if it still is the dead one's, and the new owner goes on only if it is still its own
     * a second later. Two scripts could both go on only if one of them slept for that
     * whole second between those two commands.
     */
    private val ONLY_ONE = """
LOCK=$LOCK
BOOT_ID=§(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
OWNED=§LOCK.owner
busy() {
  rm -f "§LOCK.me.§§"
  [ "§(sed -n 1p "§OWNED" 2>/dev/null)" = "§JOB" ] && exit 0
  fail "Another boot loader step is still running. Let it finish, then try again."
}
can_flock() { # on a file of its own
  command -v flock >/dev/null 2>&1 || return 1
  exec 7>>"§LOCK.try.§§" && flock -n 8 8>&7
  cf=§?
  exec 7>&-
  rm -f "§LOCK.try.§§"
  return §cf
}
owner_lives() { # the lock's text
  [ "§(printf '%s\n' "§1" | sed -n 3p)" = "§BOOT_ID" ] || return 1
  ol_pid=§(printf '%s\n' "§1" | sed -n 2p)
  [ -n "§ol_pid" ] && kill -0 "§ol_pid" 2>/dev/null || return 1
  # Where that can be read: still one of these scripts, not another process by now.
  case "§(tr '\000' ' ' < "/proc/§ol_pid/cmdline" 2>/dev/null)" in ''|*pbos-abl*) return 0;; esac
  return 1
}
if can_flock; then
  exec 9>>"§LOCK.file"
  flock -n 8 8>&9 || busy
else
  OWNED=§LOCK.held
  lo_n=0
  printf '%s\n%s\n%s\n' "§JOB" "§§" "§BOOT_ID" > "§LOCK.me.§§"
  until ln "§LOCK.me.§§" "§LOCK.held" 2>/dev/null; do
    lo_n=§(( lo_n + 1 ))
    [ §lo_n -le 5 ] || busy
    lo_x=§(cat "§LOCK.held" 2>/dev/null) || continue
    owner_lives "§lo_x" && busy
    [ "§(cat "§LOCK.held" 2>/dev/null)" = "§lo_x" ] && mv -f "§LOCK.me.§§" "§LOCK.held" || continue
    sleep 1
    [ "§(sed -n 2p "§LOCK.held" 2>/dev/null)" = "§§" ] || busy
    break
  done
  rm -f "§LOCK.me.§§"
fi
printf '%s\n%s\n%s\n' "§JOB" "§§" "§BOOT_ID" > "§OWNED"
# The status stays fresh while the storage is being flushed, which can take a while.
( while sleep 5; do kill -0 §§ 2>/dev/null || exit; touch -c "§STATUS" 2>/dev/null; done ) >/dev/null 2>&1 9>&- &
BEAT=§!
CHK=/data/local/tmp/pbos-abl-chk
bye() { kill "§BEAT" 2>/dev/null; rm -rf "§CHK"; [ "§(sed -n 2p "§OWNED" 2>/dev/null)" = "§§" ] && rm -f "§OWNED"; on_exit; }
trap bye EXIT
# Left over from scripts a power cut or kill ended.
for f in /data/local/tmp/pbos-abl-*.sh; do [ "§f" = "§RUN_COPY" ] || rm -f "§f"; done
""".sh()

    private val FLASH_KIT = """
part() { echo "/dev/block/by-name/abl_§1"; }
# Makes the next read come from the storage, not from memory.
reread() { sync; blockdev --flushbufs "§1" 2>/dev/null; { echo 3 > /proc/sys/vm/drop_caches; } 2>/dev/null; }
put() { dd if="§1" of="§(part "§2")" bs=1048576 conv=fsync 2>/dev/null; reread "§(part "§2")"; }
give_dir() { [ -z "§OWNER" ] || chown "§OWNER" "§1" 2>/dev/null; chmod 770 "§1" 2>/dev/null; }
WORK=$WORK
roots() { [ "§(stat -c %u "§1" 2>/dev/null)" = 0 ]; }
own_work() { { [ -d "§WORK" ] && roots "§WORK"; } || { rm -rf "§WORK"; mkdir -p "§WORK"; } && chmod 700 "§WORK"; }
# The note of an unfinished install: the app's, and root's own copy (the app's data can be cleared).
root_note() { [ -f "§WORK/journal" ] && roots "§WORK"; }
unfinished() { [ -e "§JOURNAL" ] || root_note; }
jget() { sed -n "s/^§1=//p" "§2" 2>/dev/null | head -n 1; }
is_sha() { [ §{#1} = 64 ] || return 1; case "§1" in *[!0-9a-f]*) return 1;; esac; }
want() { case "§1" in a) echo "§SHA_A";; b) echo "§SHA_B";; esac; }
original() { [ "§(sha "§(part "§1")")" = "§(want "§1")" ]; }
# A copy of a slot's original that still has its checksum, or nothing.
good_copy() {
  for gc in "§DIR/abl_§1.img" "§WORK/abl_§1.img"; do
    [ "§(sha "§gc" 2>/dev/null)" = "§(want "§1")" ] && { echo "§gc"; return; }
  done
}
# Puts the original into a slot and reads it back. True only when the slot holds the original.
put_back() {
  reread "§(part "§1")"
  original "§1" && return 0
  pb_f=§(good_copy "§1")
  [ -n "§pb_f" ] || return 1
  for pb_n in 1 2; do
    put "§pb_f" "§1"
    original "§1" && return 0
  done
  return 1
}
# Forgets the install: both slots were read back.
finished() { rm -f "§WORK/journal" "§JOURNAL" "§JOURNAL.new"; sync; rm -rf "§WORK"; }
KEEP_ON="Don't turn your handheld off and don't restart it: it may not start again. Keep it charged."
# Puts the original back into the slots §@, in that order, and stops at the first one that
# can't be: a whole slot is never written while another isn't whole. LEFT: that one.
restore_all() {
  status restoring
  LEFT=
  for s in "§@"; do put_back "§s" || { LEFT=§s; return 1; }; done
}
is_menu() { [ "§(head -c "§M_BYTES" "§(part "§1")" | sha)" = "§M_SHA" ]; }
whole() { original "§1" || is_menu "§1"; }
# The end, after writing: with both slots whole, or with one that isn't.
die() { status failed 0 0 "§1"; exit 1; }
die_half() { also "restore=failed"; also "result=half"; die "§1 §KEEP_ON"; }
also() { EXTRA="§EXTRA§{EXTRA:+
}§1"; }
# A random number root made up on this handheld (not in the app's data, so it doesn't move to another one).
ID_FILE=/data/local/tmp/pbos-abl-id
own_id() { [ -s "§ID_FILE" ] && roots "§ID_FILE" && sha "§ID_FILE"; }
make_id() {
  own_id >/dev/null && return
  rm -f "§ID_FILE"
  cat /proc/sys/kernel/random/uuid > "§ID_FILE" 2>/dev/null && chmod 600 "§ID_FILE" && sync
}
""".sh()

    /** Reads the note of the unfinished install: NOTE is ok, foreign (another handheld's), damaged or none. */
    private val READ_NOTE = """
read_note() {
  DIR=§(jget dir "§1") SHA_A=§(jget sha_a "§1") SHA_B=§(jget sha_b "§1")
  N_SHA=§(jget payload_sha "§1") N_BYTES=§(jget payload_bytes "§1") N_BOOT=§(jget boot "§1")
  N_ID=§(jget id "§1") M_SHA=§N_SHA M_BYTES=§N_BYTES
  case "§DIR" in *..*) return 1;; "§BACKUPS"/*) ;; *) return 1;; esac
  case "§N_BYTES" in ''|*[!0-9]*|????????*) return 1;; esac
  [ "§(jget end "§1")" = 1 ] && is_sha "§SHA_A" && is_sha "§SHA_B" && is_sha "§N_SHA"
}
# Root's own note was made here. The app's is another handheld's only when it has another
# number: model names can change with an update. Without one, the slots themselves tell.
other_handheld() {
  oh_id=§(own_id)
  [ -n "§oh_id" ] && [ -n "§N_ID" ] && [ "§oh_id" != "§N_ID" ]
}
if root_note && read_note "§WORK/journal"; then NOTE=ok
elif [ -e "§JOURNAL" ] && read_note "§JOURNAL"; then
  if other_handheld; then NOTE=foreign; else NOTE=ok; fi
elif unfinished; then NOTE=damaged
else NOTE=none; fi
""".sh()

    /**
     * What each slot holds (ST_A, ST_B), read from the storage:
     * original / menu: exactly its original, or exactly the boot menu that was being written
     *   (with its original's bytes after it);
     * known: another boot loader the app knows;
     * half: every block (4096 bytes) is the original's or the boot menu's, some of each;
     * newer: an ELF file that differs from both all over, with nothing of the boot menu in it (an Android update);
     * other: none of those; unsure: there's nothing left to compare it with; unreadable.
     */
    private val SURVEY = """
good_payload() {
  for gp in "§WORK/payload" §PAYLOADS; do
    [ "§(sha "§gp" 2>/dev/null)" = "§N_SHA" ] && { echo "§gp"; return; }
  done
}
blocks() { dd if="§1" bs=4096 skip="§2" count="§3" 2>/dev/null | sha; }
slot_state() {
  ss_p=§(part "§1")
  reread "§ss_p"
  ss_h=§(sha "§ss_p")
  is_sha "§ss_h" || { STATE=unreadable; return; }
  [ "§ss_h" = "§(want "§1")" ] && { STATE=original; return; }
  ss_o=§(good_copy "§1") ss_y=§(good_payload)
  if [ -z "§ss_o" ]; then
    if [ "§(head -c "§N_BYTES" "§ss_p" | sha)" = "§N_SHA" ]; then STATE=menu
    elif [ "§(identify "§ss_p")" != unknown ]; then STATE=known
    else STATE=unsure; fi
    return
  fi
  ss_t="§CHK/target"
  if [ -n "§ss_y" ] && cp "§ss_o" "§ss_t" && dd if="§ss_y" of="§ss_t" bs=4096 conv=notrunc 2>/dev/null &&
    [ "§(head -c "§N_BYTES" "§ss_t" | sha)" = "§N_SHA" ]; then
    [ "§(sha "§ss_t")" = "§ss_h" ] && { STATE=menu; return; }
  else
    ss_t=
  fi
  [ "§(identify "§ss_p")" != unknown ] && { STATE=known; return; }
  ss_s=§(blockdev --getsize64 "§ss_p")
  if [ -z "§ss_t" ] || [ "§(wc -c < "§ss_o" | tr -d ' ')" != "§ss_s" ]; then STATE=unsure; return; fi
  ss_n=§(( (ss_s + 4095) / 4096 )) ss_i=0 ss_f=0 ss_m=0
  while [ §ss_i -lt §ss_n ]; do
    ss_x=§(blocks "§ss_p" §ss_i 16)
    if [ "§ss_x" = "§(blocks "§ss_o" §ss_i 16)" ]; then :
    elif [ "§ss_x" = "§(blocks "§ss_t" §ss_i 16)" ]; then ss_m=§(( ss_m + 1 ))
    else
      ss_j=§ss_i
      while [ §ss_j -lt §(( ss_i + 16 )) ] && [ §ss_j -lt §ss_n ]; do
        ss_x=§(blocks "§ss_p" §ss_j 1)
        if [ "§ss_x" = "§(blocks "§ss_o" §ss_j 1)" ]; then :
        elif [ "§ss_x" = "§(blocks "§ss_t" §ss_j 1)" ]; then ss_m=§(( ss_m + 1 ))
        else ss_f=§(( ss_f + 1 )); fi
        ss_j=§(( ss_j + 1 ))
      done
    fi
    ss_i=§(( ss_i + 16 ))
  done
  if [ §ss_f = 0 ]; then STATE=half
  elif [ §ss_f -ge 16 ] && [ §ss_m = 0 ] && [ "§(od -A n -t x1 -N 4 "§ss_p" | tr -d ' ')" = 7f454c46 ]; then STATE=newer
  else STATE=other; fi
}
# From the survey on, every failure says how the slots stand, and with one that isn't whole to leave the handheld on.
ST_A= ST_B=
fail() {
  fl=§1
  if [ -n "§ST_B" ]; then
    case "§EXTRA" in *result=*) ;; *) also "result=§(result)";; esac
    case "§ST_A §ST_B" in *half*|*other*|*unsure*|*unreadable*) fl="§fl §KEEP_ON";; esac
  fi
  status failed 0 0 "§fl"
  exit 1
}
survey() {
  rm -rf "§CHK"
  mkdir -p "§CHK" && chmod 700 "§CHK" || fail "Couldn't make a work folder. Nothing was changed."
  slot_state a; ST_A=§STATE
  slot_state b; ST_B=§STATE
  also "slot_a=§ST_A"
  also "slot_b=§ST_B"
}
ended() { finished; also "result=§1"; status done; exit 0; }
# No slot needs anything: the note goes, and that's all.
end_if_whole() {
  case "§ST_A §ST_B" in
    "original original") ended original;;
    "menu menu") ended menu;;
    *half*|*other*|*unsure*|*unreadable*) ;;
    *newer*)
      # Only a start since, from a slot that was updated, shows that the update is whole.
      [ -n "§N_BOOT" ] && [ -n "§BOOT_ID" ] && [ "§N_BOOT" != "§BOOT_ID" ] || return
      case "§(getprop ro.boot.slot_suffix) §ST_A §ST_B" in
        "_a newer newer"|"_b newer newer")
          # The other one nothing has shown to be whole: root keeps the originals.
          rm -rf "§WORK.kept"; cp -r "§WORK" "§WORK.kept" 2>/dev/null; rm -f "§WORK.kept/journal"; chmod 700 "§WORK.kept" 2>/dev/null
          ended changed;;
        "_a newer "*|"_b "*" newer") ended changed;;
      esac;;
    *known*) ended changed;;
  esac
}
# What the slots are together, when something is left to do.
result() {
  case "§ST_A §ST_B" in
    *other*|*unsure*|*unreadable*) echo other;;
    *half*newer*|*newer*half*|*half*known*|*known*half*) echo other;;
    *newer*|*known*) echo newer;;
    *half*) echo half;;
    *) echo mixed;;
  esac
}
# The slots §@, a half-written one first, whatever slot is in use.
half_first() {
  hf_h= hf_w=
  for hf in "§@"; do
    case "§hf" in a) hf_s=§ST_A;; *) hf_s=§ST_B;; esac
    if [ "§hf_s" = half ]; then hf_h="§hf_h §hf"; else hf_w="§hf_w §hf"; fi
  done
  echo §hf_h §hf_w
}
# Nothing is written unless both slots are theirs: the original's and the boot menu's blocks only.
ours_only() {
  case "§(result)" in
    half|mixed) ;;
    newer) also "result=newer"
      fail "A boot loader slot holds something newer than the install that didn't finish (slot a: §ST_A, slot b: §ST_B), so nothing is written over it.";;
    *) also "result=other"
      fail "The boot loader slots hold something that is neither the original nor the boot menu (slot a: §ST_A, slot b: §ST_B), so nothing is written over it.";;
  esac
}
""".sh()

    /** Without a readable note: it goes when each slot is a boot loader the app knows or one of its backups. */
    private val DAMAGED_NOTE = """
whole_slot() {
  ws_p=§(part "§1")
  reread "§ws_p"
  [ "§(identify "§ws_p")" != unknown ] && return 0
  ws_h=§(sha "§ws_p")
  is_sha "§ws_h" || return 1
  for ws_f in "§BACKUPS"/*/abl_§1.img; do [ "§(sha "§ws_f" 2>/dev/null)" = "§ws_h" ] && return 0; done
  return 1
}
case "§NOTE" in
  none) also "result=none"; also "unfinished=0"; fail "There is no unfinished boot menu install.";;
  foreign) # Never this handheld's: nothing here was begun by it.
    rm -f "§JOURNAL" "§JOURNAL.new"; sync; also "result=foreign"; status done; exit 0;;
  damaged)
    if whole_slot a && whole_slot b; then ended damaged; fi
    also "result=other"
    fail "The note about the unfinished install can't be read, and a slot holds something the app doesn't know. Nothing was written. §KEEP_ON";;
esac
""".sh()

    /** Sets FIRST to the slot Android isn't running from and SECOND to the active one. */
    private val PICK_SLOTS = """
case "§(getprop ro.boot.slot_suffix)" in
  _a) FIRST=b SECOND=a;;
  _b) FIRST=a SECOND=b;;
  *) fail "Couldn't tell which boot loader slot is in use. Nothing was changed.";;
esac
""".sh()

    /**
     * Shell function `update_state`: 0 when no Android update waits to switch slots, 1 when one
     * does, 2 when nothing says, 3 while one is being written. The slot of the next start comes
     * from Android's boot control, or else from the partition table as the boot loader reads
     * it (attribute bit 50: active, bits 48-49: priority; the same as pb-os's notices.cpp).
     */
    private val UPDATE_STATE = """
gpt_prio() { # a|b: prints 0-3, or nothing
  gp_p=§(readlink -f "/dev/block/by-name/boot_§1" 2>/dev/null)
  gp_s=§(readlink -f "/sys/class/block/§{gp_p##*/}" 2>/dev/null)
  gp_n=§(cat "§gp_s/partition" 2>/dev/null)
  gp_d=§{gp_s%/*}
  gp_b=§(cat "§gp_d/queue/logical_block_size" 2>/dev/null)
  case "§gp_n:§gp_b" in :*|*:|*[!0-9:]*|0*) return;; esac
  gp_d=/dev/block/§{gp_d##*/}
  [ "§(dd if="§gp_d" bs="§gp_b" skip=1 count=1 2>/dev/null | head -c 8)" = "EFI PART" ] || return
  gp_u() { od -A n -t u4 -j "§(expr "§gp_b" + "§1")" -N 4 "§gp_d" 2>/dev/null | tr -d ' '; }
  gp_l=§(gp_u 72) gp_c=§(gp_u 80) gp_z=§(gp_u 84)
  [ "§(gp_u 76)" = 0 ] && [ "§{gp_l:-0}" -gt 0 ] && [ "§{gp_z:-0}" -ge 128 ] && [ "§gp_n" -le "§{gp_c:-0}" ] || return
  gp_e=§(expr "§gp_l" '*' "§gp_b" + '(' "§gp_n" - 1 ')' '*' "§gp_z")
  case "§1" in a) gp_x=61;; *) gp_x=62;; esac
  # The right entry: its name (UTF-16) is the partition's.
  [ "§(od -A n -v -t x1 -j "§(expr "§gp_e" + 56)" -N 14 "§gp_d" 2>/dev/null | tr -d ' \n')" = "62006f006f0074005f00§{gp_x}000000" ] || return
  gp_a=§(od -A n -t u1 -j "§(expr "§gp_e" + 54)" -N 1 "§gp_d" 2>/dev/null | tr -d ' ')
  case "§gp_a" in ''|*[!0-9]*) return;; esac
  if [ §(( gp_a & 4 )) != 0 ]; then echo §(( gp_a & 3 )); else echo 0; fi
}
gpt_next() {
  gn_a=§(gpt_prio a) gn_b=§(gpt_prio b)
  [ -n "§gn_a" ] && [ -n "§gn_b" ] && [ "§gn_a" != "§gn_b" ] || return
  if [ "§gn_a" -gt "§gn_b" ]; then echo 0; else echo 1; fi
}
update_state() {
  case "§(timeout 10 update_engine_client --status 2>&1)" in
    *UPDATE_STATUS_DOWNLOADING*|*UPDATE_STATUS_VERIFYING*|*UPDATE_STATUS_FINALIZING*|*ATTEMPTING_ROLLBACK*) return 3;;
    *UPDATED_NEED_REBOOT*) return 1;;
  esac
  case "§(getprop ro.boot.slot_suffix)" in _a) us_c=0;; _b) us_c=1;; *) return 2;; esac
  us_n=§(timeout 10 bootctl get-active-boot-slot 2>/dev/null)
  case "§us_n" in 0|1) ;; *) us_n=§(gpt_next);; esac
  case "§us_n" in 0|1) [ "§us_n" = "§us_c" ]; return;; esac
  return 2
}
""".sh()

    /**
     * A half-written slot is repaired whatever slot comes next: an update that waits for a
     * restart is no reason to leave it, one that is being written right now is.
     */
    private val RESTORE_CAREFULLY = """
update_state
RC_U=§?
[ §RC_U != 3 ] || fail "Android is writing an update right now. Wait until it's done, then try again. Nothing was changed."
[ §RC_U != 1 ] || [ "§(result)" = half ] || fail "An Android update waits, so nothing is written now. Nothing was changed."
""".sh()

    private val NO_UPDATE = """
update_state
case §? in
  0) ;;
  1|3) fail "An Android update is being installed or waits for a restart. Let it finish and restart, then try again. Nothing was changed.";;
  *) fail "Couldn't tell which slot the handheld starts from next, so the boot loader isn't replaced. Nothing was changed.";;
esac
""".sh()

    /** The same for finishing, where a slot may be half written: never a word about restarting. */
    private val FINISH_NO_UPDATE = """
update_state
case §? in
  0) ;;
  1) fail "An Android update waits, so the install isn't finished now. Put Original Back instead.";;
  3) fail "Android is writing an update right now. Wait until it's done, then try again. Nothing was changed.";;
  *) fail "Couldn't tell which slot the handheld starts from next, so the install isn't finished. Put Original Back instead.";;
esac
""".sh()

    /** The charger in and at least [PowerCheck.MIN_PERCENT] %, as Android says; not being able to tell counts as not enough. */
    private val POWER = """
PW=§(timeout 10 dumpsys battery 2>/dev/null)
case "§PW" in
  *"AC powered: true"*|*"USB powered: true"*|*"Wireless powered: true"*|*"Dock powered: true"*) ;;
  *) fail "Connect the charger first, and leave it in until this is done. Nothing was changed.";;
esac
PW=§(printf '%s\n' "§PW" | sed -n 's/^ *level: *\([0-9][0-9]*\).*/\1/p' | head -n 1)
[ "§{PW:-0}" -ge ${PowerCheck.MIN_PERCENT} ] || fail "Charge to at least ${PowerCheck.MIN_PERCENT} % first, and leave the charger in until this is done. Nothing was changed."
""".sh()

    /**
     * Shell functions: `identify FILE` prints what a boot loader image is ("stock:
     * <label>", "menu: <label>" or "unknown"). Needs ScriptKit's sha.
     */
    fun identifyFunctions(stock: List<Known>, menus: List<Known>): String {
        val stockCases = stock.joinToString("\n") { "    ${q(it.sha256)}) echo ${q("stock: " + it.label)};;" }
        val menuChecks = menus.joinToString("\n") {
            "  [ \"\$(head -c ${it.bytes} \"\$1\" | sha)\" = ${q(it.sha256)} ] && { echo ${q(MENU + it.label)}; return; }"
        }
        return """
# What a boot loader image is: a known stock one, a known boot menu, or unknown.
identify() {
  case "${'$'}(sha "${'$'}1")" in
$stockCases
    *) identify_menu "${'$'}1";;
  esac
}
identify_menu() {
$menuChecks
  echo unknown
}
""".trim()
    }

    fun backupBody(job: BackupJob): String {
        val info = job.info.joinToString("\n") { "  echo ${q(it)}" }
        return """
${ScriptKit.prelude(BACKUP, job.id, job.statusFile, ownCopy = ONLY_ONE)}
# Backs up the boot loader (both ABL slots) and says what each slot holds.
DIR=${q(job.backupDir)}
JOURNAL=${q(job.journal)}
${checks(job.platform, job.socModel)}
$FLASH_KIT
${identifyFunctions(job.stock, job.menus)}
# The slots may be half written: a copy of them is no backup.
unfinished && { EXTRA="unfinished=1"; fail "The last boot menu install didn't finish. Finish it or put the original back first."; }

status backing-up
mkdir -p "§DIR" && give_dir "§DIR"
for s in a b; do
  P=§(part §s)
  F="§DIR/abl_§s.img"
  dd if="§P" of="§F" bs=1048576 2>/dev/null || fail "Couldn't read the boot loader (slot §s)."
  sync
  # The copy must match the partition, read again from the storage.
  reread "§P"
  [ -n "§(sha "§F")" ] && [ "§(sha "§F")" = "§(sha "§P")" ] || fail "The backup of slot §s didn't match. Nothing was changed."
  give "§F"
  also "slot_§s=§(identify "§P")
sha_§s=§(sha "§F")"
done
{
$info
  echo "date=§(date '+%Y-%m-%d %H:%M:%S %z')"
  printf '%s\n' "§EXTRA"
} > "§DIR/info.txt" && give "§DIR/info.txt"
sync
status done
""".replace("§", "$").trimStart()
    }

    fun installBody(job: InstallJob): String = """
${ScriptKit.prelude(INSTALL, job.id, job.statusFile, ownCopy = ONLY_ONE)}
# Installs the boot menu in both slots, ${if (job.careful) "the one not in use first" else "slot b first"}. If one can't be written
# correctly, the backed-up original goes back into it, so the handheld still starts.
PAYLOAD=${q(job.payload)}
PAYLOADS=${'§'}PAYLOAD
PAYLOAD_BYTES=${q(job.payloadBytes.toString())}
PAYLOAD_SHA=${q(job.payloadSha256)}
M_BYTES=${'§'}PAYLOAD_BYTES M_SHA=${'§'}PAYLOAD_SHA
LABEL=${q(job.label)}
BACKUPS=${q(job.backups)}
JOURNAL=${q(job.journal)}
${checks(job.platform, job.socModel)}
$FLASH_KIT
${identifyFunctions(job.stock, job.menus)}
${if (job.careful) UPDATE_STATE else ""}
file_ok() { [ "§(wc -c < "§PAYLOAD" | tr -d ' ')" = "§PAYLOAD_BYTES" ] && [ "§(sha "§PAYLOAD")" = "§PAYLOAD_SHA" ]; }
fits() { ge "§(blockdev --getsize64 "§(part a)")" "§PAYLOAD_BYTES" && ge "§(blockdev --getsize64 "§(part b)")" "§PAYLOAD_BYTES"; }
${if (job.backupDir == null) finishChecks(job) else freshChecks(job)}
# The slot §1 didn't take the boot menu and gets its original back; only then the other one: when
# it's half written, or when it was written before (§2) and there's a copy of its original.
undo() {
  UNDO=§1
  case "§1" in a) U_O=b;; *) U_O=a;; esac
  if ! whole "§U_O" || { [ -n "§2" ] && is_menu "§2" && [ -n "§(good_copy "§2")" ]; }; then UNDO="§UNDO §U_O"; fi
  if restore_all §UNDO; then
    if original a && original b; then
      finished
      also "undone=1"
      die "Installing the boot menu didn't work. The original was put back and read back, so your handheld starts as before."
    fi
    if whole a && whole b; then
      also "result=mixed"
      die "Installing the boot menu didn't work. Each slot was read back and holds a whole boot loader, the original or the boot menu."
    fi
  fi
  die_half "Installing the boot menu didn't work, and the original couldn't be put back (slot §{LEFT:-§1})."
}

status installing 0 2
${if (job.backupDir == null) "is_menu §FIRST || " else ""}put "§WORK/payload" §FIRST
is_menu §FIRST || undo §FIRST
status installing 1 2
${if (job.backupDir == null) "is_menu §SECOND || " else ""}put "§WORK/payload" §SECOND
is_menu §SECOND || undo §SECOND §FIRST
finished
status done 2 2
""".replace("§", "$").trimStart()

    /** A new install: both slots are exactly the backup the user saved. Ends with root's copies and the note on the storage. */
    private fun freshChecks(job: InstallJob) = """
DIR=${q(job.backupDir!!)}
file_ok || fail "The boot menu file is damaged. Reinstall the app."
fits || fail "The boot loader partition is too small."
${if (job.careful) PICK_SLOTS else "FIRST=b SECOND=a"}
unfinished && { EXTRA="unfinished=1"; fail "The last boot menu install didn't finish. Finish it or put the original back first."; }
for s in a b; do
  [ -f "§DIR/abl_§s.img" ] || fail "The boot loader backup is missing. Back it up again."
  reread "§(part §s)"
  # Only install over exactly what was backed up.
  B=§(sha "§DIR/abl_§s.img")
  is_sha "§B" && [ "§B" = "§(sha "§(part §s)")" ] ||
    fail "The boot loader changed since the backup. Back it up again."
${if (job.onlyOverKnown) "  [ \"§(identify \"§DIR/abl_§s.img\")\" != unknown ] ||\n    fail \"Slot §s has a boot loader this app doesn't know, so it isn't replaced. Nothing was changed.\"\n" else ""}done
SHA_A=§(sha "§DIR/abl_a.img") SHA_B=§(sha "§DIR/abl_b.img")
is_sha "§SHA_A" && is_sha "§SHA_B" || fail "The boot loader backup can't be read. Back it up again."
${if (job.careful) "$POWER\n$NO_UPDATE" else ""}
# Root's own copies: what is written, and what would go back.
rm -rf "§WORK"
own_work && cp "§PAYLOAD" "§WORK/payload" && [ "§(sha "§WORK/payload")" = "§PAYLOAD_SHA" ] ||
  fail "Couldn't copy the boot menu file. Nothing was changed."
for s in a b; do
  cp "§DIR/abl_§s.img" "§WORK/abl_§s.img" && [ "§(sha "§WORK/abl_§s.img")" = "§(want §s)" ] || fail "Couldn't copy the backup. Nothing was changed."
done
make_id
sync
# From here on, the note says where the original is until both slots were read back.
mkdir -p "§{JOURNAL%/*}" && give_dir "§{JOURNAL%/*}"
printf 'dir=%s\nsha_a=%s\nsha_b=%s\nlabel=%s\npayload_sha=%s\npayload_bytes=%s\nboot=%s\nid=%s\nend=1\n' \
  "§DIR" "§SHA_A" "§SHA_B" "§LABEL" "§PAYLOAD_SHA" "§PAYLOAD_BYTES" "§BOOT_ID" "§(own_id)" > "§JOURNAL.new" &&
  cp "§JOURNAL.new" "§WORK/journal" && sync && mv -f "§JOURNAL.new" "§JOURNAL" && sync && give "§JOURNAL" &&
  cmp -s "§JOURNAL" "§WORK/journal" && [ "§(jget end "§JOURNAL")§(jget sha_b "§JOURNAL")" = "1§SHA_B" ] ||
  { finished; fail "Couldn't note the install. Nothing was changed."; }
""".trim()

    /** Finishing an install that didn't: the slots are only the original's and this boot menu's, and the originals are safe. */
    private fun finishChecks(job: InstallJob) = """
$READ_NOTE
$SURVEY
$DAMAGED_NOTE
survey
end_if_whole
ours_only
file_ok && fits || fail "The boot menu file in the app is damaged. Put Original Back doesn't need it."
[ "§N_SHA" = "§PAYLOAD_SHA" ] && [ "§N_BYTES" = "§PAYLOAD_BYTES" ] ||
  fail "The install that didn't finish was of another boot menu than this version of the app has. Put Original Back instead."
${if (job.careful) "$POWER\n$FINISH_NO_UPDATE" else ""}
# Half-written slots first (the one in use before the other); then, as always, ${if (job.careful) "the one not in use" else "slot b"}.
${if (job.careful) "case \"§(getprop ro.boot.slot_suffix)\" in _a) set -- a b;; *) set -- b a;; esac\n[ \"§ST_A §ST_B\" = \"half half\" ] || set -- §2 §1" else "set -- b a"}
set -- §(half_first "§@")
FIRST=§1 SECOND=§2
own_work && cp "§PAYLOAD" "§CHK/payload" && mv -f "§CHK/payload" "§WORK/payload" && [ "§(sha "§WORK/payload")" = "§PAYLOAD_SHA" ] ||
  fail "Couldn't copy the boot menu file. Nothing more was changed."
for s in a b; do
  # A slot that is still the original is only written with a copy of it at hand.
  is_menu §s || {
    S_COPY=§(good_copy §s)
    [ -n "§S_COPY" ] || S_COPY=§(part §s)
    [ "§S_COPY" = "§WORK/abl_§s.img" ] || { cp "§S_COPY" "§CHK/copy" && mv -f "§CHK/copy" "§WORK/abl_§s.img"; }
    [ "§(sha "§WORK/abl_§s.img" 2>/dev/null)" = "§(want §s)" ] || fail "There's no good copy of the original of slot §s any more, so nothing more is written."
  }
done
[ -e "§JOURNAL" ] || { mkdir -p "§{JOURNAL%/*}" && cp "§WORK/journal" "§JOURNAL" && give "§JOURNAL"; }
sync
""".trim()

    private fun after(name: String, job: AfterJob, what: String, then: String) = """
${ScriptKit.prelude(name, job.id, job.statusFile, ownCopy = ONLY_ONE)}
$what
PAYLOADS=${q(job.payload.orEmpty())}
BACKUPS=${q(job.backups)}
JOURNAL=${q(job.journal)}
${checks(job.platform, job.socModel)}
$FLASH_KIT
${identifyFunctions(job.stock, job.menus)}
$READ_NOTE
$SURVEY
$DAMAGED_NOTE
status checking
survey
end_if_whole
$then
""".replace("§", "$").trimStart()

    /** After an install that didn't finish: what the slots hold. Writes to neither; only a note nothing is left to do for goes. */
    fun checkBody(job: AfterJob): String = after(CHECK, job, "# Looks at both boot loader slots after an install that didn't finish.", """
also "result=§(result)"
status done
""".trim())

    /** Puts the original back after an install that didn't finish: a half-written slot first, then the slot in use. */
    fun restoreBody(job: AfterJob): String = after(RESTORE, job, "# Puts the original boot loader back into both slots after an install that didn't finish.", """
ours_only
set -- a b
${if (job.careful) "$UPDATE_STATE\n$RESTORE_CAREFULLY\n[ \"§(getprop ro.boot.slot_suffix)\" = _b ] && set -- b a" else ""}
set -- §(half_first "§@")
if restore_all "§@"; then ended original; fi
if whole a && whole b; then
  also "result=mixed"
  die "The original couldn't be put back (slot §LEFT). Each slot was read back and holds a whole boot loader, the original or the boot menu."
fi
die_half "The original couldn't be put back (slot §LEFT)."
""".trim())
}
