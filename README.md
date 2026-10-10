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

An Android app that puts [PB-OS](https://github.com/project-barry/pb-os), our port of Steam OS, on a
microSD card, right on your handheld. No computer needed.

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

- A handheld PB-OS has been tested on (see the list below).
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
   turn it on. In the menu, set the device model, set the boot mode to Linux
   and the boot source to the SD card, then start. Android is still there:
   choose it in the same menu.

Steps 5 and 6 need full access to the handheld ("root"). On the Retroid
Pocket 6, Retroid Pocket Nova, AYN Thor and KONKR Pocket FIT the app gets it
by itself. If a Retroid handheld refuses, the app saves a small script and
shows you how to run it from **Handheld Settings → Advanced → Run Script as
Root**.

You can use the touch screen or the controller: the d-pad moves between
buttons and **A** presses them.

At the bottom of the app, **How Does it Work?** shows these steps in short
([docs/ABOUT.md](docs/ABOUT.md)), with a QR code for this page.

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

The boot menu is **ROCKNIX ABL** (version 1.2), made by the
[ROCKNIX team](https://github.com/ROCKNIX/abl). The app carries an unchanged
copy of their release for the Snapdragon 8 Gen 2 and 8 Gen 3, so it can
install it without another download, and checks it before installing. ROCKNIX
ABL is provided as is; its authors have not published a licence for it. All
credit for it goes to the ROCKNIX team.

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
`7zzs` (GNU LGPL) and the ROCKNIX ABL boot menu (see
[The boot menu](#the-boot-menu)). Details:
[docs/LICENSES.md](docs/LICENSES.md).

- [LICENSE](LICENSE): GPL version 2
- [LICENSES/](LICENSES/): GPL version 3, Apache License 2.0, Bouncy Castle Licence
- [docs/LICENSES.md](docs/LICENSES.md): the libraries in the app, also shown in
  the app under **Licenses**
- The device-report relay ([relay/](relay/)) is part of this repo and under the
  same licence.
