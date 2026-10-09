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
  │        Ready ──[Look up newest release]──▶ LoadingRelease ─▶ Offer
  │                                                                │
  │                                              [Download PB-OS] ▼
  │                                     Downloading ─▶ Verifying ─▶ Downloaded
  │
  └── card taken out (Ready, LoadingRelease, Offer)

Failed ──[Try again]──▶ back to the step the user was on
```

Continue is only enabled with a card of at least 32 GB in. Once the download
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
| AYN Thor | SM8550 | placeholder | `sm8550` |
| KONKR Pocket FIT | SM8650 | placeholder | `pocketfit` |

RP6 and Nova strings come from `getprop` on their stock Android 13. A
placeholder never matches, so those handhelds are treated as untested until a
device report fills them in.

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

## 6. Device reports

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
| `root` | `xsu`, `su` or `none` | `none` |

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

`root` is found by looking for files only (`/product/bin/xsu`, the KONKR/AYANEO
vendor helper, then the usual `su` paths), never by running them, which could
pop up a Magisk prompt.

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

## Coming next

Writing the image needs root, because Android doesn't let apps write the card
as a raw disk. The plan:

- Root, in order of preference: `su` (Magisk), the KONKR/AYANEO vendor helper
  `/product/bin/xsu` (present on stock Pocket FIT firmware), otherwise the
  handheld's own **Run script as root** setting, which runs a script the app
  writes to `/sdcard/pb-os/`.
- The script unmounts the card, refuses unless the target is the SD disk
  (`device/type == SD`, size as expected), unpacks the `.7z` parts with a
  bundled arm64 `7zz` straight onto the card, and writes progress to a file
  the app shows.
- ROCKNIX ABL: the same root script backs up both ABL slots, recognises stock,
  ROCKNIX 1.1.8 and 1.2 by hash, flashes the bundled ABL for the chip and reads
  it back. Without root, the app copies the `rocknix_abl/<chip>` folder to
  internal storage and shows plain step-by-step instructions instead.
