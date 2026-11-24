package org.projectbarry.pbosinstaller.root

import org.projectbarry.pbosinstaller.repair.BootEntry

/** Pieces every root job script shares. */
object ScriptKit {
    /** Single-quoted for sh. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The start of every job script: moves itself to /data/local/tmp and into the
     * background (so "Run Script as Root" and xsu return at once, and a system server
     * restart can't kill it for having a file open on shared storage), then defines
     * status/fail/ge.
     *
     * [status] writes the status file (key=value lines, replaced atomically, handed to
     * the app's folder owner so the app can read it); extra lines go in $EXTRA.
     *
     * The job holds a kernel wake lock until it exits: if the handheld tried to
     * sleep mid-job, the kernel would first flush everything still waiting for
     * the card, Android's sleep service would hang behind it, and the watchdog
     * would restart the system (seen on the Thor when its screen turned off).
     * Scripts that set their own EXIT trap call [on_exit] from it.
     * [ge] compares whole numbers of any size: Android's shell does 32-bit arithmetic
     * and toybox expr's comparisons go wrong on big numbers.
     */
    /**
     * Shell function `retarget_kernel FILE ROOT`: points the ABL's KERNEL (an
     * Android boot image, header v0) at root=ROOT. The command line is 512 bytes at
     * byte 64 of the header, outside the header's checksum, so nothing else changes;
     * only root=, rootfstype= and errors= are replaced. Returns non-zero unless it
     * reads back right.
     */
    val RETARGET_KERNEL = """
retarget_kernel() {
  [ "${'$'}(head -c 8 "${'$'}1")" = 'ANDROID!' ] && [ "${'$'}(od -A n -t u4 -j 40 -N 4 "${'$'}1" | tr -d ' ')" = 0 ] || return 1
  rk_old=${'$'}(dd if="${'$'}1" bs=64 skip=1 count=8 2>/dev/null | tr -d '\000')
  rk_new=
  for t in ${'$'}rk_old; do
    case "${'$'}t" in root=*|rootfstype=*|errors=*) ;; *) rk_new="${'$'}rk_new ${'$'}t" ;; esac
  done
  rk_new="${'$'}{rk_new# } root=${'$'}2 rootfstype=ext4 errors=remount-ro"
  [ ${'$'}{#rk_new} -lt 512 ] || return 1
  rk_tmp=/data/local/tmp/pbos-cmdline.${'$'}${'$'}
  { printf '%s' "${'$'}rk_new"; head -c ${'$'}(expr 512 - ${'$'}{#rk_new}) /dev/zero; } > "${'$'}rk_tmp" &&
    dd if="${'$'}rk_tmp" of="${'$'}1" bs=64 seek=1 count=8 conv=notrunc 2>/dev/null
  rk_ok=${'$'}?
  rm -f "${'$'}rk_tmp"
  [ ${'$'}rk_ok = 0 ] && [ "${'$'}(dd if="${'$'}1" bs=64 skip=1 count=8 2>/dev/null | tr -d '\000')" = "${'$'}rk_new" ]
}
""".trim()

