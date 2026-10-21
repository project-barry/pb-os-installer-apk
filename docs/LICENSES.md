# Licenses

PB-OS Installer is free software. You can share it and change it under the
GNU General Public License, version 2 or (at your option) any later version.

The app includes libraries under the Apache License 2.0, which works with
version 3 of the GPL. So the app, as you install it, comes to you under
**GPL version 3**. Its source code is at
[github.com/project-barry/pb-os-installer-apk](https://github.com/project-barry/pb-os-installer-apk).

## Libraries in the app

- **AndroidX and Jetpack Compose** (Google): Apache License 2.0
- **Kotlin and kotlinx.coroutines** (JetBrains): Apache License 2.0
- **ZXing** (QR codes): Apache License 2.0
- **Bouncy Castle** (checks the download's signature): Bouncy Castle Licence, an MIT-style licence
- **7-Zip 26.04** by Igor Pavlov (unpacks PB-OS while writing it to the card): GNU LGPL for most
  code, with the unRAR restriction for some code and BSD 2- and 3-clause licences for
  other parts (full text below). It is the unchanged `7zzs` program from
  [7-zip.org](https://www.7-zip.org/), shipped inside the app as `lib7zzs.so`; its source code is
  at [7-zip.org](https://www.7-zip.org/download.html).

- **OpenABL 1.2** by the [Open Handheld Collective](https://github.com/OpenHandheldCollective)
  (the boot menu the app installs; ROCKNIX uses it too): the unchanged
  `abl_signed-SM8550.elf` and `abl_signed-SM8650.elf` from the
  [v1.2 release of Project Barry's fork](https://github.com/project-barry/OpenABL/releases/tag/v1.2),
  which is the Open Handheld Collective's
  [v1.1](https://github.com/OpenHandheldCollective/OpenABL/releases/tag/v1.1)
  plus a fix for an occasional freeze on the logo at startup.
  OpenABL is free software under the GNU General Public License, version 3 or
  (at your option) any later version, Copyright (C) 2026-present Open Handheld
  Collective, with an added permission to link with Qualcomm's boot loader.
  Each image also contains Qualcomm's boot loader and other parts under
  BSD, MIT, ISC, Apache 2.0, Zlib and OpenSSL/SSLeay licences; OpenABL's own list of
  them, its copyright notices and every licence text follow below. "This
  product includes software developed by the OpenSSL Project for use in the
  OpenSSL Toolkit (http://www.openssl.org/)" and "This product includes
  cryptographic software written by Eric Young (eay@cryptsoft.com)".
  Its complete source code is the release's `openabl-v1.2-source.tar.xz`
  (SHA-256 d8f47640e7d91695961971dea8bb8eab8270db013883736804449503fbd94da5;
  OpenABL at that version plus every source its build downloads), at
  [github.com/project-barry/OpenABL/releases/tag/v1.2](https://github.com/project-barry/OpenABL/releases/tag/v1.2).
  It is a separate program the app writes to the boot loader partitions; it
  is not part of the app's own code.

Earlier versions of the app installed ROCKNIX ABL 1.2 by the ROCKNIX team; the
app still recognizes it on handhelds that have it, but no longer carries it.

The app icon is the Project Barry logo.

The full licence texts follow.
