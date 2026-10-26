# TV-Replica

A remote-first Android TV launcher with a glass-style interface, animated wallpapers and HDMI device cards.

[简体中文](README.zh-CN.md)

TV-Replica publishes the launcher module derived from [CleanYourTV](https://github.com/Gathin23/cleanyourtv). The original MIT license and upstream attribution are retained in [LICENSE](LICENSE) and [UPSTREAM.md](UPSTREAM.md).

## Features

- Six application cards per row, a bottom dock and direct on-screen arrangement.
- A profile pill with a passive LAN / Wi-Fi / offline connection indicator.
- A compact first-level menu: **Profile, Add profile, Connection, Input, All Settings**.
- HDMI connection states and device recognition where the TV firmware exposes metadata.
- Saturn, Earth, Weather and Flip Clock scenes, with independent settings and a local screensaver.
- Earth uses 4K day/night maps and a fixed rendering budget up to 1080p, with a bounded 2K fallback. Explore mode supports rotation, tilt and zoom.
- A 12-hour / 24-hour clock setting, cached profile photos and gentle navigation sounds.

## Build

Requirements: JDK 17, Android SDK platform 34, Android build-tools 34.0.0 and an Android TV running Android 8.0 / API 26 or newer. The Gradle 8.9 wrapper is included.

```sh
git clone https://github.com/lmnjun/TV-Replica.git
cd TV-Replica
(cd launcher && ./gradlew testDebugUnitTest lintRelease assembleRelease)
```

Configure the SDK with `ANDROID_HOME` or an untracked `launcher/local.properties`. The generated APK is under `launcher/app/build/outputs/apk/release/`.

The application ID remains `com.gathin.tvhome` for compatibility with the inherited launcher integration. Release builds currently use a locally generated debug signing key for development. No signing key is included. Updating an existing installation requires the same signing identity; preserve your own key and device data.

## Install and use

Enable developer options and authorize ADB on your TV, then install your locally built APK with Android platform-tools:

```sh
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.gathin.tvhome/.MainActivity
```

Select the launcher through the TV's Home settings. Some firmware also requires an accessibility-based Home handoff. Keep a working route to the TV's system settings and original launcher before changing Home ownership. Casting, voice assistants, HDMI and firmware updates are managed by the TV firmware.

From **All Settings → Wallpaper scene**, choose a scene and open its settings. In Earth **Explore scene**, use arrows to rotate and tilt, channel or fast-forward/rewind keys to zoom, OK to toggle 1× / 1.5×, and Back to save and exit.

## Compatibility and performance

OEM firmware controls HDMI metadata, account access, Home selection, refresh rate and standby behavior. Device recognition falls back to the HDMI port and connection status when metadata is unavailable.

The launcher targets smooth 60fps interaction. Window and Surface frame-rate requests are advisory: stable 60fps is not guaranteed when firmware keeps the display at another refresh rate. Navigation does not lower Earth's texture or rendering quality. Initial menu and scene transitions may still have long frames on some devices.

## Privacy and assets

Profile names/photos, app order and preferences are stored on the device. Weather can use a device or approximate network location; IP-based lookup contacts `ipwho.is`, and weather requests contact Open-Meteo with coordinates. Review [PRIVACY.md](PRIVACY.md) before enabling these features.

The public repository excludes device backups, account exports, screenshots, ADB credentials, signing keys and local build configuration. Earth maps are historical NASA / NOAA composites rather than live satellite imagery. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the bundled asset notices for sources and licenses.
