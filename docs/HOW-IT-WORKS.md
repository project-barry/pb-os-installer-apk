# How PB-OS Installer works

The technical side of the app: what each step checks, where the code is, how
to build and test it, and how device reports reach Discord. For the
user-facing overview, see the [README](../README.md).

Kotlin, Jetpack Compose, minSdk 30, targetSdk 35. Package
`org.projectbarry.pbosinstaller`.

## Flow

The screen is a single state machine in
[InstallerViewModel.kt](../app/src/main/java/org/projectbarry/pbosinstaller/ui/InstallerViewModel.kt).
**The app only moves forward when the user taps a button.** It does the local
checks (chip, model, card size) by itself, because they change nothing, and it
goes back to the card step if the card is taken out before the download, but
inserting a card never moves it on.

```
WrongChip                                       (stop)

Card ──[Continue]──▶ Untested                    (stop, unless -PallowUntested=true)
  ▲          │
  │          ▼
  │        Ready ──[Let's Go!]──▶ LoadingRelease ─▶ Offer
  │                                                                │
  │                                              [Download PB-OS] ▼
  │                                     Downloading ─▶ Verifying ─▶ Downloaded
  │
  └── card taken out (Ready, LoadingRelease, Offer)

Failed ──[Try again]──▶ back to the step the user was on
```

The device card (model, chip, **Send Device Report**) shows only on the
Card, Untested and WrongChip screens; from Ready on, the step card has the
page to itself. Continue is only enabled with a card of at least 32 GB in. Once the download
has started, taking the card out no longer interrupts it; the card only
matters again for writing, which will also wait for the user's go-ahead.

## 1. Chip check

[device/Soc.kt](../app/src/main/java/org/projectbarry/pbosinstaller/device/Soc.kt),
[device/DeviceInfo.kt](../app/src/main/java/org/projectbarry/pbosinstaller/device/DeviceInfo.kt)

- Reads `Build.SOC_MODEL` (`ro.soc.model`).
- `SM8550*` / `QCS8550*` → SM8550 image, `SM8650*` / `QCS8650*` → SM8650 image.
  Retroid reports the QCS name: both the RP6 and the Nova say `QCS8550`.
- Only when the model is empty or `unknown`, `ro.board.platform` decides:
  `kalama` = SM8550, `pineapple` = SM8650.
- Anything else (for example the 8 Elite edition of the Pocket FIT, SM8750) is
  refused.

## 2. microSD card

[storage/SdCardWatcher.kt](../app/src/main/java/org/projectbarry/pbosinstaller/storage/SdCardWatcher.kt),
[storage/SdBlock.kt](../app/src/main/java/org/projectbarry/pbosinstaller/storage/SdBlock.kt)

- Watches `StorageManager` volumes (removable, not primary) through a
  `StorageVolumeCallback` and the `ACTION_MEDIA_*` broadcasts, so inserting a
  card updates the card step at once (Continue enables); it never moves on by
  itself.
- A card counts as present whatever is on it, including Linux file systems
  that Android calls "unmountable".
- **Size.** A card that already holds PB-OS appears as one volume per
  partition, and the first one is the 536 MB BOOT partition. So the app reads
  the whole disk from `/sys/block/mmcblk*/size` (the `mmcblk` device whose
  `device/type` is `SD`), which apps can read on the Nova. When that's not
  readable and the card has several volumes, the size counts as unknown and
  the writing step checks it.
- Minimum: 28,000,000,000 bytes (a "32 GB" card). The image is about 16.5 GB.
- Seen on the Nova: the card is `mmcblk1` (Android lists it as disk `179,0`)
  and reports `removable=0`. The writing step must therefore find the card by
  `device/type == SD`, never by a fixed name or the removable flag.

## 3. Tested handhelds

[device/Devices.kt](../app/src/main/java/org/projectbarry/pbosinstaller/device/Devices.kt)