    /**
     * Shell functions for OpenABL's boot entry (see [BootEntry], the same format
     * as pb-os's pbos-update.py refresh_boot_entry):
     *  - `kernel_cmdline KERNEL`, `kernel_tag KERNEL`, `entry_conf TAG CMDLINE`;
     *  - `kernel_has KERNEL IMAGE INITRD DTBDIR`: those files are KERNEL's gzip
     *    Image, its ramdisk and (all of) its appended DTBs;
     *  - `entry_matches BOOT KERNEL`: BOOT's entry starts exactly KERNEL;
     *  - `put_entry BOOT KERNEL PIECES`: makes it so, from the pieces
     *    [BootEntry.Split.writeTo] leaves (Image.gz, initrd.gz, dtbs/), in
     *    pbos-update.py's order: kernel and initrd under KERNEL's hash, the DTBs
     *    that differ, then the entry (the switch), then the leftovers (older
     *    kernels, DTBs this KERNEL doesn't have, half writes); each file written
     *    aside, flushed and renamed over. Checks the pieces against KERNEL before
     *    it writes anything, and reads back.
     * All sizes are well under 2 GiB, so the shell's 32-bit arithmetic is enough.
     */
    val BOOT_ENTRY = """
be_u32() { od -A n -t u4 -j "§2" -N 4 "§1" | tr -d ' '; }
be_size() { wc -c < "§1" | tr -d ' '; }
be_sha() { sha256sum "§@" 2>/dev/null | cut -d' ' -f1; }
kernel_cmdline() { # both header parts (bytes 64-575, 608-1631), each up to its NUL
  printf '%s%s' "§(dd if="§1" bs=64 skip=1 count=8 2>/dev/null | tr '\000' '\n' | head -n 1)" \
    "§(dd if="§1" bs=32 skip=19 count=32 2>/dev/null | tr '\000' '\n' | head -n 1)"
}
kernel_tag() { be_sha "§1" | cut -c 1-12; }
entry_conf() { # tag cmdline
  printf 'title PB-OS\nsort-key pbos\narchitecture aa64\nlinux /${BootEntry.DIR}/Image-%s.gz\ninitrd /${BootEntry.DIR}/initrd-%s.gz\noptions %s\n' "§1" "§1" "§2"
}
kernel_has() { # kernel image initrd dtbdir
  kh_page=§(be_u32 "§1" 36) kh_ks=§(be_u32 "§1" 8) kh_rs=§(be_u32 "§1" 16)
  [ "§(head -c 8 "§1")" = 'ANDROID!' ] && [ "§(be_u32 "§1" 40)" = 0 ] && [ "§{kh_page:-0}" -gt 0 ] &&
    [ "§{kh_rs:-0}" -gt 0 ] && [ -f "§2" ] && [ -f "§3" ] || return 1
  case "§(kernel_cmdline "§1")" in *root=*) ;; *) return 1 ;; esac
  kh_is=§(be_size "§2") kh_d=0
  for kh_f in "§4"/*.dtb; do
    [ -f "§kh_f" ] && kh_d=§(( kh_d + §(be_size "§kh_f") ))
  done
  [ "§kh_is" -gt 0 ] && [ "§kh_d" -gt 0 ] && [ §(( kh_is + kh_d )) = "§kh_ks" ] || return 1
  [ "§(dd if="§1" bs="§kh_page" skip=1 count=§(( (kh_is + kh_page - 1) / kh_page )) 2>/dev/null | head -c "§kh_is" | be_sha)" = "§(be_sha "§2")" ] &&
    [ "§(od -A n -t x1 -j §(( kh_page + kh_is )) -N 4 "§1" | tr -d ' ')" = d00dfeed ] &&
    [ "§(dd if="§1" bs="§kh_page" skip=§(( 1 + (kh_ks + kh_page - 1) / kh_page )) count=§(( (kh_rs + kh_page - 1) / kh_page )) 2>/dev/null | head -c "§kh_rs" | be_sha)" = "§(be_sha "§3")" ]
}
entry_matches() { # boot kernel
  em_tag=§(kernel_tag "§2")
  [ -n "§em_tag" ] && [ "§(cat "§1/${BootEntry.CONF}" 2>/dev/null; echo .)" = "§(entry_conf "§em_tag" "§(kernel_cmdline "§2")"; echo .)" ] &&
    kernel_has "§2" "§1/${BootEntry.DIR}/Image-§em_tag.gz" "§1/${BootEntry.DIR}/initrd-§em_tag.gz" "§1/${BootEntry.DTBS}"
}
put_synced() { cp "§1" "§2.new" && sync && mv -f "§2.new" "§2"; }
put_entry() { # boot kernel pieces
  pe_tag=§(kernel_tag "§2")
  [ -n "§pe_tag" ] && kernel_has "§2" "§3/Image.gz" "§3/initrd.gz" "§3/dtbs" || return 1
  mkdir -p "§1/${BootEntry.DIR}" "§1/${BootEntry.DTBS}" "§1/${BootEntry.CONF.substringBeforeLast('/')}" &&
    put_synced "§3/Image.gz" "§1/${BootEntry.DIR}/Image-§pe_tag.gz" &&
    put_synced "§3/initrd.gz" "§1/${BootEntry.DIR}/initrd-§pe_tag.gz" || return 1
  for pe_f in "§3"/dtbs/*.dtb; do
    [ -f "§pe_f" ] || continue
    cmp -s "§pe_f" "§1/${BootEntry.DTBS}/§{pe_f##*/}" || put_synced "§pe_f" "§1/${BootEntry.DTBS}/§{pe_f##*/}" || return 1
  done
  entry_conf "§pe_tag" "§(kernel_cmdline "§2")" > "§1/${BootEntry.CONF}.new" && sync &&
    mv -f "§1/${BootEntry.CONF}.new" "§1/${BootEntry.CONF}" || return 1
  sync
  for pe_f in "§1/${BootEntry.DIR}"/*; do
    [ -f "§pe_f" ] || continue
    case "§{pe_f##*/}" in "Image-§pe_tag.gz"|"initrd-§pe_tag.gz") ;; *) rm -f "§pe_f" ;; esac
  done
  for pe_f in "§1/${BootEntry.DTBS}"/*; do
    [ -f "§pe_f" ] || continue
    case "§pe_f" in *.new) rm -f "§pe_f" ;; *.dtb) [ -f "§3/dtbs/§{pe_f##*/}" ] || rm -f "§pe_f" ;; esac
  done
  sync
  entry_matches "§1" "§2" || return 1
  for pe_f in "§3"/dtbs/*.dtb; do
    [ -f "§pe_f" ] || continue
    cmp -s "§pe_f" "§1/${BootEntry.DTBS}/§{pe_f##*/}" || return 1
  done
}
""".trim().replace("§", "$")

