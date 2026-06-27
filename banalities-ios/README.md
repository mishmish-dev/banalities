# banalities-ios

Thin SwiftUI host around the `BanalitiesUI` Kotlin framework built by `:banalities-ui`.

The Xcode project is **generated** from `project.yml` by [XcodeGen] (provided by the
Nix shell) — there's no hand-maintained `.pbxproj`. `project.yml` and the Swift sources
in `iosApp/` are the source of truth.

## Build / run

From the repo root, inside the Nix shell (which sets `DEVELOPER_DIR` to real Xcode and
clears the Nix C-toolchain vars that otherwise break Xcode's linker):

```sh
# (re)generate iosApp.xcodeproj — only needed after editing project.yml
nix develop --command bash -c 'cd banalities-ios && xcodegen generate'

# build for the simulator
nix develop --command bash -c 'cd banalities-ios && \
  xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination "generic/platform=iOS Simulator" build'
```

Or open `iosApp.xcodeproj` in Xcode and ⌘R. The pre-build script phase runs
`./gradlew :banalities-ui:embedAndSignAppleFrameworkForXcode` to build the framework, so
Xcode needs `JAVA_HOME`/`ANDROID_HOME` in its environment when launched from the GUI
(launch it from the Nix shell, or set them in Xcode's scheme).

## Targets

Device `iosArm64` + simulator `iosSimulatorArm64` only. Compose MP dropped the Intel
`iosX64` simulator, so `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` is set — build on
Apple Silicon.

[XcodeGen]: https://github.com/yonaskolb/XcodeGen
