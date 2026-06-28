{
  description = "Kotlin dev environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";  # or pin commit

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "x86_64-darwin" "aarch64-linux" "x86_64-linux" ];
      # Android SDK is unfree and needs license acceptance, so import nixpkgs with config
      # instead of using legacyPackages.
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs {
        inherit system;
        config.allowUnfree = true;
        config.android_sdk.accept_license = true;
      }));
    in {
      devShells = forAllSystems (pkgs:
        let
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" ];
            buildToolsVersions = [ "35.0.0" ];
            # ponytail: no emulator/system-images/NDK — add to the lists above if you need them
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
          };
          sdk = "${android.androidsdk}/libexec/android-sdk";
          devCmd = pkgs.writeShellScriptBin "dev" ''
            exec "$(git rev-parse --show-toplevel)/dev-utils.sh" "$@"
          '';
        in {
          # mkShellNoCC: no Nix C toolchain — its cc/ld wrapper otherwise mangles Xcode's
          # Swift linker flags. Kotlin/Gradle/Xcode supply their own toolchains.
          default = pkgs.mkShellNoCC ({
            packages = with pkgs; [
              jdk21
              kotlin
              gradle
              android.androidsdk
              postgresql  # pg_dump for dev dump-schema
              devCmd
            ] ++ lib.optionals stdenv.isDarwin [
              xcodegen  # generates the iOS Xcode project from banalities-ios/project.yml
            ];
            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;
          } // pkgs.lib.optionalAttrs pkgs.stdenv.isDarwin {
            # Nix's darwin stdenv forces DEVELOPER_DIR to its apple-sdk (can't link iOS
            # frameworks). shellHook runs after that, so override it here to point Kotlin/Native
            # + xcodebuild at the real Xcode. (Xcode itself can't come from Nix.)
            shellHook = ''
              export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
              # Nix's C-toolchain env vars hijack Xcode's Swift linker (SDKROOT points at
              # the Nix macOS SDK, NIX_LDFLAGS injects -L paths, etc). Nothing here compiles
              # C — Kotlin/Gradle/Xcode bring their own toolchains — so clear them.
              unset SDKROOT LD MACOSX_DEPLOYMENT_TARGET ''${!NIX_@}
            '';
          });
        });
    };
}
