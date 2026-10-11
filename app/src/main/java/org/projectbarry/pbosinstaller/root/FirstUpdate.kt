package org.projectbarry.pbosinstaller.root

/**
 * Puts the newest PB-OS patch on a freshly written PB-OS, so its first start
 * installs it: the package parts, SHA256SUMS and SHA256SUMS.sig go into the
 * steamos user's ~/Downloads (where PB-OS's own updater looks), and a
 * one-time systemd job runs `konkr-update.py local-update` on the first boot,
 * after HOME has grown and before Steam starts, then restarts into the
 * updater's install screen.
 *
 * The job only runs while /home/.pbos-installer/first-update.sh exists, and
 * the script moves itself away before anything else, so it runs once even if
 * the unit file stays in /etc. If staging fails, PB-OS just starts as it is;
 * the files stay in ~/Downloads, where PB-OS Utils offers them.
 *
 * Never fails the install: [block] defines place_update, which returns
 * non-zero and the job reports first_update=failed.
 */
object FirstUpdate {
    /** [files]: root paths of the parts, SHA256SUMS and SHA256SUMS.sig, with their SHA-256. */
    data class Files(val tag: String, val files: List<Pair<String, String>>)

    const val UNIT = "pbos-first-update.service"

    fun block(update: Files): String {
        val q = ScriptKit::quote
        val copies = update.files.joinToString("\n") { (path, sha) -> "  copy_update ${q(path)} ${q(sha)} || return 1" }
        return """
# The newest PB-OS patch (${update.tag}): into ~/Downloads, with a one-time
# first-boot job that has PB-OS's own updater install it (FirstUpdate.kt).
copy_update() { # file sha256
  d="${'$'}DL/${'$'}{1##*/}"
  cp "${'$'}1" "${'$'}d" && chown 1000:1000 "${'$'}d" && chmod 644 "${'$'}d" && [ "${'$'}(sha "${'$'}d")" = "${'$'}2" ]
}
place_update() { # mounted system, mounted home
  R=${'$'}1 H=${'$'}2
  [ -f "${'$'}R/usr/share/konkr-update/konkr-update.py" ] && [ -d "${'$'}H/steamos" ] || return 1
  DL="${'$'}H/steamos/Downloads"
  mkdir -p "${'$'}DL" && chown 1000:1000 "${'$'}DL" || return 1
$copies
  mkdir -p "${'$'}H/.pbos-installer" || return 1
  cat > "${'$'}H/.pbos-installer/first-update.sh" <<'FIRST_UPDATE' || return 1
$SCRIPT
FIRST_UPDATE
  chmod 755 "${'$'}H/.pbos-installer/first-update.sh" || return 1
  # Both /etc layers: the system's own and the overlay's upper layer.
  for e in "${'$'}R/etc" "${'$'}R/var/lib/overlays/etc/upper"; do
    [ -d "${'$'}e" ] || continue
    mkdir -p "${'$'}e/systemd/system/multi-user.target.wants" || return 1
    cat > "${'$'}e/systemd/system/$UNIT" <<'UNIT' || return 1
$UNIT_FILE
UNIT
    chmod 644 "${'$'}e/systemd/system/$UNIT" &&
      ln -sfn ../$UNIT "${'$'}e/systemd/system/multi-user.target.wants/$UNIT" || return 1
  done
  sync
}
""".trim()
    }

    private val SCRIPT = """
#!/bin/bash
# Put here by the PB-OS Installer app: installs the PB-OS update it left in
# ~/Downloads, once, on the first start, then restarts into the updater.
D=/home/.pbos-installer
mv -f "${'$'}D/first-update.sh" "${'$'}D/first-update.done" || exit 0
exec >>"${'$'}D/first-update.log" 2>&1
# The job is done with itself: its unit goes (it can't run again anyway).
rm -f /etc/systemd/system/multi-user.target.wants/$UNIT /etc/systemd/system/$UNIT
echo "${'$'}(date -Iseconds) installing the update from ~/Downloads"
if python3 /usr/share/konkr-update/konkr-update.py local-update && [ -e /var/lib/konkr-update/pending ]; then
  echo "staged, restarting"
  systemctl reboot
else
  echo "not staged: PB-OS starts as it is; PB-OS Utils offers the update in ~/Downloads"
fi
""".trim()

    private val UNIT_FILE = """
[Unit]
Description=Install the PB-OS update the PB-OS Installer brought (first start only)
ConditionPathExists=/home/.pbos-installer/first-update.sh
RequiresMountsFor=/home /boot
After=local-fs.target steamos-sm8550-expand-home.service systemd-growfs@home.service systemd-growfs-root.service
Before=display-manager.service graphical.target

[Service]
Type=oneshot
ExecStart=/bin/bash /home/.pbos-installer/first-update.sh
TimeoutStartSec=30min

[Install]
WantedBy=multi-user.target
""".trim()
}
