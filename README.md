# CloneHub

CloneHub now uses the upstream [ISEKHON/VirtualApp](https://github.com/ISEKHON/VirtualApp) Android virtualization application as its codebase.

The previous experimental CloneHub implementation has been removed. This repository no longer contains a separate custom cloning engine or GitHub Actions workflows.

## Open the Android project

The upstream project is included as a Git submodule at `third_party/VirtualApp`.

After cloning this repository, initialize the submodule:

```bash
git submodule update --init --recursive
```

Then open **`third_party/VirtualApp`** directly in Android Studio. Use the Gradle wrapper included by the upstream project.

## Upstream project

- Source: https://github.com/ISEKHON/VirtualApp
- Pinned revision: `b3c634ad7941765df3da84a207aca94b7861afae`
- License: Apache-2.0 (see the upstream `LICENSE` file)

## Important

VirtualApp is an actively developed and explicitly unstable project. Expect compatibility limitations and possible crashes. This repository adopts the upstream project as-is; it does not claim that the app has been built or verified on every device.

Changes to the upstream engine should be made in the upstream submodule or in a deliberate fork, while preserving its license and attribution.
