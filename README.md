# CloneHub

CloneHub is an Android app-cloning project. The goal is to create separately installed app packages that Android manages as independent applications, each with its own package identity and app data directory.

## Current status

The project is being rebuilt from the previous virtual-app architecture.

The first milestone is a clean Android manager app that can enumerate user-installed applications. APK extraction, package transformation, signing, and installation are not implemented yet.

## Planned architecture

1. Detect installed applications and their APK/split APK files.
2. Inspect package manifests and resources.
3. Create a distinct package identity for a clone.
4. Rebuild and sign the resulting APK using normal Android signing.
5. Hand installation to Android's Package Installer.
6. Track, launch, and uninstall clones from CloneHub.

Each installed clone will rely on Android's normal package sandbox. Compatibility is app-dependent: changing package identity and signing certificates can break apps that rely on their original signature, Play services, integrity checks, DRM, or server-side validation. CloneHub will not promise compatibility with every app.

## Build

Requirements: JDK 17, Android SDK 35, and the Gradle wrapper included in this repository.

```sh
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Development

Work is being delivered in small milestones. The current milestone is the clean manager shell and installed-app inventory. Later milestones will add APK inspection and the safe package/install pipeline.

## License

License and third-party attribution are being audited as part of the architecture reset. See the repository history for prior project and upstream notices.
