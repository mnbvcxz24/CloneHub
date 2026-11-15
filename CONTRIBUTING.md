# Contributing to Fuku

Thanks for helping improve Fuku. Contributions can include code, testing, device compatibility reports, documentation, and clear bug reproductions.

This guide explains how to prepare a change and what information helps maintainers review it.

## Table of Contents

- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Understanding the Project](#understanding-the-project)
- [Making Changes](#making-changes)
- [Code Style](#code-style)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Areas to Improve](#areas-to-improve)
- [Reporting Issues](#reporting-issues)

---

## Getting Started

1. Fork the [Fuku repository](https://github.com/mnbvcxz24/Fuku).
2. Clone your fork:
   ```bash
   git clone https://github.com/YOUR_USERNAME/Fuku.git
   cd Fuku
   ```
3. Create a branch for your work:
   ```bash
   git checkout -b feature/short-description
   ```

For small fixes, keep the change focused. If a proposal changes runtime architecture or adds a new interception layer, open an issue first so the approach can be discussed before substantial work begins.

## Development Setup

### Prerequisites

| Tool | Version / requirement |
|------|------------------------|
| JDK | 17 |
| Android SDK | API 35 |
| Android NDK | 27.1.12297006 |
| CMake | 3.22+ for native components |
| Android Studio | A version compatible with the project's Gradle and Android Gradle Plugin |

### Build commands

```bash
# Compile the debug application
./gradlew :app:assembleDebug

# Run available lint tasks
./gradlew lint

# Build the configured project
./gradlew build
```

Use a physical Android device for meaningful virtualization testing. Emulator behavior may differ from a physical device, and a successful build does not confirm that a guest app will run correctly.

### Before opening a change

- Start from the latest `main` branch.
- Check existing issues and pull requests for related work.
- Keep credentials, signing keys, private APKs, and user data out of commits.
- Record the Android version and device details used for testing.

## Understanding the Project

Fuku is organized into the host application, core runtime modules, and feature modules.

```text
app/                    Host app, dependency injection, guest entry points
core/
  virtualization/       Guest startup, process and lifecycle handling
  binder/               Binder and system-service routing
  sandbox/              Guest context and data separation
  hook/                 Native interception support
  apk/                  APK inspection and class-loading support
  framework/            Android framework integration
  model/                Shared runtime models
  services/             Virtual service components
  common/               Shared helpers
  designsystem/         Shared UI components and theme
feature/
  launcher/             Guest app home screen
  appmanager/           Guest APK installation and management
  settings/             Runtime and application settings
  filemanager/          Sandbox file browsing
```

### Suggested reading order

If you're new to the codebase, trace one guest launch from the host UI into the runtime before changing lower-level hooks:

1. `core/model/` — understand the data representing a virtual app and process.
2. `core/virtualization/` — follow initialization and guest lifecycle handling.
3. `core/binder/` — see how selected system calls are routed.
4. `core/sandbox/` — understand the guest context and storage layout.
5. `core/hook/` — review native interception when your change requires it.
6. `feature/` and `app/` — follow the user-facing flow and host integration.

The implementation is the source of truth. Update this guide when module responsibilities or important entry points change.

## Making Changes

### General guidelines

- Keep changes scoped to one issue or feature.
- Prefer existing project patterns and dependencies over adding new frameworks.
- Handle failures explicitly; guest startup has multiple stages and should not silently discard useful errors.
- Avoid complex work inside native hook callbacks. Keep hook code safe against recursion and unexpected calls.
- Do not assume behavior observed in one guest app applies to every app.
- Add or update tests when practical, especially for parsing, path handling, and state transitions.
- Update documentation when a change affects setup, user-visible behavior, or architecture.

### What to avoid

- Do not add root requirements unless the project direction is explicitly changed.
- Do not add undisclosed tracking or telemetry.
- Do not commit secrets, tokens, signing credentials, or personal app data.
- Do not claim support for an app or Android version without testing it.
- Do not present upstream documentation or feature claims as Fuku capabilities unless they have been verified in this codebase.

## Code Style

- **Kotlin:** follow the formatting and Kotlin conventions used in the module.
- **C++:** keep native code consistent with surrounding NDK code and use clear ownership and error handling.
- **Naming:** use descriptive names that match existing package and module conventions.
- **Logging:** include enough context to diagnose failures, but never log passwords, tokens, or private user content.
- **Comments:** explain non-obvious Android behavior and constraints rather than restating the code.

Before submitting, review the diff and remove unrelated formatting changes.

## Submitting a Pull Request

1. Make sure the project builds with the relevant Gradle task.
2. Run applicable lint or tests.
3. Test runtime changes on a physical device where possible.
4. Update the README or relevant guide if behavior or setup has changed.
5. Open a pull request with a concise description of the problem and solution.

### Pull request details

Please include:

- **Summary:** what changed?
- **Reason:** what problem does it solve?
- **Testing:** which build tasks, tests, devices, and Android versions were used?
- **Compatibility impact:** could this affect other guest apps or Android versions?
- **Screenshots or logs:** include them when useful, after removing private information.

Keep pull requests reviewable. Discuss large architectural changes before implementation.

## Areas to Improve

The following are broad areas where contributions may be useful. They are suggestions, not claims that a feature is already supported.

### Runtime reliability

- Improve guest startup diagnostics and failure reporting.
- Make lifecycle and process cleanup more predictable.
- Investigate compatibility differences across Android releases.

### Sandbox behavior

- Improve coverage for files, preferences, databases, and external-storage paths.
- Add tests for path mapping and per-instance separation.
- Document cases where Android services still use host identity or host permissions.

### App compatibility

- Reproduce and document failures for individual guest apps.
- Investigate WebView, native libraries, multi-process apps, and service dependencies.
- Separate confirmed fixes from workarounds that only help one app.

### User experience and documentation

- Improve guest management and error messages.
- Make diagnostic information easier to collect and understand.
- Keep setup instructions and feature descriptions aligned with the current code.

## Reporting Issues

Choose the issue type that best matches the problem and provide enough detail for someone else to reproduce it.

For a runtime or compatibility report, include:

- **Device:** manufacturer and model.
- **Android version:** include the API level if known.
- **Fuku version:** release or commit being tested.
- **Guest app:** app name, package name, and version.
- **Steps to reproduce:** list the actions in order.
- **Expected result:** what you thought would happen.
- **Actual result:** what happened instead, including whether the guest process closed or returned to the host.
- **Diagnostics:** relevant Fuku logs, with personal information removed.

Do not upload private APKs, account data, authentication tokens, or logs containing sensitive information.

### Issue categories

| Category | Use it for |
|----------|------------|
| Bug | A reproducible problem in Fuku |
| Compatibility | A guest app or Android-version-specific issue |
| Enhancement | An improvement to existing behavior |
| Feature request | A proposed new capability |
| Documentation | Incorrect, missing, or confusing project guidance |

---

## Questions?

If you're unsure where a change belongs, open an issue with a short explanation of the problem and the modules you think may be involved. It's better to agree on the direction early than to spend time on a change that conflicts with the runtime design.

Thanks for helping make Fuku more reliable.
