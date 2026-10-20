# How does it work?

PB-OS Installer puts PB-OS on a microSD card, right on your handheld. It asks
you before each step.

1. Checks that your handheld has a chip PB-OS supports (Snapdragon 8 Gen 2 or
   8 Gen 3).
2. Checks your microSD card (32 GB or bigger) and that PB-OS supports your
   handheld.
3. Finds the newest PB-OS release on GitHub.
4. Downloads it and checks that it's complete and really comes from the PB-OS
   team.
5. Writes it to the microSD card, erasing what's on it, and reads it back to
   check every byte.
6. Sets up the boot menu (OpenABL, by the Open Handheld Collective) so your
   handheld can start PB-OS from the card, after saving a copy of the
   original.
7. To start PB-OS: hold Volume Down while you turn the handheld on, then set
   Boot mode to Linux and Boot source to SD card. Android is still there:
   hold Volume Up while you turn it on.

If PB-OS has a newer update than the download, it comes along and PB-OS
installs it on its first start.

Already have PB-OS or ArmadaOS on internal storage? **Install Over Internal
Linux** puts PB-OS in its place without touching Android (over PB-OS you can
keep your games). **Tools → Repair PB-OS** checks an install that stopped
starting and offers fixes.

The app is free and open source:
[github.com/project-barry/pb-os-installer-apk](https://github.com/project-barry/pb-os-installer-apk)
