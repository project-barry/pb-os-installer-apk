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
microSD card, right on the handheld, with no PC.

> [!WARNING]
> Work in progress. The app downloads and checks the image today. Writing it
> to the card and the ROCKNIX ABL steps come next.

## What it does

1. **Checks the chip.** PB-OS has images for Snapdragon 8 Gen 2 (SM8550, also
   reported as QCS8550) and Snapdragon 8 Gen 3 (SM8650). Anything else stops here.
2. **Waits for a microSD card.** Carries on by itself once a card is in.
   The card must be 32 GB or bigger.
3. **Checks the handheld.** Only models PB-OS was tested on can install.
4. **Downloads the newest PB-OS release** for that handheld from GitHub
   (the release marked *Latest*), after a clear warning that the card will be
   erased. The checksum list must carry the pb-os release signature, and every
   part must match it.

## Tested handhelds

The app matches Android's `Build.MANUFACTURER` and `Build.MODEL`
([Devices.kt](app/src/main/java/org/projectbarry/pbosinstaller/device/Devices.kt)).

| Handheld | Chip | Android strings |
|---|---|---|
| Retroid Pocket 6 | SM8550 | `Moorechip` / `Retroid Pocket 6` |
| Retroid Pocket Nova | SM8550 | placeholder |
| AYN Thor | SM8550 | placeholder |
| KONKR Pocket FIT | SM8650 | placeholder |

Until a placeholder is filled in, the app treats that handheld as untested. To
get the real strings, open the app on the device and tap **Copy device info**.

## Untested handhelds

Every build we hand out refuses handhelds that aren't in the list. For
development, one build flag lets any SM8550 or SM8650 handheld through (the
app then shows an "untested device" warning):

```sh
./gradlew assembleDebug -PallowUntested=true
```

## Building

Needs JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

### Trying every screen on an emulator

Debug builds can pretend to be another device:

```sh
adb shell am start -n org.projectbarry.pbosinstaller/.MainActivity \
  --es fakeManufacturer Moorechip --es fakeModel "'Retroid Pocket 6'" --es fakeSoc QCS8550
adb shell sm set-virtual-disk true   # then: adb shell sm partition disk:<id> public
```

Release builds ignore these extras.

## License

GPL-2.0, see [LICENSE](LICENSE).
