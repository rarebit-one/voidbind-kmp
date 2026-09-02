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
| Pairing/cert wire reconciled to voidbind-go **v2** | ✅ done + merged (dual-key commit, 7-digit SAS) |
| `DeviceKeyStore` JVM actual (software) | ✅ done |
| `DeviceKeyStore` Android actual (StrongBox-sealed) | ✅ compiles; **needs a device to prove non-extractability** |
| `DeviceKeyStore` iOS actual (SE-sealed) | ✅ compiles; **needs a device** |
| Swift `SecureEnclaveSealer` implementation | ✅ written (`iosApp/Voidbind/SecureEnclaveSealer.swift`); **needs Xcode + a device** |
| `UserIdentity` / `Enrolment` / `LoginQr` / flow coordinators (commonMain) | ✅ done, unit-tested + cross-language proven vs live voidbind-go |
| `Voidbind.xcframework` export | ✅ done (`assembleVoidbindXCFramework`) |
| Android app shell (Compose) | 🚧 in progress (peer session — `androidApp/`) |
| iOS app shell (SwiftUI) | 🚧 scaffold (`iosApp/`); full screen set on-device |
| Live web QR-login vs All Thing / heyarr | ⏳ device test (below) |
| Same-device app-to-app deep link (`voidbind:login?…` from an RP app) | ✅ approval sheet proven on-device via `adb am start` against a live heyarr node (Test 5) |
| Membership op-set (ADR-0005): any member adds the next; Devices list + Remove | ✅ library proven vs live voidbind-go (14/14 vectors, phone→phone through the Go relay, Go RP honours `ops`); **on-device: upgrade-in-place + Devices list proven; a real second-phone pair/remove needs a second phone (Test 4b)** |

The commonMain "device brain" (identity derivation, self-enrolment, the
`LoginApproval` / `DevicePairing` / `DeviceAuthorization` coordinators, the QR
wire) is done and **cross-language proven against a live voidbind-go**
(`CoordinatorGoInteropTest`: a coordinator-driven login on the real Go RP + the
pairing coordinators through the real Go relay). What remains is genuinely
device-bound: the Secure Enclave / StrongBox properties and the biometric gate.

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
   `VoidbindIos.shared.doInit(sealer:)` (the app builds `VoidbindEngine()`, which
   injects the Swift `SecureEnclaveSealer`).
2. The Secure-Enclave P-256 key is created with `kSecAttrTokenIDSecureEnclave` +
   access control `.privateKeyUsage`/`.biometryCurrentSet` — the private key is
   non-extractable by construction (Apple never returns SE key material).
3. Call `sign(...)`: iOS must present Face/Touch ID (the SE unseal), and only then
   return a signature. Cancel the prompt — signing must fail, not proceed.

## Test 2 — onboarding: create / restore an identity on-device

Drives `UserIdentity` + `Enrolment` through the app engine (`OnboardingView` on
iOS; the Android onboarding screens).
1. **Create**: tap "Create a new identity". The app calls `UserIdentity.create()`,
   provisions the device key (biometric fires on first `DeviceKeyStore.getOrCreate`),
   and `Enrolment.selfEnrol`s. Assert the recovery secret is shown **once**
   (`heyarr1…`), and that force-quitting before saving it does not silently persist it.
2. **Restore**: reinstall the app (or use a second device), tap "Restore", type the
   secret. Assert the reconstructed `userId` **equals** the original (recovery
   restores the SAME pinned identity, offline), and that a single mistyped character
   is rejected loudly (the bech32m checksum) rather than yielding a different identity.

## Test 3 — web QR-login with the hardware key (via `LoginApproval`)

The RP backend already exists — run `cmd/voidbind login-serve --pin <userId>` (or
a deployed All Thing) and pin the identity from Test 2.
1. The RP shows a `voidbind:login?rp=&id=` QR. The app's Scan screen calls
   `VoidbindQr.parse` → `LoginApproval.begin(qr)`; the approval sheet shows the
   audience (RP origin) + a live expiry countdown.
2. Tap Approve → `LoginApproval.approve` calls `DeviceKeyStore.sign`, so the
   **biometric prompt fires** (the SE/StrongBox unseal); the assertion posts.
3. The RP verifies it offline (voidbind-go/rp) and mints a short-lived token.
4. Assert: no private key leaves the phone; a **cancelled** biometric prompt yields
   no login; an **unpinned** device is refused (401); an expired challenge is refused.

