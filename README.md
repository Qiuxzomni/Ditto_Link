# Ditto Link

Ditto Link connects an Android phone to [Ditto Clipboard Manager](https://ditto-cp.sourceforge.io/) on a Windows PC. It transfers text and files over Tailscale or a shared local network.

## Features

- Send typed or pasted text from Android to the PC.
- Send selected Android text using the **Send to PC** selection action.
- Send one or more files using Android's **Share** menu or the file picker.
- Receive PC text into the Android clipboard, ready to paste in another app.
- Receive files into **Internal storage → Download → Ditto** and share the latest received file with another Android app.
- Browse recent transfers in the History tab.

## Setup

1. For remote use, connect both devices to Tailscale. On the same Wi-Fi/LAN, enter the PC's local IP instead; port forwarding is not needed on a local network.
2. In Ditto on the PC, open **Options → Friends**, add the phone's reachable IP, and enable **Send All Copies** if PC clipboard changes should be sent automatically.
3. In Ditto Link, add the PC's Tailscale or local IP and Ditto network password. Tailscale is recommended for the simplest secure setup.
4. Turn on **Background Receive** to receive clipboard text and files while Ditto Link is in the background.

Android does not provide a general-purpose file clipboard. Files received from the PC are saved in `Download/Ditto`; use **Share Last** to send one to another phone app.

## Build

Requirements: JDK 17 and Android SDK Platform 36.

```sh
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
