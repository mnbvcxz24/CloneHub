# Third-party notices

## CloniX manifest mutation implementation

Fuku's binary Android manifest mutation implementation is derived from the manifest mutation code in [CloniX](https://github.com/txorav/CloniX), Copyright the CloniX contributors, and licensed under GNU GPL version 3.0. Fuku's version is modified to preserve the target app's original Application and AppComponentFactory. It intentionally excludes runtime payload injection, signature spoofing, device-identity spoofing, and native hooks.

## ARSCLib

Fuku uses [ARSCLib](https://github.com/REAndroid/ARSCLib) for Android binary resource and manifest parsing. See the upstream project for its license and notices.
