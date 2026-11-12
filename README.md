# Prime Ad Muter (Ad Cover)

A small Android TV app that notices when **Prime Video** or **YouTube** starts an ad, **mutes the sound and covers the screen** with a clock and a rotating quote (English and Hindi), and gets out of the way the moment the show comes back. On YouTube it can also **press Skip** for you.

It doesn't block or remove ads. They still play, you just don't have to watch or hear them.

- 🎬 Demo video: https://youtu.be/Ef7TNzE7l3A
- 📖 The story of how it was built, as a comic: https://reels2real.github.io/comic/primeadmuter/
- ⬇️ APK: [`dist/adcover-2.5.1.apk`](dist/adcover-2.5.1.apk) (SHA-256 in [`dist/SHA256SUMS`](dist/SHA256SUMS))

## How it knows an ad is playing

Video apps tell Android what they're doing through a *media session*: playing or paused, and which buttons work right now. During an ad, Prime and YouTube report **"playing", but with seek, fast-forward and rewind taken away**. That one change is the detector, so the app needs no screenshots while you watch.

Where an app doesn't report that (Prime on Google TV), the app falls back to looking for Prime's yellow **"Ad"** badge in the top-right corner, once a second, only while Prime is in front and sound is playing.

| Signal | Used for |
|---|---|
| Media session: playing + no seek | Prime and YouTube video ads (instant) |
| Screenshot of the badge corner (`watch on`) | Prime on devices where the session says nothing |
| Screenshot while paused | Prime's pause-screen ads (a still ad after ~5 s) |
| Screenshot of the Skip button, then the OK key | YouTube skippable ads (Android 13+) |
| Prime's "Ad 1:26" countdown, read from the badge | holds the mute if the session says "over" too early |

The cover leaves the corner with the app's own countdown (Prime top-right, YouTube's Skip bottom-right) visible.

## What it has been tested on

| Device | Android | Prime | YouTube |
|---|---|---|---|
| BenQ GV30 projector (QS01 dongle) | Android TV 12 | ✓ session | ✗ (YouTube 7.28 gives no signal and blocks screenshots) |
| Xiaomi Mi TV Stick (1 GB) | Android TV 9 | ✓ via `DUMP` (see below) | ✓ mute + cover; no auto-Skip (needs Android 13) |
| Xiaomi Mi TV Box S (2nd gen) | Google TV 14 | ✓ badge watch (`watch on`) | ✓ mute + cover + auto-Skip |

Prime Video and YouTube can change how they report ads at any time; if they do, the app may stop noticing them until it's updated.

## Install (one time, over ADB)

You need [ADB](https://developer.android.com/tools/adb) and **Developer options → USB debugging** switched on on the TV. Then:

```sh
adb connect <tv-ip>:5555
adb install dist/adcover-2.5.1.apk            # Play Protect may ask: choose "Install anyway"

# let it draw the cover
adb shell appops set local.adcover SYSTEM_ALERT_WINDOW allow
# let it read Prime's / YouTube's playback status
adb shell cmd notification allow_listener local.adcover/local.adcover.SessionListener
# Android 11+: screenshots for pause ads / the badge / the Skip button
adb shell settings put secure enabled_accessibility_services local.adcover/local.adcover.PauseAdService
adb shell settings put secure accessibility_enabled 1

# start it and switch it on
adb shell am start-foreground-service -n local.adcover/.CoverService
adb shell "echo 'auto on' | toybox nc -q 1 127.0.0.1 8765"
```

**Low-memory devices (Android 9, 1 GB, e.g. the Mi TV Stick).** Android silently refuses the notification-listener permission there. Grant this instead, and the app reads the same status report itself:

```sh
adb shell pm grant local.adcover android.permission.DUMP
```

**Google TV, where Prime's status never changes during ads.** Turn on the badge watch:

```sh
adb shell "echo 'watch on' | toybox nc -q 1 127.0.0.1 8765"
```

It starts by itself after a reboot.

## Controls

The app listens on `127.0.0.1:8765` on the TV only (nothing outside the TV can reach it). Send one line:

```sh
adb shell "echo status | toybox nc -q 1 127.0.0.1 8765"
```

| Command | What it does |
|---|---|
| `status` | current state (auto, ad, muted, permissions, Android version) |
| `auto on` / `auto off` | the automatic mute + cover |
| `watch on` / `watch off` | badge watch for Prime (for Google TV) |
| `darkness 98` | how dark the cover is, 10–100 (default 98) |
| `style full` / `style plain` | clock, date and quotes, or just "AD PLAYING" |
| `show 100` / `hide` | show or hide the cover by hand (hides itself after 6 s) |
| `mute` / `unmute` / `muted` | Android's music-stream mute |

## Rules it follows

- It only ever undoes **its own** mute. If you muted the TV yourself, it leaves it alone.
- If you unmute during an ad, it stays out of it until that break is over.
- It never holds a mute longer than 5 minutes.
- It writes down that the mute is its own before muting, so after a crash or restart it still unmutes.

## Build it yourself

No Gradle needed. You need JDK 17 and the Android SDK command-line tools with `build-tools;34.0.0` and `platforms;android-33`:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_SDK=/path/to/android-sdk ./build.sh
```

`build.sh` compiles, packages and signs `adcover.apk` with your own keystore (created on the first build; set `ADCOVER_KS` and `ADCOVER_KS_PASS` to use an existing one). An APK you build will have a different signature than the one in `dist/`, so uninstall the old one first.

## Privacy

The app has no account, no analytics and makes no network requests. The `INTERNET` permission only exists so it can listen on `127.0.0.1` for the commands above. Screenshots are taken only to look for ads (as listed above), analysed in memory and never saved.

## Not affiliated

Not affiliated with, endorsed by or connected to Amazon, Prime Video, Google or YouTube. Use it on your own devices, with content you're entitled to watch.

## License

[MIT](LICENSE)
