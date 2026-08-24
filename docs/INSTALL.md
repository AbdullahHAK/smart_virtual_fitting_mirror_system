# Installation Guide

This covers setting up a development machine to build and run both apps, and installing them on real devices.

## Prerequisites

- A Windows/Mac/Linux machine with Android Studio (Standard install — bundles an SDK and a JDK).
- An Android Box (or a phone/tablet standing in for it during development) and a separate tablet, both able to join the same Wi-Fi network.
- A USB cable capable of data transfer (not charge-only) for the initial install, unless sideloading over Wi-Fi (see below).

## ⚠️ Important: JDK compatibility

Android Studio bundles its own Java runtime, and on some installs that runtime is newer than the Kotlin compiler version this project uses. When that happens, **every build silently fails** — Gradle throws `IllegalArgumentException` while trying to parse the Java version string, before your code is even touched. Symptoms if you hit this:

- Clicking Run appears to work, but the app on the device never actually updates.
- No error dialog appears in Android Studio; the build log shows a failure buried in a long stack trace mentioning `JavaVersion.parse`.

**Fix:** install a JDK 17 distribution (e.g. [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17)) and point Gradle at it — either:
- In Android Studio: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**, select the JDK 17 install.
- Or system-wide: create/edit `~/.gradle/gradle.properties` (in your user home folder, *not* the project folder) and add:
  ```
  org.gradle.java.home=/path/to/jdk-17
  ```
  This applies to every Gradle project on the machine and doesn't touch anything in this repo.

If you're not sure whether you're affected: after clicking Run, check whether the APK's install timestamp actually changed (`adb shell dumpsys package com.smartmirror.box | grep lastUpdateTime`, compare to the current time). If it didn't move, this is almost certainly the cause.

## Building and installing

### Box app
1. Open `box-app/` in Android Studio as its own project (File → Open).
2. Wait for Gradle sync to finish (needs internet the first time, to download dependencies).
3. Connect the Android Box (or a phone standing in for it) via USB, with **Developer Options → USB debugging** enabled on the device.
4. Select the device in the toolbar dropdown, click the green **Run ▶** button.
5. Grant the camera permission prompt when it appears on the device.

### Tablet app
Repeat the same steps with `tablet-app/` open as its own project, installing onto the tablet (or a second phone).

### Command-line build (no Android Studio UI needed)
Both apps can also be built directly, without opening Android Studio at all:
```
cd box-app
./gradlew assembleDebug        # gradlew.bat on Windows
```
Make sure `JAVA_HOME` points at a compatible JDK first (see the JDK compatibility note above) — either export it in your shell, or set `org.gradle.java.home` in your global `~/.gradle/gradle.properties`. The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`; install it with `adb install -r <path>`.

## Testing with only one physical device
Install both apps on the same device and either use Android's split-screen, or switch between them manually. In tablet-app's IP field, use `127.0.0.1` instead of a real Wi-Fi address — this works because both apps share the same device's network stack.

## Sideloading without a USB data cable
If you only have a charge-only cable:
1. In Android Studio: **Build → Build Bundle(s)/APK(s) → Build APK(s)** (no device connection needed for this step).
2. Serve the resulting `.apk` from your computer over the same Wi-Fi network — e.g. run `python -m http.server 8000` in the output folder.
3. On the target device's browser, navigate to `http://<your-computer's-LAN-IP>:8000/app-debug.apk` and download it.
4. Open the downloaded file; Android will prompt to allow installing from that source (browser/Files app) — allow it, then install.

## Common device issues

- **Device not detected by adb**: try a different USB cable — many "charging" cables have no data lines despite looking identical to real ones. Confirm with `adb devices` (should list the device, not show it empty).
- **Screen locks during testing and stops the camera**: this is expected Android behavior (the app's lifecycle pauses when the screen locks, unbinding the camera). Increase the screen timeout, or use `adb shell svc power stayon usb` for extended dev sessions.
- **"Gradle wrapper not found" prompt on first open**: accept it — Android Studio regenerates the missing wrapper files automatically.
