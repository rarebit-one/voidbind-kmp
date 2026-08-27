# Device testing — proving the hardware keystore

The keystore's acceptance is **device-tested, not CI**: a StrongBox secure element
and a Secure Enclave do not exist on an emulator or the iOS Simulator, so the
non-extractability and biometric properties can only be shown on real hardware.
This is the runbook, plus the map of what still has to be built to reach the full
"app pairs + web QR-login with the hardware key" acceptance.

## Status

| Piece | State |
|---|---|
| Crypto decision (ADR-0001) | ✅ resolved + recorded |
| `Ed25519Engine` (software, multiplatform) | ✅ done, JVM round-trip tested |
| `DeviceKeyStore` JVM actual (software) | ✅ done |
| `DeviceKeyStore` Android actual (StrongBox-sealed) | ✅ compiles; **needs a device to prove non-extractability** |
| `DeviceKeyStore` iOS actual (SE-sealed) | ✅ compiles; **needs the Swift `SecureEnclaveSealer` impl + a device** |
| Swift `SecureEnclaveSealer` implementation | ⏳ TODO (in the iOS app) |
| Android app shell (Compose) | ⏳ TODO |
| iOS app shell (SwiftUI) | ⏳ TODO |
| Live web QR-login vs All Thing / heyarr | ⏳ TODO — **blocked on the pairing reconciliation below** |

### Prerequisite for live interop: reconcile the pairing/cert wire to voidbind-go v2

`src/commonMain/.../Pairing.kt` still encodes the **v1** transcript
(`heyarr/pairing/v1/{commit,sas}`, single-key commit, a bespoke SAS fold), while
voidbind-go is now **v2** dual-key (`heyarr/pairing/commit/v2`, the commitment
binds both the signing key and the X25519 encryption key — ADR-0049 §41). The
hardware key and signing are unaffected, but a real QR-login/pairing against a
live backend will not interoperate until `Pairing.kt` (and `Cert.kt`'s cert
fields) are brought byte-identical to voidbind-go v2. Do this before wiring the
app's pairing flow to a real All Thing / heyarr.

## What you need

- A **physical Android device with a StrongBox** secure element (Pixel 3+ or any
  device advertising `FEATURE_STRONGBOX_KEYSTORE`), with a screen lock + a
  fingerprint/face enrolled.
- A **physical iPhone with a Secure Enclave** (any Face/Touch ID iPhone), with a
  passcode + biometric enrolled.
- A reachable **All Thing** (or heyarr) instance to log in against, over the VPN
  or on the LAN. Standing one up locally: build the `allthing` binary and run
  `allthing serve` behind a bearer token; the QR-login endpoint is the target the
  app signs a challenge for.

## Test 1 — the device key is non-extractable in secure hardware

**Android**
1. In the app, provision a device key: `DeviceKeyStore.getOrCreate("device")`
   (after `VoidbindAndroid.init(applicationContext)`), authenticating at the
   `BiometricPrompt` when asked.
2. Confirm the wrapping key reports StrongBox: read its `KeyInfo`
   (`KeyFactory.getKeySpec(..., KeyInfo::class)`) and assert
   `getSecurityLevel() == SECURITY_LEVEL_STRONGBOX` (API 31+). If the device has
   no StrongBox it falls back to TEE — note which.
3. Prove non-extractability: there is no API that returns the AES wrapping key or
   the plaintext seed at rest. Inspect `filesDir/voidbind/device.key` — it holds
   only the **sealed** ciphertext + IV + the (public) Ed25519 key. The seed never
   appears there.
4. Prove use is gated: lock the device, wait past the 30-second auth window, and
   call `sign(...)` — it must throw `AuthenticationRequiredException`. Authenticate
   and retry — it must produce a 64-byte signature.

**iOS**
1. Provision with `DeviceKeyStore.getOrCreate("device")` after
   `VoidbindIos.init(sealer)`.
2. The Secure-Enclave P-256 key is created with `kSecAttrTokenIDSecureEnclave` +
   access control `.privateKeyUsage`/`.biometryCurrentSet` — the private key is
   non-extractable by construction (Apple never returns SE key material).
3. Call `sign(...)`: iOS must present Face/Touch ID (the SE unseal), and only then
   return a signature. Cancel the prompt — signing must fail, not proceed.

## Test 2 — pair + web QR-login with the hardware key

_(After the pairing reconciliation and the app shells exist.)_
1. All Thing shows a login QR encoding a challenge + session id.
2. The app scans it, `sign()`s the challenge with the **hardware** device key
   (biometric prompt fires), and posts the signature + the device cert.
3. All Thing verifies the signature offline against the pinned device/user key
   (voidbind-go/rp) and issues a short-lived session token.
4. Assert: no private key ever leaves the phone; a cancelled biometric prompt
   yields no login; a signature from a different device is refused.
