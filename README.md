# CloneHub

CloneHub is an Android app-cloning project. The goal is to create separately installed app packages that Android manages as independent applications, each with its own package identity and app data directory.

## Current status

The manager can enumerate installed apps, extract and verify base/split APKs, inspect package metadata, rewrite package identity and provider authorities, re-sign the APK set, and hand it to Android Package Installer. This is the first-generation compatibility-limited clone path.

## Clone workflow

1. Select an installed app.
2. Prepare and checksum its base APK and split APKs.
3. Inspect its package metadata and signing certificate.
4. Rewrite the package ID, app label, provider authorities, app-defined permissions, and relevant manifest references.
5. Sign the rewritten APK set with CloneHub's persistent local signing key.
6. Submit the APK set to Android's Package Installer for user-approved installation.

Clones are normal installed packages and use Android's package sandbox. CloneHub does not inject runtime payloads, spoof signatures, or spoof device identity. The original app is not modified.

Compatibility is app-dependent. Apps that rely on their original signing certificate, hard-coded package IDs or provider authorities, Play Integrity, Google Play services, DRM, anti-tamper checks, or server-side validation may fail. Roblox compatibility is not guaranteed.

The signing key is stored in CloneHub's private app data. Keep CloneHub installed and retain its app data if you want future clones to use the same signing identity.

## Build

Requirements: JDK 17, Android SDK 35, and the Gradle wrapper included in this repository.

```sh
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## License

CloneHub is distributed under GNU GPL version 3.0. See `THIRD_PARTY_NOTICES.md` for upstream attribution.
