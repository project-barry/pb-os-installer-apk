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

An Android app that puts [PB-OS](https://github.com/project-barry/pb-os) on a
microSD card, right on your handheld. No computer needed.

> [!WARNING]
> This app is still being built. Right now it checks your handheld and
> downloads PB-OS. Writing PB-OS onto the card is the next step.

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
| AYN Thor | Not yet |
| KONKR Pocket FIT | Not yet |

PB-OS runs on all four, but the app still needs a report from an AYN Thor and
a KONKR Pocket FIT before it recognises them. If you have one, see
[My handheld isn't recognised](#my-handheld-isnt-recognised).

## How it works

1. **Open the app.** It checks that your handheld has a chip PB-OS supports
   (Snapdragon 8 Gen 2 or 8 Gen 3). If not, it stops here.
2. **Put in a microSD card.** If there's no card, the app waits and carries on
   by itself as soon as you put one in.
3. **The app checks your handheld.** It only installs on handhelds we have
   tested PB-OS on, so nobody ends up with a system that doesn't work on
   their hardware.
4. **Download.** Tick "I understand the card will be erased" and tap
   **Download PB-OS**. You can leave the app while it downloads. When it's
   done, the app checks that the download is complete and really comes from
   the PB-OS team.

You can use the touch screen or the controller: the d-pad moves between
buttons and **A** presses them.

## My handheld isn't recognised

Tap **Send Device Report**. The app shows you exactly what it will send, and
only sends it when you tap **Send**. The report goes to the Project Barry
Discord server so we can add your handheld. You can send one a day.

**What's in the report?** opens a short page explaining what the report
contains and how we use it: [docs/DEVICE-REPORT.md](docs/DEVICE-REPORT.md).

If you'd rather send it yourself, tap **Copy device info** and paste it to us
on Discord.

## Getting the app

There's no public download yet. Testers get the app from the PB-OS team.

## More detail

How the app works inside, how to build it and how the report service is set
up: [docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md).

## License

GPL-2.0, see [LICENSE](LICENSE).
