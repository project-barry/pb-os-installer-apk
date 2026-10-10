# How does it work?

PB-OS Installer puts PB-OS on a microSD card, right on your handheld. It asks
you before each step.

1. Checks that your handheld has a chip PB-OS supports (Snapdragon 8 Gen 2 or
   8 Gen 3).
2. Checks your microSD card (32 GB or bigger) and that PB-OS has been tested
   on your handheld.
3. Finds the newest PB-OS release on GitHub.
4. Downloads it and checks that it's complete and really comes from the PB-OS
   team.
5. Writes it to the microSD card, erasing what's on it, and reads it back to
   check every byte.
6. Sets up the boot menu (ROCKNIX ABL, by the ROCKNIX team) so your handheld
   can start PB-OS from the card, after saving a copy of the original.
7. To start PB-OS: hold Volume Down while you turn the handheld on. Android is
   still there, in the same menu.

The app is free and open source:
[github.com/project-barry/pb-os-installer-apk](https://github.com/project-barry/pb-os-installer-apk)
