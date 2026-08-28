# Voidbind iOS app

The SwiftUI device authenticator. It links the KMP library as `Voidbind.xcframework`
and provides the two platform pieces the library needs on iOS: the **Swift
`SecureEnclaveSealer`** (seals the Ed25519 signing seed to a Secure-Enclave P-256
key) and a **`URLSessionHttpTransport`**. Everything else — identity derivation,
pairing, web-login, cert crypto — lives in the shared library.

## Build & run (Simulator)

The Xcode project is generated from [`project.yml`](project.yml) with
[XcodeGen](https://github.com/yonom/XcodeGen) (`brew install xcodegen`), so
`Voidbind.xcodeproj` is **not** checked in — regenerate it after a clone:

```sh
cd iosApp
xcodegen generate            # writes Voidbind.xcodeproj from project.yml
# The scheme's pre-build phase runs `assembleVoidbindDebugXCFramework` if the
# framework is missing; you can also build it up front from the repo root:
#   ./gradlew assembleVoidbindDebugXCFramework
xcodebuild -scheme Voidbind -project Voidbind.xcodeproj -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' ARCHS=arm64 EXCLUDED_ARCHS=x86_64 build
```

`ARCHS=arm64 EXCLUDED_ARCHS=x86_64` is required: Kotlin/Native only emits an
**arm64** simulator slice, so an Intel-arch simulator leg won't link. On Apple
Silicon this is the native path anyway.

Install + launch in a booted arm64 Simulator:

```sh
DEV=$(xcrun simctl list devices available | grep -m1 'iPhone 17 Pro (' | grep -oE '[0-9A-F-]{36}')
xcrun simctl boot "$DEV"
APP=$(find iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator -maxdepth 1 -name Voidbind.app)
xcrun simctl install "$DEV" "$APP"
xcrun simctl launch  "$DEV" one.rarebit.voidbind
xcrun simctl io "$DEV" screenshot onboarding.png
```

Verified on the iOS 26.2 Simulator (Xcode 26.2): the app builds, installs,
launches, and renders the onboarding screen (Create / Restore).

Project facts: bundle id `one.rarebit.voidbind`, deployment target iOS 16, links
`Voidbind.xcframework` **Embed & Sign**, `NSFaceIDUsageDescription` +
`NSCameraUsageDescription` in `Info.plist`. The app's Swift module is
`VoidbindApp` (not `Voidbind`) so `import Voidbind` resolves to the framework and
not the app target itself.

> ⚠️ CI verifies the KMP library (`jvmTest` + Android + the iOS klibs) and the
> Swift files are type-checked against the real exported framework. What still
> needs a **real iPhone** is the runtime Secure Enclave + biometric behaviour —
> see [`../docs/DEVICE-TESTING.md`](../docs/DEVICE-TESTING.md).
>
> To re-run the type-check:
> ```sh
> cd .. && ./gradlew assembleVoidbindDebugXCFramework
> SLICE=build/XCFrameworks/debug/Voidbind.xcframework/ios-arm64-simulator
> xcrun --sdk iphonesimulator swiftc -typecheck \
>   -target arm64-apple-ios17.0-simulator -F "$SLICE" iosApp/Voidbind/*.swift
> ```

## Files

| File | Role |
|---|---|
| `Voidbind/SecureEnclaveSealer.swift` | `EnclaveSealer` — implements the KMP `SecureEnclaveSealer` protocol (SE P-256 key + ECIES seal/unseal of the Ed25519 seed, biometric-gated; Keychain persistence). Named `EnclaveSealer` so it doesn't clash with the protocol. |
| `Voidbind/URLSessionHttpTransport.swift` | The KMP `HttpTransport` actual over `URLSession` (blocking, per the contract). |
| `Voidbind/VoidbindEngine.swift` | Wiring — the iOS mirror of Android's `DeviceVoidbindEngine`: `UserIdentity` create/restore, `DeviceIdentity` provisioning, `Enrolment`, the three coordinators. |
| `Voidbind/VoidbindApp.swift` | `@main` app; builds the engine (injects the sealer) once. |
| `Voidbind/OnboardingView.swift` | A demonstrative screen proving the create/restore → enrol wiring. |
| `Smoke/RuntimeSmoke.swift` | A **runtime** smoke — runs the identity path from Swift in the iOS Simulator and checks the results (bridging, `@Throws`, the derivation KAT). |

The full screen set from Jaryl's mockups (home, QR scanner, web-login approval
sheet, pair connect/verify, recovery backup, settings) is fleshed out on-device;
each view binds to a `VoidbindEngine` coordinator exactly as `OnboardingView` does.

