# VirtualSpace engine integration audit

Audit date: 2026-11-14

## Decision

Use chiyuan5/VirtualSpace as an architectural reference for CloneHub's in-app virtualization engine. Do not import its application UI or assume that its advertised virtualization features are implemented. CloneHub keeps its existing dashboard and instance repository.

The upstream revision reviewed is b1ff7988ac598b00b45c22003390ff43396c1c01.

## Licensing checkpoint

The upstream README states "MIT License", but the repository API currently reports no recognized license and the repository tree contains no LICENSE file. Treat the source as not yet cleared for copying or vendoring. Until the owner supplies a complete license grant or otherwise confirms permission, use only high-level architectural ideas and write CloneHub implementation independently. Preserve attribution in project records.

## Findings in the reviewed source

The following are implementation gaps, not merely missing UI:

- VirtualCore stores virtual apps in a map keyed by packageName, so it cannot represent several instances of the same package independently.
- VirtualAppLoader.loadApplicationInfo returns null; isPackageInstalled always returns false; createVirtualContext falls back to the host context.
- VirtualContext delegates package name, class loader, resources, and system services to the host context rather than creating a guest application context.
- HookManager records AMS/PMS hook metadata but does not install method hooks; hookMethod only logs registration.
- MethodProxy.getSystemContext returns null and getUserId always returns zero.
- VirtualDispatch.hookPackageManager only logs that a hook is prepared.
- ServiceBroker.getService always returns null.
- BinderHook retrieves original system binders and writes the same binders into ServiceManager's cache; it does not install functional proxy binders.
- VirtualFileSystem creates directories and maps a limited set of path strings, but this is not a complete filesystem interception layer.
- VirtualActivityManager rewrites an intent package to a fake package name, but the fake package is not installed in Android's PackageManager, so this alone cannot launch a guest activity.
- The upstream app Gradle module compiles against API 34, while its README's Android support claims are broader. Compatibility must be established by tests, not README claims.
- The upstream repository's latest commit is a compilation-fix commit that removed portions of earlier hook and package-query code.

## CloneHub design requirements

1. Keep CloneHub's existing MainActivity and Alpha18 UI.
2. Make instanceId the primary identity. A package may have any number of instance records; packageName is metadata, not a unique key.
3. Each instance gets its own internal data root, preferences, databases, cache, code cache, and extracted native-library directory.
4. Keep base and split APKs together as an immutable APK set. Validate package/version/ABI before attempting to load.
5. Do not fake Android UIDs or claim OS-level sandbox equivalence. Hosted guests run under CloneHub's host security identity unless a genuine separate process/OS identity is established.
6. Build a Java/Kotlin-first guest lifecycle with explicit unsupported-operation results. Add native hooks only when a verified test requires them.
7. Start with a small, permissively licensed test APK. Prove launch, activity lifecycle, storage separation, and clean stop before attempting Roblox.
8. Roblox is a later compatibility target. Its split APK layout, ARM64 native libraries, graphics, login, and process assumptions require device testing.
9. Never mark an instance RUNNING until a guest process/activity handshake confirms it is alive.

## Implementation phases

### Phase 0 - Source and architecture audit
- Completed: reviewed upstream README, Gradle setup, manifest, core, loader, context, dispatch, service broker, hook manager, binder hook, storage, and activity manager.
- Pending: obtain a complete upstream license grant before copying any source.

### Phase 1 - CloneHub runtime contracts
- Define package-independent instance identity, APK-set metadata, guest process slots, lifecycle results, and per-instance storage paths.
- Add unit tests for multiple instances of the same package and path isolation.
- Keep the existing prepared-APK workflow and dashboard working.

### Phase 2 - Minimal guest loader
- Implement APK metadata parsing and class-loader/native-library preparation.
- Add a dedicated test APK and verify class loading and application initialization.
- Do not claim full Android component virtualization at this phase.

### Phase 3 - Component and system-service virtualization
- Implement and test activity dispatch, package queries, providers, services, broadcasts, permissions, and storage interception.
- Maintain explicit Android-version compatibility checks.

### Phase 4 - Roblox compatibility
- Test the exact installed split APK set on the target ARM64 device.
- Verify graphics, networking, login, native libraries, and lifecycle.
- Only then test two and three concurrent instances.

## Current status

This document is an audit and implementation plan only. It does not add a runtime backend or change the Alpha18 APK behavior.
