# PB-OS Installer

> [!IMPORTANT]
> **This repo was built with a coding agent: [Claude Code](https://www.anthropic.com/claude-code),
> running Anthropic's Claude Opus 5.5 (`claude-opus-5-5`).** Claude wrote the
> code, the commit messages and this README. People set the goals, made the
> decisions and did the hands-on testing. Review the code before you rely on
> it. See [a note from lavachemist](https://github.com/project-barry), a human, on Project Barry and generative AI.

> [!TIP]
> **Join the Project Barry community on Discord:** https://discord.gg/euPurKCWc4
>
> **Watch Project Barry on YouTube:** https://www.youtube.com/@Project-Barry

An Android app that puts [PB-OS](https://github.com/project-barry/pb-os), our port of Steam OS from the Steam Frame, on a
microSD card, right on your ARM handheld (AYN Thor, Retroid Pocket 6, Retroid Pocket Nova and Konkr Pocket Fit are currently supported; the AYN Odin 2, Odin 2 Portal and Odin 2 Mini are supported but not tested yet). No computer needed.
It can also install PB-OS over PB-OS or ArmadaOS on the handheld's internal storage, and repair a PB-OS
that stopped starting.

> [!WARNING]
> This app is new. It has installed PB-OS from start to finish on the
> handhelds in the list below, but expect rough edges, and keep the copy of
> your boot loader it asks you to save.

## See it in action

Both videos are the whole install, from stock Android to PB-OS, sped up where
it waits.

| KONKR Pocket FIT | AYN Thor |
|---|---|
| [![PB-OS install on the KONKR Pocket FIT, no computer](https://img.youtube.com/vi/reaYRtysAOQ/hqdefault.jpg)](https://youtu.be/reaYRtysAOQ) | [![PB-OS install on the AYN Thor, no computer](https://img.youtube.com/vi/EbYO5bSAKnU/hqdefault.jpg)](https://youtu.be/EbYO5bSAKnU) |
| [Watch on YouTube](https://youtu.be/reaYRtysAOQ) | [Watch on YouTube](https://youtu.be/EbYO5bSAKnU) |

## What you need

- A handheld PB-OS supports (see the list below).
- A microSD card of 32 GB or bigger. **Everything on the card will be
  erased.** Copy off anything you want to keep first.
- Wi-Fi, and about 5 GB of free space on the handheld for the download.

## Handhelds

| Handheld | Does the app recognise it yet? |
|---|---|
| Retroid Pocket 6 | Yes |
| Retroid Pocket Nova | Yes |
| AYN Thor | Yes |
| KONKR Pocket FIT | Yes |
| AYN Odin 2 | Yes, but PB-OS hasn't been tested on one yet |
| AYN Odin 2 Portal | Yes, but PB-OS hasn't been tested on one yet |
| AYN Odin 2 Mini | Yes, but PB-OS hasn't been tested on one yet |

If yours isn't on the list, see
[My handheld isn't recognised](#my-handheld-isnt-recognised).

## How it works

The app asks you before each step and never moves on by itself.

1. **Open the app.** It checks that your handheld has a chip PB-OS supports
   (Snapdragon 8 Gen 2 or 8 Gen 3). If not, it stops here.
2. **Put in a microSD card and tap Continue.** The app checks that the card is
   big enough and that your handheld is one we have tested PB-OS on, so nobody
   ends up with a system that doesn't work on their hardware.
3. **Tap Let's Go!** The app finds the newest PB-OS on GitHub and shows its
   size.
4. **Download.** Tick "I understand the card will be erased" and tap
   **Download PB-OS** to start the download. You can leave the app while it downloads. When it's
   done, the app checks that the download is complete and really comes from
   the PB-OS team.
5. **Write to SD Card.** The app writes PB-OS to the card, then reads it all
   back to check every byte. This takes about 10 minutes. Keep the card in;
   the screen stays on while it works.
6. **Set Up Boot Menu.** Your handheld needs a boot menu to start PB-OS from
   the card. First the app saves a copy of your handheld's own boot loader.
   **Save that copy somewhere else too** (a USB drive, Google Drive, your
   computer): you need it to go back to stock Android or to install Android
   updates. Then tap **Install Boot Menu**.
7. **Start PB-OS.** Turn the handheld off and hold **Volume Down** while you
   turn it on: the OpenABL menu opens (Volume Up and Down move, Power
   selects). Set **Boot mode** to Linux, **Boot source** to SD card and
   **Device model** to your handheld, then choose **Start**. The menu keeps
   these settings. Android is still there: hold **Volume Up** while you turn
   the handheld on, or set **Boot mode** back to Android.

**The newest update comes along.** If PB-OS has a newer update than the
download, the app downloads it too. PB-OS installs it on its first start and
restarts once, so you start on the newest version instead of updating right
away.

Steps 5 and 6 need full access to the handheld ("root"). On the Retroid
Pocket 6, Retroid Pocket Nova, AYN Thor and KONKR Pocket FIT the app gets it
by itself. If a Retroid handheld refuses, the app saves a small script and
shows you how to run it from **Handheld Settings → Advanced → Run Script as
Root**.

You can use the touch screen or the controller: the d-pad moves between
buttons and **A** presses them.

At the bottom of the app, **How Does it Work?** shows these steps in short
([docs/ABOUT.md](docs/ABOUT.md)), with a QR code for this page.

## Already have PB-OS or ArmadaOS on internal storage?

On the first screen, tap **Install Over Internal Linux**. The app checks
internal storage (it only reads) and shows what it found: Android's size, the
Linux space, and how PB-OS will use it.

- **Android stays exactly as it is.** PB-OS goes into the space the old Linux
  install already has; Android keeps the size you gave it.
- **Over ArmadaOS**, that space needs at least 40.8 GB; ArmadaOS is erased,
  games included.
- **Over PB-OS**, you can keep your games and files (**Keep my games and
  files**, on by default): your installed games, saves, Steam login and Decky
  stay; the system is new, so Wi-Fi networks and system settings start fresh.

Then download as usual, and tap **Install PB-OS** five times to confirm. In
the boot menu, set the boot source to **Internal**.

## Repairing PB-OS

**Tools** (at the bottom of the app) → **Repair PB-OS** checks PB-OS on the
microSD card and on internal storage. The check only reads; nothing changes
until you pick a fix. It can:

- **undo the last update**, when PB-OS stopped starting after it (PB-OS's own
  updater puts back everything the update changed on the next start; your
  games and files stay),
- point PB-OS's kernel at the right place if it looks in the wrong one,
- put the boot menu back if an Android update removed it.

## Updating the app

From version 0.3.0, the app tells you when a newer version is out (when you
tap **Let's Go!**) and updates itself with **Update App**. Android asks you to
confirm, and the first time to allow installing apps from PB-OS Installer.

## My handheld isn't recognised

Tap **Send Device Report**. The app shows you exactly what it will send, and
only sends it when you tap **Send**. The report goes to the Project Barry
Discord server so we can add your handheld.

**What's in the Report?** (at the bottom of the app) shows a short page
explaining what the report contains and how we use it, followed by your
handheld's exact report. You can also read the page here:
[docs/DEVICE-REPORT.md](docs/DEVICE-REPORT.md).

If you'd rather send it yourself, open **What's in the Report?**, tap **Copy
device info** under your report and paste it to us on Discord.

## Getting the app

Download the newest `.apk` from
[Releases](https://github.com/project-barry/pb-os-installer-apk/releases) on
your handheld and open it. Android asks you to allow installing apps from your
browser or file manager the first time.

## The boot menu

The boot menu is **OpenABL** (version 1.2), made by the
[Open Handheld Collective](https://github.com/OpenHandheldCollective/OpenABL);
ROCKNIX uses it too. Version 1.2 comes from
[Project Barry's fork](https://github.com/project-barry/OpenABL/releases/tag/v1.2):
their v1.1 plus a fix for an occasional freeze on the logo at startup. The app
carries an unchanged copy of it for the Snapdragon 8 Gen 2 and 8 Gen 3, so it
can install it without another download, and checks it before installing.
OpenABL is free software under the GNU GPL, version 3 or later; its source code
is on the [v1.2 release page](https://github.com/project-barry/OpenABL/releases/tag/v1.2)
(`openabl-v1.2-source.tar.xz`). All credit for OpenABL goes to the Open Handheld
Collective.

Handhelds set up by app 0.4.1 have OpenABL 1.1; Repair → Reinstall the Boot
Menu puts 1.2 in its place. Handhelds set up by earlier versions of the app have
the **ROCKNIX ABL** 1.2 boot menu by the ROCKNIX team. The app still recognizes
both and offers OpenABL 1.2 in their place.

## More detail

How the app works inside, how to build it and how the report service is set
up: [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).

## License

PB-OS Installer is free software under the GNU General Public License,
version 2 or (at your option) any later version (`GPL-2.0-or-later`).

The app includes libraries under the Apache License 2.0 (AndroidX, Jetpack
Compose, Kotlin, ZXing), which works with GPL version 3 but not version 2
alone, so the app as built and installed is distributed under **GPL version
3**. Bouncy Castle uses an MIT-style licence, which works with both.

The app also carries two programs it doesn't link to, unchanged: 7-Zip's
`7zzs` (GNU LGPL) and the OpenABL boot menu (GNU GPL version 3 or later, see
[The boot menu](#the-boot-menu)). Details:
[docs/LICENSES.md](docs/LICENSES.md).

- [LICENSE](LICENSE): GPL version 2
- [LICENSES/](LICENSES/): GPL version 3, Apache License 2.0, Bouncy Castle Licence,
  7-Zip's licence, and OpenABL's notices and licence texts (`OpenABL-*`)
- [docs/LICENSES.md](docs/LICENSES.md): the libraries in the app, also shown in
  the app under **Licenses**
- The device-report relay ([relay/](relay/)) is part of this repo and under the
  same licence.