## Runtime smoke (no device, no UI, no network)

`Smoke/RuntimeSmoke.swift` drives the pure identity surface from Swift in the iOS
**Simulator**, so it proves the Swift↔KMP bridge works at runtime — the
`Data`↔`KotlinByteArray` conversions, the companion/object accessors, and the
`@Throws` catch — not just that the Swift type-checks. It exercises only
derivation (no Secure Enclave, no network), so it runs headless. See the header of
that file for the exact `swiftc` + `simctl spawn` commands. Verified passing on the
iOS 26.2 Simulator:

```
ROUNDTRIP OK      # create → recovery secret → restore → same identity
KAT OK            # a known secret derives the exact voidbind-go public key, through Swift
THROWS OK         # a mistyped secret is caught, not a crash (the @Throws fix)
QR_MATCH OK       # the voidbind:login QR is byte-identical through Swift
SMOKE_DONE
```

## Building the framework the app links

```sh
cd ..                                   # repo root
./gradlew assembleVoidbindReleaseXCFramework
#   → build/XCFrameworks/release/Voidbind.xcframework  (ios-arm64 + simulator)
```

## Xcode project setup (one-time, done on-device/-Mac)

There is no `.xcodeproj` checked in yet (it is generated on the machine that has
Xcode). To stand it up:

1. **New Xcode project** → iOS App, SwiftUI, name it `Voidbind`, bundle id e.g.
   `one.rarebit.voidbind`. Add the `Voidbind/*.swift` files here to the target.
2. **Link the framework**: drag `build/XCFrameworks/release/Voidbind.xcframework`
   into the target → *Frameworks, Libraries, and Embedded Content* → **Embed & Sign**.
   (Add a Run Script or a Gradle build phase to re-run `assembleVoidbindReleaseXCFramework`
   so the framework tracks the library.)
3. **Capabilities / entitlements**:
   - Face ID usage string: add `NSFaceIDUsageDescription` to `Info.plist`
     ("Authenticate to use your Voidbind device key").
   - Keychain sharing is not required (items are app-scoped, this-device-only).
4. **Signing**: a real Secure Enclave needs a device build (the Simulator has no
   Enclave — the sealer's provision/unseal only truly exercise on hardware).

## Wiring recap

```swift
// once, at startup (VoidbindApp builds this):
let engine = VoidbindEngine()                    // injects SecureEnclaveSealer into the library

// onboarding:
let identity = engine.createIdentity()           // show identity.recovery.format() ONCE
// restore instead: `let identity = try engine.restoreIdentity(secret)` — throws on a mistyped secret
let device   = engine.deviceIdentity()           // provisions the SE-sealed signing key
_ = engine.enrolFirstDevice(identity: identity, device: device)

// scan dispatch (types drop the framework prefix — VoidbindQr.Login / .Pair):
switch engine.parseScanned(qr) {
case let login as VoidbindQr.Login: /* engine.loginApproval(...).begin/approve */ break
case let pair  as VoidbindQr.Pair:  /* engine.devicePairing(...).begin/confirm */ break
default: break
}
```

Run the coordinator calls off the main thread (`Task.detached` / a background
queue) — the KMP transport is blocking, the same as Android's `Dispatchers.IO`.
