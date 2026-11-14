# CloneHub

CloneHub uses the [mnbvcxz24/VirtualApp](https://github.com/mnbvcxz24/VirtualApp) Android virtualization project as its codebase.

## Open the Android project

The VirtualApp source is included as a Git submodule at `third_party/VirtualApp`.

After cloning this repository, initialize and update the submodule to the latest `main` revision:

```bash
git submodule update --init --remote --recursive
```

Then open **`third_party/VirtualApp`** directly in Android Studio and use the Gradle wrapper included by that project.

## Single-app installation

The Gradle build includes the CloneHub host app and its virtualization library. The separate shell application module is no longer included in the build, so you install and open CloneHub only. Clone apps from the CloneHub home screen and launch them there; a separate shell APK is not required.

## Upstream project

- Source: https://github.com/mnbvcxz24/VirtualApp
- License: Apache-2.0 (see the upstream `LICENSE` file)

## Important

VirtualApp is an actively developed and explicitly unstable project. Android 15 (API 35) compatibility has not yet been established, and some apps may fail to initialize in the virtual environment. This repository does not claim that the app has been built or verified on every device.