A handheld is tested when `Build.MANUFACTURER` and `Build.MODEL` both match an
entry (case ignored) **and** its chip matches the entry's chip.

| Handheld | Chip | `MANUFACTURER` / `MODEL` | Image |
|---|---|---|---|
| Retroid Pocket 6 | SM8550 | `Moorechip` / `Retroid Pocket 6` | `sm8550` |
| Retroid Pocket Nova | SM8550 | `Moorechip` / `Retroid Pocket Nova` | `sm8550` |
| AYN Thor | SM8550 | `AYN` / `AYN Thor` | `sm8550` |
| KONKR Pocket FIT | SM8650 | `AYANEO` / `Pocket FIT` | `pocketfit` |
| AYN Odin 2 \* | SM8550 | `AYN` / `Odin2` | `pb-os` |
| AYN Odin 2 Portal \* | SM8550 | `AYN` / `Odin2 Portal` | `pb-os` |
| AYN Odin 2 Mini \* | SM8550 | `AYN` / `Odin2 Mini` | `pb-os` |

\* Supported but never run on one (`hardwareTested = false`): the app installs
PB-OS and warns that it is untested. Their strings come from the stock
firmware (odm/vendor `build.prop`; `ro.product.property_source_order` puts
odm first), not from a device report.

The strings come from `getprop` on each handheld's stock Android (13 on the
RP6, Nova and Thor, 14 on the Pocket FIT). A future entry can use the
placeholder value, which never matches, until a device report fills it in.

### Untested handhelds

`-PallowUntested=true` (or `allowUntested=true` in `gradle.properties`) sets
`BuildConfig.ALLOW_UNTESTED`: any SM8550 or SM8650 handheld then gets through,
uses the image for its chip, and the offer screen shows an "untested device"
warning. Builds handed out to people keep it off.

## 4. Finding and downloading the image

[release/Release.kt](../app/src/main/java/org/projectbarry/pbosinstaller/release/Release.kt),
[release/ImageDownloader.kt](../app/src/main/java/org/projectbarry/pbosinstaller/release/ImageDownloader.kt)

- `GET https://api.github.com/repos/project-barry/pb-os/releases/latest`.
  pb-os marks only its newest feature release as Latest, and patch releases
  carry no image, so Latest is always the image to install.
- Image assets are named `pb-os-<tag>-<image>.img.7z.001`, `.002`, ... The
  parts must run from 001 with no gap. `SHA256SUMS` and `SHA256SUMS.sig` must
  be present too.
- Parts download with Android's `DownloadManager` into
  `Android/data/org.projectbarry.pbosinstaller/files/images/` on internal
  storage. Downloads continue when the app is closed, show a notification, and
  pick up again after Wi-Fi drops.
- `DownloadManager` creates each file at full size before it has the data, so
  a part only counts as finished once a "done" marker is saved after
  `STATUS_SUCCESSFUL`. Restarting the app reattaches to running downloads.
- Free space needed: the missing parts plus 256 MB. Parts from older releases
  are deleted.

## 5. Checking the download

[release/SshSig.kt](../app/src/main/java/org/projectbarry/pbosinstaller/release/SshSig.kt)

1. `SHA256SUMS.sig` is an `ssh-keygen -Y sign` signature (SSHSIG, ssh-ed25519).
   The app checks it the way `ssh-keygen -Y verify` does: the signing key must
   be the pb-os release key (from pb-os
   `external-and-mods/konkr-update/allowed_signers`) and the namespace
   `pb-os-update`. Ed25519 comes from BouncyCastle's lightweight API.
2. Every downloaded part's SHA-256 must match its line in `SHA256SUMS`. A part
   that doesn't is deleted.

The unit tests check the real alpha-v0.5.2 `SHA256SUMS` and signature, a
tampered copy, another key and another namespace.

### The newest update comes along

[release/Updates.kt](../app/src/main/java/org/projectbarry/pbosinstaller/release/Updates.kt),
[root/FirstUpdate.kt](../app/src/main/java/org/projectbarry/pbosinstaller/root/FirstUpdate.kt)