## Test 4 — add a second device (pairing, `DeviceAuthorization` ↔ `DevicePairing`)

Two devices (or one device + the `voidbind pair-*` CLI as the counterpart).
1. On the **existing** device: `DeviceAuthorization.invite(relayBase)` renders the
   pairing QR. The **new** device scans it → `DevicePairing.begin(inviteQr)`; both
   screens run the handshake and show a **7-digit SAS**.
2. Confirm the two numbers match (the human gate). The existing device
   `authorise`s (signs + seals the cert to the new device's X25519 key); the new
   device `confirm`s (unseals + verifies).
3. Assert: the SAS matches only when the two devices are the real pair; a
   mismatched/rushed SAS yields no enrolment; the delivered cert verifies against
   the user key and binds the new device.

## Test 4b — any member adds the next device; Settings → Devices; Remove (ADR-0005)

From 0.5.0 the device that taps "Add a device" does NOT need the recovery secret:
it signs the new device's add op with its own hardware key, citing the heads of the
membership replica in `IdentityStore` (`ops`). A pre-0.5.0 install migrates on first
launch — its v2 cert IS a genesis add, so the replica starts as `[cert]`; nothing is
re-enrolled and nothing is lost.
1. Upgrade in place (`adb install -r`, never uninstall). Open the app: Home still
   shows the same identity fingerprint and this device; Settings → **Devices** lists
   this device, "admitted <date> by genesis (recovery key)".
2. On a PAIRED (non-owner) install, tap **Add a device**: the invite renders without
   any secret; a third device that scans it receives an add signed **by the phone**
   (`Membership.evaluate` on either side finds all three members). The invite is v3
   (`usr=`), and a responder that expects a different identity gets **no SAS**.
3. **Remove** another device from Devices: biometric prompt → a `remove` op signed
   by this device → the row disappears (local evaluation) and the ops are pushed to
   `POST /membership/{usr}` on the heyarr node (:7777) and All Thing (:8080);
   a 404 from an RP that has not landed the route yet is tolerated. The removed
   device is refused at every RP on its next login (its ops travel with the
   assertion, and the RP's log now holds the remove).
4. Login still works: the assertion carries `ops` beside the admitting op (see
   `CoordinatorGoInteropTest`, which proves the Go RP evaluates them).

## Test 5 — same-device handoff: an RP app opens the authenticator by deep link

No second phone. The RP app on the SAME phone launches `voidbind:login?rp=&id=` (the
`qr` string its broker returned, optionally `&callback=<app-scheme-uri>`); the
authenticator shows the normal approval sheet and finishes back to the caller (ADR-0003).
Simulate the RP with `adb` against a live node:

```sh
# build + install the REAL engine (the phone must already hold an enrolled identity).
# The app is Cruciform, package one.rarebit.cruciform (ADR-0004); if a pre-0.3.0
# one.rarebit.voidbind install is still present, uninstall it LAST — after the new
# app launches and is enrolled — so only one authenticator claims the voidbind: scheme.
./gradlew -PdeviceEngine=true :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# adb uninstall one.rarebit.voidbind   # old package, only once Cruciform is enrolled

# mint a login on the RP (a heyarr dev node on the LAN), then hand it to the authenticator
ID=$(curl -s -X POST http://192.168.16.224:7777/login | sed -E 's/.*"id":"([^"]+)".*/\1/')
adb shell am start -a android.intent.action.VIEW \
  -d "voidbind:login?rp=http%3A%2F%2F192.168.16.224%3A7777&id=$ID&callback=heyarr%3A%2F%2Flogin%2Fdone"
```

1. The approval sheet opens (cold start AND with the app already open — `singleTask`
   + `onNewIntent`) showing the **rp origin** `192.168.16.224:7777` and a live expiry;
   nothing was approved by the link itself. A v2 challenge shows the number grid.
2. Tap **Deny**: the activity finishes; the previous app is back in front; no callback
   is launched; `GET /login/$ID` never reports approved.
3. Repeat with a fresh id and tap **Approve** → biometric → the RP's broker poll reports
   approved and (only now) the `callback` is launched bare, if an app handles it.
4. A malformed link (`voidbind:login?rp=x`, a `callback=https://…`) opens nothing /
   drops the callback — the URI is untrusted input. Debug builds allow cleartext HTTP so
   the plain-http LAN node works; release builds do not.
