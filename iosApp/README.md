# Voidbind iOS app (scaffold)

The SwiftUI device authenticator. It links the KMP library as `Voidbind.xcframework`
and provides the two platform pieces the library needs on iOS: the **Swift
`SecureEnclaveSealer`** (seals the Ed25519 signing seed to a Secure-Enclave P-256
key) and a **`URLSessionHttpTransport`**. Everything else — identity derivation,
pairing, web-login, cert crypto — lives in the shared library.

> ⚠️ **This Swift is NOT compiled in CI.** CI verifies the KMP library
> (`jvmTest` + Android + the iOS klibs). These `.swift` files need an Xcode project
> and, for the Secure Enclave + biometric paths, a **real iPhone** — see
> [`../docs/DEVICE-TESTING.md`](../docs/DEVICE-TESTING.md). Treat them as reviewed
> scaffold until a device run confirms them.

## Files

| File | Role |
|---|---|
| `Voidbind/SecureEnclaveSealer.swift` | Implements the KMP `SecureEnclaveSealer` protocol — SE P-256 key + ECIES seal/unseal of the Ed25519 seed, biometric-gated; Keychain persistence. |
| `Voidbind/URLSessionHttpTransport.swift` | The KMP `HttpTransport` actual over `URLSession` (blocking, per the contract). |
| `Voidbind/VoidbindEngine.swift` | Wiring — the iOS mirror of Android's `DeviceVoidbindEngine`: `UserIdentity` create/restore, `DeviceIdentity` provisioning, `Enrolment`, the three coordinators. |
| `Voidbind/VoidbindApp.swift` | `@main` app; builds the engine (injects the sealer) once. |
| `Voidbind/OnboardingView.swift` | A demonstrative screen proving the create/restore → enrol wiring. |

The full screen set from Jaryl's mockups (home, QR scanner, web-login approval
sheet, pair connect/verify, recovery backup, settings) is fleshed out on-device;
each view binds to a `VoidbindEngine` coordinator exactly as `OnboardingView` does.

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
let device   = engine.deviceIdentity()           // provisions the SE-sealed signing key
_ = engine.enrolFirstDevice(identity: identity, device: device)

// scan dispatch:
switch engine.parseScanned(qr) {
case let login as VoidbindVoidbindQrLogin: /* engine.loginApproval(...).begin/approve */ break
case let pair  as VoidbindVoidbindQrPair:  /* engine.devicePairing(...).begin/confirm */ break
default: break
}
```

Run the coordinator calls off the main thread (`Task.detached` / a background
queue) — the KMP transport is blocking, the same as Android's `Dispatchers.IO`.