- With the image, the app looks up the newest patch for it: releases of
  `pb-os-updates`, then `pb-os` (a tag's first copy wins; drafts and
  `pb-os-dev` never), newer than the image's tag, with
  `pb-os-<tag>-<image>.from-<image tag>.delta.tar.gz.NNN` (unbroken) plus
  `SHA256SUMS` and `SHA256SUMS.sig`.
- Patches are cumulative deltas from their feature release, and the feature
  release's own updater installs one from `~/Downloads` (it accepts any signed
  delta built from its version), so only the newest is needed. Bridge releases
  such as alpha-v0.5.2.3 are for updaters looking online. Checked: the
  alpha-v0.5.2 updater's `validate_archive` accepts the alpha-v0.5.2.10 delta.
- It downloads into `files/updates/`, checks the signature and the joined
  package's hash, and keeps `SHA256SUMS(.sig)` as published.
- After writing (card or internal), the root job mounts the new system and
  home (card: MBR partitions 2 and 3 through loop devices) and puts the parts
  and `SHA256SUMS(.sig)` into `/home/steamos/Downloads` (uid 1000), plus
  `pbos-first-update.service` in both `/etc` layers and
  `/home/.pbos-installer/first-update.sh`. On the first start, after
  expand-home and growfs and before the display manager, it runs
  `konkr-update.py local-update` and restarts into the updater's install
  screen. The script moves itself away first, so it runs once; the log is
  `/home/.pbos-installer/first-update.log`.
- Never fails the install: lookup or placing problems only mean PB-OS starts
  on the image's version (`first_update=failed` in the status, shown on the
  last screen).

### The app updates itself

[update/AppUpdate.kt](../app/src/main/java/org/projectbarry/pbosinstaller/update/AppUpdate.kt)

- On the Let's Go! tap (the app's first time online) it also reads this repo's
  latest release: tag `vX.Y.Z` newer than its own version, with
  `pb-os-installer-vX.Y.Z.apk` and `SHA256SUMS`. A banner offers **Update App**.
- Update App downloads the APK, checks its checksum, and checks it is this
  package, a higher versionCode and signed with the installed app's key (a
  debug build gets a clear message instead of Android's failure). Then it hands
  it to Android's `PackageInstaller`: the first time, Android asks to allow
  installs from PB-OS (the banner opens that page), then Android shows its own
  "Do you want to update this app?". Android replaces the app and closes it.
- Tested on the emulator with a local fake release (0.2.1 → 0.2.2, same debug
  key): banner, download, permission page, Android's prompt, 0.2.2 installed.
  Versions up to 0.2.1 can't do this, so the first self-update is from the
  release that ships it.

## 6. Installing over Linux on internal storage

[internal/](../app/src/main/java/org/projectbarry/pbosinstaller/internal/)
**Not yet tried on a handheld's own storage.**

For people who already run PB-OS or ArmadaOS from internal storage: PB-OS goes
over it in the space it already has, without a card. Android's partitions,
userdata included, are never written, so Android keeps the size it was given.

```
Card ──[Install Over Internal Linux]──▶ InternalChecking ─▶ InternalFound ──[Continue]──▶ Ready … Downloaded
                                                       └─▶ InternalRefused ──[Back]──▶ Card
Downloaded ──[Install PB-OS ×5]──▶ InternalWriting ─▶ InternalDone
```

- **Check** (root, reads only): finds the disk userdata is on
  (`/dev/block/by-name/userdata`, then its parent in `/sys/class/block`) and
  saves its first and last MiB plus the kernel's partitions (in use or not).
  The app reads the GPT itself ([Gpt.kt](../app/src/main/java/org/projectbarry/pbosinstaller/internal/Gpt.kt)):
  both copies must be intact and agree.
