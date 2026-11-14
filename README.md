# CloneHub

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Native-C%2B%2B-00599C?style=for-the-badge&logo=cplusplus&logoColor=white" />
  <img src="https://img.shields.io/badge/Min%20SDK-26-brightgreen?style=for-the-badge" />
</p>

<p align="center">
  <strong>An Android app sandbox project for managing and running guest applications in isolated app data spaces without requiring root access.</strong>
</p>

<p align="center">
  <a href="#overview">Overview</a> &bull;
  <a href="#architecture">Architecture</a> &bull;
  <a href="#project-layout">Project layout</a> &bull;
  <a href="#development-status">Development status</a> &bull;
  <a href="#build">Build</a> &bull;
  <a href="#contributing">Contributing</a> &bull;
  <a href="#credits-and-license">Credits &amp; license</a>
</p>

---

## Overview

CloneHub is an Android virtualization and app-isolation project. It provides a host application for managing guest APKs and launching them in a managed runtime environment. The goal is to explore app compatibility, sandbox behavior, and runtime diagnostics on ordinary Android devices.

CloneHub is based on the ShitJumpingVM codebase by [woand](https://github.com/woand/ShitJumpingVM), which in turn contains work derived from [NEXTVM](https://github.com/TanvirHossain2/NEXTVM). CloneHub is maintained as a separate project with its own documentation, configuration, and development direction.

**Important:** CloneHub is not a full Android emulator or a second Android operating system. It does not guarantee that every APK will work. Guest apps remain subject to Android's process and security model, and compatibility depends on the app and device.

## Architecture

CloneHub brings together four main parts:

1. **Guest app management** — records APKs added to the host and their virtual instances.
2. **Runtime startup** — prepares the guest environment and launches guest components through host-managed processes.
3. **Environment isolation** — provides guest-specific paths and context for data such as files, preferences, and databases.
4. **Compatibility support** — uses framework and native interception points to redirect selected operations and collect diagnostics.

### Runtime flow

```text
Choose a guest APK
       |
       v
CloneHub creates or updates its virtual app entry
       |
       v
The guest runtime and sandbox are prepared
       |
       v
Guest components are routed through host stub components
       |
       v
The guest runs inside a managed host process
       |
       v
Sandbox data and diagnostics support troubleshooting
```

### Sandbox data

Guest data is stored beneath the host application's private files area and separated by virtual app instance. This is intended to keep guest files distinct from the host's own data. It does not create a separate Android user or provide hardware-level isolation.

## Project layout

The repository is organized into the host application, core runtime modules, and user-facing features.

```text
CloneHub/
├── app/                  # Host application and entry points
├── core/
│   ├── virtualization/   # Guest runtime and lifecycle
│   ├── binder/           # Binder and system-service interception
│   ├── sandbox/          # Virtual app data and context
│   ├── hook/             # Native hook support
│   ├── apk/              # APK parsing and loading support
│   ├── framework/        # Android framework integration
│   ├── model/            # Shared runtime models
│   ├── services/         # Virtual service implementations
│   ├── common/           # Shared utilities
│   └── designsystem/     # Shared UI styling
├── feature/
│   ├── launcher/         # Guest app launcher
│   ├── appmanager/       # Guest APK management
│   ├── settings/         # Runtime and app settings
│   └── filemanager/      # Virtual storage browser
└── gradle/               # Gradle wrapper and dependency configuration
```

Module names and responsibilities may change as development continues. The source tree is the definitive reference.

## Development status

CloneHub is under active development. The presence of a module or class does not necessarily mean the related feature is complete or enabled in the runtime.

Current development priorities include:

- Improving guest startup reliability across Android versions and devices.
- Strengthening sandbox path handling and guest data separation.
- Investigating app-specific launch and runtime compatibility issues.
- Making diagnostics more useful when a guest app closes unexpectedly.
- Improving the host interface and guest app management workflow.

Compatibility is not universal. Apps that depend on Play services, DRM, device integrity checks, unusual native behavior, or complex multi-process setups may not work correctly. Please report what you actually tested rather than assuming support from a successful launch alone.

## Build

### Requirements

- Android Studio with a compatible Android Gradle Plugin
- JDK 17
- Android SDK 35
- Android NDK 27.1.12297006
- CMake 3.22 or newer for native components

### Build a debug APK

```bash
git clone https://github.com/mnbvcxz24/CloneHub.git
cd CloneHub
./gradlew :app:assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

To install from a computer with a connected Android device:

```bash
./gradlew :app:installDebug
```

## Contributing

Bug reports, compatibility findings, documentation improvements, and code contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and contribution guidelines.

When reporting a compatibility issue, include the Android version, device model, guest app version, steps to reproduce, and any relevant CloneHub diagnostics. Remove personal information and credentials before sharing logs.

## Credits and license

CloneHub builds on existing open-source work:

- [ShitJumpingVM](https://github.com/woand/ShitJumpingVM) by woand — the project base used by CloneHub.
- [NEXTVM](https://github.com/TanvirHossain2/NEXTVM) by Tanvir Hossain — upstream work incorporated into ShitJumpingVM.

Applicable Apache License 2.0 terms and required upstream notices are retained in this repository. See [LICENSE](LICENSE) for the license text. Third-party dependencies may have separate license terms.

CloneHub is an independent project and is not an official release of ShitJumpingVM or NEXTVM.
