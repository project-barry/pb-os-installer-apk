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

- **ROCKNIX ABL 1.2** by the ROCKNIX team (the boot menu the app installs):
  the unchanged `abl_signed-SM8550.elf` and `abl_signed-SM8650.elf` from their
  [v1.2 release](https://github.com/ROCKNIX/abl/releases). The ROCKNIX team has
  not published a licence for it; it is provided as is, and all credit goes to
  them. It is a separate program the app writes to the boot loader partitions;
  the app's licence does not cover it.

The app icon is the Project Barry logo.

The full licence texts follow.
