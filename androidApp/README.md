# androidApp — the Voidbind Android authenticator

The first-party Android app: a Jetpack Compose, Material 3, **dark-first**
authenticator that drives the real Voidbind flows with a hardware-backed device
key. It is a separate Gradle application module that depends on the root KMP
library (`project(":")`) — the shared wire contract, the flow coordinators, and
the hardware `DeviceKeyStore`.

## What it does

The screens follow the product mockups one-to-one:

| Screen | Purpose |
|---|---|
| Onboarding | Create a new identity · Restore from a recovery secret · Add this device (pair) |
| Home | Identity fingerprint, StrongBox status, this device, trusted sites, Scan FAB |
| Scan | CameraX + ML Kit QR scanner → dispatches web-login vs pair invites |
| Login approval | Sovereign consent sheet for a web login, biometric-gated |
| Pair · Connect | Show the one-time encrypted invite QR (existing device) |
| Pair · Verify | Compare the 7-digit SAS out of band, then confirm |
| Recovery backup | Display + acknowledge the bech32m recovery secret (screenshots blocked) |
| Settings | Device name, biometric approval, trusted sites (revoke), recovery, about |

## Architecture

- **`domain/`** — UI-facing models (`Identity`, `DeviceInfo`, `TrustedSite`,
  `LoginRequest`, `PairSession`, …) and **`VoidbindEngine`**, the single seam the UI
  calls. Everything cryptographic lives behind it; screens receive already-formatted
  fingerprints and booleans and never touch key bytes.
- **`ui/theme`** — the palette (mint = security, blue = brand, amber = recovery,
  coral = destructive), typography (a monospace face for every load-bearing crypto
  string), and the dark Material 3 scheme.
- **`ui/components`**, **`ui/screens`**, **`ui/nav`** — the reusable pieces, the
  screens, and the `VoidbindNavHost` that wires them with a bottom bar.
- **`ui/scan`** — the CameraX + ML Kit QR reader (`QrScanner`).

### Two engines

`VoidbindEngine` has two implementations:

- **`PreviewVoidbindEngine`** (present) — an in-memory backend seeded with the
  mockup data. It performs **no cryptography and no I/O**; it exists so the entire UI
  runs and can be reviewed against the mockups before the hardware/coordinators are
  wired. It is scaffolding, clearly labelled, and every value in it is placeholder
  content, not a real identity.
- **`DeviceVoidbindEngine`** (next slice) — the real backend: `UserIdentity`
  create/restore, the hardware `DeviceKeyStore` signing key + a sealed-at-rest X25519
  encryption-key store → `DeviceIdentity`, `Enrolment.selfEnrol`, and the
  `LoginApproval` / `DevicePairing` / `DeviceAuthorization` coordinators over an
  OkHttp `HttpTransport`, with `BiometricPrompt` gating each signature. Swapping it in
  is a one-line change in `MainActivity`.

## Building

```sh
./gradlew :androidApp:assembleDebug     # build the debug APK
```

`local.properties` must point `sdk.dir` at your Android SDK (gitignored).

## Acceptance is device-tested, not CI

CI compiles and assembles this module so a broken build cannot merge, but the
load-bearing security properties — **StrongBox non-extractability and biometric
gating** — do not exist on an emulator and are proven only on real hardware. See
[`docs/DEVICE-TESTING.md`](../docs/DEVICE-TESTING.md). Treat the Android
`DeviceKeyStore` StrongBox path and the biometric gate as *scaffolded, not verified*
until run on a physical StrongBox device.