- **What it accepts** ([InternalLayout.kt](../app/src/main/java/org/projectbarry/pbosinstaller/internal/InternalLayout.kt)):
  after userdata exactly `ROCKNIX, STORAGE, HOME` (PB-OS) or
  `ARMADA, ARMADA_BOOT, ARMADA_ROOT` (ArmadaOS); nameless leftovers inside
  userdata (UNINSTALL CFW) are ignored, anything else is refused.
  - PB-OS: the same partitions, the table isn't written. Needs ROCKNIX ≥
    256 MiB, STORAGE ≥ 18 GiB, HOME ≥ 16 GiB.
  - ArmadaOS: its space becomes ROCKNIX 2 GiB, STORAGE 20 GiB, HOME the rest,
    in the same table slots, never past Armada's end.
  - **Space check:** the Linux space must be at least 38 GiB (2 + 20 + 16),
    and the image must fit STORAGE (checked again by the script).
- **Seen on the Nova (Android 13):** toybox `tr -d '\000'`, 64-bit `expr`,
  `losetup -r -o -S`, `dd conv=notrunc`, ext4 (64bit, metadata_csum) mounted
  read-write and vfat with 4 KiB sectors on a 512-byte loop all work.
- **Install** ([InternalScript.kt](../app/src/main/java/org/projectbarry/pbosinstaller/internal/InternalScript.kt)):
  everything goes through loop devices over the disk at the planned byte
  ranges, because the kernel keeps Android's view of the partitions until the
  next boot.
  1. Checks first: same disk, table byte-for-byte as the app read it, nothing
     in the Linux space mounted or held, download and table files intact.
  2. The image is unpacked into STORAGE and read back against the signed
     checksum. From here on the old install is gone.
  3. ROCKNIX: FAT32 in the disk's sector size (the image's 512-byte FAT can't
     be mounted on 4K UFS), made by the image's own `mkfs.fat` run through its
     glibc loader from the image's system mounted read-only. Android's
     `newfs_msdos` can't: it always takes the loop device's 512-byte sectors
     (seen on the Nova). Then the image's boot files, the
     KERNEL's command line changed to `root=PARTLABEL=STORAGE` (the
     command line is outside the boot image's checksum).
  4. Home into HOME (skipped when an existing PB-OS HOME is kept), then the
     system to the start of STORAGE. That copy always
     moves to a lower address, so nothing is overwritten before it's read.
     Each copy is checked against its source.
  5. fstab mounts by partition name (a PB-OS card may be in too, with the same
     filesystem IDs); `x-systemd.growfs` and expand-home grow both filesystems
     on the first boot.
  6. ArmadaOS only: the new table, backup copy first and primary header last,
     read back.
- **Keep HOME** (PB-OS over PB-OS only, on by default): HOME isn't written at
  all; the script first checks it's ext4 that mounts with `/steamos` in it.
  Games, saves, the Steam login and Decky stay; the system (`/etc`, `/var`:
  Wi-Fi, password, ssh, Tailscale) is new. Bundled Decky plugins stay at their
  old versions until the next update.
- The 5-tap button follows the boot loader backup's rule. The ABL isn't
  touched: an internal Linux install already has a boot menu (ROCKNIX's
  or OpenABL).

### Dry run on a handheld

`InternalDryRunTest` writes `app/build/internal-dry-run/`: a 60 GiB fake disk
with Android and ArmadaOS (`disk-head.bin`, `disk-tail.bin`), the new table
files and `install.sh`, which installs into `/data/local/tmp/pbos-dry/disk.img`
instead of internal storage. On the handheld, as root:

```sh
D=/data/local/tmp/pbos-dry
truncate -s 64424509440 $D/disk.img          # sparse; needs ~20 GB free
dd if=$D/disk-head.bin of=$D/disk.img conv=notrunc
dd if=$D/disk-tail.bin of=$D/disk.img bs=1048576 seek=61439 conv=notrunc
sed -i "s|SEVENZIP_PATH|<app lib dir>/lib7zzs.so|; s|IMAGE_DIR|<folder with the parts>|" $D/install.sh
sh $D/install.sh; cat $D/status
```

