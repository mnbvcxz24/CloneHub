# Fuku

Fuku is a single-edition Android app-cloning manager. It creates separately installed clones of supported, launchable user apps. There are no Lite or Universal editions.

## How it works

1. Select an installed app.
2. Fuku copies the app's base and split APK files into its private workspace and verifies their SHA-256 hashes.
3. The APK set is inspected, package identity and selected manifest references are rewritten, and the set is re-signed with Fuku's local signing key.
4. Android Package Installer presents the installation confirmation. The user must approve it.

Clones are separate installed packages with their own Android app data. The original app is not modified. Fuku does not promise compatibility with every app. Apps relying on their original signing certificate, hard-coded package IDs, Play Integrity, Google Play services, DRM, anti-tamper checks, or server-side validation may fail. Split APKs, native libraries, and app-specific install requirements can also affect compatibility.

Use only apps and APKs you are authorized to modify. Fuku does not bypass app protections or platform security checks.

## Floating windows

From the selected source-app dialog, **Launch clone floating** finds its installed Fuku clone and requests a centered, smaller launch window using Android's public activity launch-bounds API. This launches the actual cloned app; it does not mirror the app or draw an overlay.

This is a best-effort request, not a way to bypass Android's window manager. The device must support and allow freeform windows; otherwise Android may ignore the requested bounds and open the clone full-screen. Fuku reports whether the device advertises freeform-window support. PiP is not injected into arbitrary apps because a PiP manifest flag alone cannot make an app enter PiP; the app's own activity must call the PiP API.

## Build

Requirements: JDK 17 and Android SDK 35.

```sh
./gradlew :app:assembleDebug
```

Output:

- `app/build/outputs/apk/debug/app-debug.apk`

## License

Fuku is distributed under GNU GPL version 3.0. See `THIRD_PARTY_NOTICES.md` for upstream attribution.
