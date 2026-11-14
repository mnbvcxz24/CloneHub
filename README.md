# CloneHub

A lightweight Android app-cloning project exploring an isolated, BlackBox-inspired virtual runtime.

## V1 status: foundation

The first commit establishes the Android application shell and APK library.

### Included
- Kotlin Android application using native Android Views
- Android 8.0 (API 26) minimum
- APK selection through the Android Storage Access Framework
- Persisted document access where the provider supports it
- Local imported-app list stored on-device
- No root requirement for the library/import foundation

### Not included yet
- Virtual APK execution
- Per-instance Android user/process isolation
- Floating app windows or overlay controls
- Delta Executor compatibility

Imported APKs are currently catalogued only. CloneHub does not install, modify, or execute them in this version.

## Build

1. Open this repository in Android Studio.
2. Use JDK 17.
3. Allow Gradle to sync.
4. Run the `app` configuration on an Android 8.0+ device or emulator.

The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and compile SDK 35.

## Initial architecture direction

- **App shell:** Kotlin + native Android Views
- **APK import:** Storage Access Framework
- **Library metadata:** lightweight local preferences for V1
- **Virtual runtime:** separate module to be designed and tested before integration
- **Compatibility target:** ARM64 first

## Roadmap

1. [x] Android project and APK library foundation
2. [ ] Inspect and prototype the isolated virtual runtime
3. [ ] Verify storage and account-data isolation
4. [ ] Add multiple virtual instances
5. [ ] Add floating controls and investigate device-supported freeform windows
6. [ ] Test compatibility with target apps

## License

Project code is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