## 7. Device reports

[report/DeviceReport.kt](../app/src/main/java/org/projectbarry/pbosinstaller/report/DeviceReport.kt),
[report/ReportSender.kt](../app/src/main/java/org/projectbarry/pbosinstaller/report/ReportSender.kt),
[relay/](../relay/)

**Send Device Report** posts a JSON object to the relay, a Cloudflare Worker,
which posts it to a Discord channel. The Discord webhook is a secret of the
Worker and is never in the app or this repo. The **What's in the
Report?** button at the bottom of the app shows [DEVICE-REPORT.md](DEVICE-REPORT.md), the plain-English
version of this section for users, in a pop-up. The build copies that file
into the APK (`CopyAppDocs` in `app/build.gradle.kts`), so each build shows
the page as it was when it was built, with no browser or internet needed.

### Fields

The report is built from this fixed list and nothing else:

| Field | Source | Example (Nova) |
|---|---|---|
| `app_version` | app version | `0.1.0` |
| `manufacturer` | `Build.MANUFACTURER` | `Moorechip` |
| `brand` | `Build.BRAND` | `qti` |
| `model` | `Build.MODEL` | `Retroid Pocket Nova` |
| `device` | `Build.DEVICE` | `kalama` |
| `product` | `Build.PRODUCT` | `kalama` |
| `soc_manufacturer` | `Build.SOC_MANUFACTURER` | `QTI` |
| `soc_model` | `Build.SOC_MODEL` | `QCS8550` |
| `board_platform` | `ro.board.platform` | `kalama` |
| `android` | Android version and API level | `13 (API 33)` |
| `fingerprint` | `Build.FINGERPRINT` | see below |
| `sd_block` | the SD card's disk in `/sys/block` | `mmcblk1` |
| `sd_type` | `SD`, or `none` without a readable card | `SD` |
| `root` | `xsu`, `pservice`, `su` or `none` | `pservice` |

**`fingerprint`** is Android's build fingerprint, the firmware's identity, not
a biometric. Format:

```
brand/product/device:release/build-id/incremental:type/tags
qti/kalama/kalama:13/TKQ1.231222.001/eng.RPN.20260722.081626:user/release-keys
```

It is identical on every unit running the same firmware and changes only with
a firmware update; any app can read it without a permission. It tells us which
firmware a report comes from, for example to see whether a firmware update
changed how the card or the root tool behaves.

The `incremental` part of a firmware built with `eng.` versioning is
`eng.<build user>.<date>.<time>`: the account name of the computer that
compiled the firmware. On stock firmware that's the maker's build machine
(`RPN` on the Nova, `RP6` on the RP6). On firmware someone compiled at home it
would be that person's own computer user name. We send the fingerprint
unchanged; the README explains this to users.

`root` is found by looking only, never by running anything, which could pop
up a Magisk prompt: `/product/bin/xsu` (the KONKR/AYANEO vendor helper), then
Retroid's `PServerBinder` service, then the usual `su` paths.

Nothing else is read: no serial number, Android ID, account, IMEI, location,
network or Wi-Fi details.

### Cleaning and limits

- Every value is reduced to the characters the relay accepts
  (`A-Z a-z 0-9 space . _ : / ( ) + , = -`, anything else becomes `_`) and cut
  to 120 characters. A unit test reads `relay/worker.js` and checks that the
  app's field list and cleaned values match what the relay accepts.