    /**
     * [ownCopy]: for jobs that must never run twice or write over a running script: each
     * start has a copy (and wake lock) of its own, the saved script goes once it was started
     * (a launcher run again does nothing), and the text of [ownCopy] (a lock) comes before
     * the first status.
     */
    fun prelude(name: String, id: String, statusFile: String, dryRun: Boolean = false, ownCopy: String? = null): String {
        val run = "/data/local/tmp/pbos-$name.sh"
        val own = "/data/local/tmp/pbos-$name-" + id.filter { it.isLetterOrDigit() }
        val copy = if (ownCopy == null) """
RUN_COPY=${quote(run)}
if [ "${'$'}0" != "${'$'}RUN_COPY" ]; then
  cp "${'$'}0" "${'$'}RUN_COPY" && { nohup sh "${'$'}RUN_COPY" >/dev/null 2>&1 & }
  exit 0
fi
WAKE_LOCK=${quote("pbos-$name")}
""".trim() else """
case "${'$'}0" in
  $own.*.sh) RUN_COPY=${'$'}0 ;;
  *)
    RUN_COPY=$own.${'$'}${'$'}.sh
    cp "${'$'}0" "${'$'}RUN_COPY" && { rm -f "${'$'}0"; nohup sh "${'$'}RUN_COPY" >/dev/null 2>&1 & }
    exit 0
    ;;
esac
WAKE_LOCK=pbos-$name-${'$'}${'$'}
""".trim()
        val detach = if (dryRun) "on_exit() { :; }\n" else """
$copy
on_exit() {
  { echo "${'$'}WAKE_LOCK" > /sys/power/wake_unlock; } 2>/dev/null
  rm -f "${'$'}RUN_COPY"
}
trap on_exit EXIT
{ echo "${'$'}WAKE_LOCK" > /sys/power/wake_lock; } 2>/dev/null
"""
        return """
#!/system/bin/sh
# PB-OS Installer root job "$name". Generated by the app; run as root.
set -o pipefail
$detach
JOB=${quote(id)}
STATUS=${quote(statusFile)}
EXTRA=
OWNER=${'$'}(stat -c %u:%g "${'$'}(dirname "${'$'}STATUS")" 2>/dev/null)
give() { # hand files to the app (the owner of its folder)
  [ -z "${'$'}OWNER" ] || chown "${'$'}OWNER" "${'$'}@" 2>/dev/null
  chmod 660 "${'$'}@" 2>/dev/null
}
status() { # state done total message
  { printf 'job=%s\nstate=%s\ndone=%s\ntotal=%s\nmessage=%s\n' "${'$'}JOB" "${'$'}1" "${'$'}{2:-0}" "${'$'}{3:-0}" "${'$'}4"
    [ -z "${'$'}EXTRA" ] || printf '%s\n' "${'$'}EXTRA"; } > "${'$'}STATUS.tmp" &&
    { give "${'$'}STATUS.tmp"; mv -f "${'$'}STATUS.tmp" "${'$'}STATUS"; }
}
fail() { status failed 0 0 "${'$'}1"; exit 1; }
ge() {
  [ ${'$'}{#1} -ne ${'$'}{#2} ] && { [ ${'$'}{#1} -gt ${'$'}{#2} ]; return; }
  [ "${'$'}1" = "${'$'}2" ] || [[ "${'$'}1" > "${'$'}2" ]]
}
sha() { sha256sum "${'$'}@" | cut -d' ' -f1; }
${ownCopy?.let { it + "\n" }.orEmpty()}status started
""".trimStart()
    }
}
