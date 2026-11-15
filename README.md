# Fuku

Fuku is an Android app-cloning manager with two installable editions:

- **Fuku Lite** — restricted to the installed Roblox app.
- **Fuku Universal** — lets users select other installed, launchable, non-system apps.

Each edition has a separate Android application ID, so both can be installed side by side.

## How it works

1. Select an installed app (Roblox in Lite; other launchable user apps in Universal).
2. Fuku copies the app's base and split APK files into its private workspace and verifies their SHA-256 hashes.
3. The APK set is inspected, package identity and selected manifest references are rewritten, and the set is re-signed with Fuku's local signing key.
4. Android Package Installer presents the installation confirmation. The user must approve it.

Clones are separate installed packages with their own Android app data. The original app is not modified. Fuku does not promise compatibility with every app. Apps relying on their original signing certificate, hard-coded package IDs, Play Integrity, Google Play services, DRM, anti-tamper checks, or server-side validation may fail. Split APKs, native libraries, and app-specific install requirements can also affect compatibility.

Use only apps and APKs you are authorized to modify. Fuku does not bypass app protections or platform security checks.

## Build

Requirements: JDK 17 and Android SDK 35.

```sh
./gradlew :app:assembleLiteDebug :app:assembleUniversalDebug
```

Outputs:

- `app/build/outputs/apk/lite/debug/app-lite-debug.apk`
- `app/build/outputs/apk/universal/debug/app-universal-debug.apk`

## License

Fuku is distributed under GNU GPL version 3.0. See `THIRD_PARTY_NOTICES.md` for upstream attribution.