- The app shows the exact report before sending and sends only on **Send**.
- One report per install per day (stored in the app's own preferences).
  A report the relay already has (`duplicate`) also counts.
- The relay adds its own limits: see [relay/README.md](../relay/README.md).

### Relay address

The relay's address is not in this repo. Builds take it from the Gradle
property `pbosReportUrl` (for example in `~/.gradle/gradle.properties`) or the
environment variable `PBOS_REPORT_URL`:

```properties
pbosReportUrl=https://pbos-device-report.<subdomain>.workers.dev/report
```

A build without it hides the **Send Device Report** button; **Copy device
info** still works. Setting up the relay itself: [relay/README.md](../relay/README.md).

## Pages in the app

The footer links open pop-ups built from pages in this repo, copied into the
APK by the build (`CopyAppDocs`), so each build shows them as they were when
it was built: **How Does it Work?** ([ABOUT.md](ABOUT.md), with a QR code for
the GitHub repo), **What's in the Report?** ([DEVICE-REPORT.md](DEVICE-REPORT.md),
with a QR code for the Discord invite, followed by this handheld's exact report
as **Send Device Report** would send it) and **Licenses** ([LICENSES.md](LICENSES.md)
plus `LICENSES/`). **GitHub** and **Discord** are small pop-ups built in code
(`LinkDialog` in `DocDialog.kt`): one line of text, the tappable link and a QR
code for it. The pop-ups read a small Markdown subset (`SimpleMarkdown`):
headings, `-` and `1.` lists, paragraphs, bold, italic and links.

## Licences

The app's code is `GPL-2.0-or-later`. Every library in the release APK
(83 of them, from `./gradlew :app:dependencies --configuration
releaseRuntimeClasspath`) is under the Apache License 2.0 except Bouncy
Castle (MIT-style). Apache-2.0 is compatible with GPL version 3 but not
version 2 alone, so the APK as a whole is distributed under GPL version 3.
None of the libraries ships a `NOTICE` file.

The **Licenses** button shows [LICENSES.md](LICENSES.md) and the full texts in
`LICENSES/`, copied into the APK by the build (`CopyAppDocs`), which satisfies
the Apache and MIT requirement to ship the licence texts with the app. When
adding a library, check its licence, add it to `LICENSES.md`, and add its
licence text to `LICENSES/` if it's a new one.

## Building

Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. Release builds
(`assembleRelease`) are minified and need a signing key, which isn't set up
yet.

## Testing on an emulator

Debug builds accept extras that make the app pretend to be another handheld
(release builds ignore them):

```sh
adb shell am start -n org.projectbarry.pbosinstaller/.MainActivity \
  --es fakeManufacturer Moorechip --es fakeModel "'Retroid Pocket 6'" --es fakeSoc QCS8550
```

A virtual SD card:

```sh
adb shell sm set-virtual-disk true
adb shell sm list-disks                 # e.g. disk:7,304
adb shell sm partition disk:7,304 public
```

The virtual card is 536 MB, so the "card too small" warning shows. For a full
download test, give the emulator at least 16 GB of data space
(`disk.dataPartition.size` in the AVD's `config.ini`).

## Root

Writing the card and the boot loader steps need root, because Android doesn't
let apps write the card as a raw disk or touch the `abl_a`/`abl_b` partitions.
The app writes each job as a shell script into its own folder and starts it
the first way that works:

1. **`xsu`** (KONKR/AYANEO): `/product/bin/xsu` talks to the vendor's root
   daemon over a socket any app may use. The app runs `xsu sh <script>`.
2. **Retroid's root service**: the binder service `PServerBinder`, served by
   `/system/bin/pservice` (the service behind Handheld Settings → Advanced →
   Run Script as Root). Any app may call it: call code 0 with the string array
   `[command, "1"]` runs the command as root and replies with its output. The
   app checks it with `id -u` first. `service call` can't reach it because the
   service declares no interface name, so the app calls it directly.
3. **Run Script as Root** by hand, when neither works on a Retroid: the app
   saves a one-line launcher (`sh <script>`) in Downloads and the user runs it
   from Handheld Settings. That runner sends a file line by line, which is why
   the launcher is one line.

Every job script first copies itself to `/data/local/tmp` and carries on in
the background, so the call returns at once and an Android system restart
can't stop it. It reports progress to a status file the app reads.
