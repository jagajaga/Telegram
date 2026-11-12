## Unofficial fork: faster attachments, far less battery

An unofficial fork of [Telegram for Android](https://github.com/DrKLO/Telegram), kept in step with the official repository. It is not affiliated with Telegram.

**[Download the latest APK](https://github.com/jagajaga/Telegram/releases/latest)** (arm64). It installs next to the official app and updates itself from this repository's releases.

### What is different

**Photos in the attach menu appear instantly.** A photo you just took is in the grid the moment you open the attach sheet. The official app rescans the whole gallery after a 2-second delay; this fork merges new photos into the existing list in milliseconds and loads thumbnails from the system cache, in parallel.

**Much less battery in the background.** Media is downloaded only while the app is open; channel and muted-chat media loads when you open the chat; stories are preloaded on Wi‑Fi only. The background connection adapts its keep-alive to the carrier. Measured on one phone: background CPU went from about an hour a day to two or three minutes.

**Calls use a fraction of the CPU.**
- The app no longer keeps rendering the chat hidden behind the call screen — this was most of a call's cost.
- During a voice call on a headset the screen follows the normal timeout instead of staying lit for the whole call.
- The call screen runs at 60 Hz; the rest of the app keeps the phone's refresh rate.

Together: a voice call went from roughly 40–50 % of a CPU core to about 8 %.

**Bluetooth headsets in calls.** Calls are routed to the headset on the first attempt, with retries and a fallback for classic headsets. In the official app the audio could stay on the earpiece with no way to switch.

**Calls always ring.** Builds not signed by Telegram cannot receive Google push notifications, so the fork keeps Telegram's own background connection for calls and messages. If Android's system call integration refuses a call, the app falls back to ringing on its own instead of dropping it.

**Maps without Google.** Vector maps (MapLibre with OpenStreetMap data), sharp at any zoom, with a real dark style.

**Smaller things.** A Calls tab in place of Contacts; animated stickers and emoji at 30 fps (optional).

All battery options are in **Settings → Data and Storage → Battery**.

---

## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
