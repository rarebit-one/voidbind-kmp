# CLAUDE.md — voidbind-kmp

Guidance for Claude Code working in **voidbind-kmp**, the Kotlin Multiplatform
**device authenticator** side of Voidbind.

## What this is

Voidbind is a device-authentication protocol extracted from **heyarr**. There are
two sides:

- **`voidbind-go`** — the counterpart, already built. It holds the **wire
  contract** (token formats, key rendering, recovery encoding, pairing transcript).
  It is the source of truth.
- **`voidbind-kmp`** (this repo) — the on-device authenticator for **iOS and
  Android**, plus a JVM target for dev/test. Its job is to hold the device signing
  key in **hardware** (Secure Enclave / StrongBox) and speak the same wire format
  as voidbind-go.

## Naming: Voidbind is the protocol, Cruciform is the app

**Voidbind** names the protocol / security model and everything on the wire (the
`voidbind:` scheme, `one.rarebit.voidbind:voidbind-client`, the library package,
the ADR terminology). **Cruciform** names the first-party authenticator app
(`androidApp/`, package + applicationId `one.rarebit.cruciform`; the iOS scaffold
target) — see ADR-0004. Do not rename protocol identifiers to Cruciform, and do not
brand the app "Voidbind" again; a small "Voidbind protocol" attribution where the
security posture is explained is the intended overlap.

## The single most important rule: mirror voidbind-go exactly

Every value that crosses the wire must be **byte-identical** to what voidbind-go
produces/accepts. This library re-implements the *encodings* in pure Kotlin; it
does not get to invent them. When in doubt, the Go side wins — port from it, don't
design fresh.

### Identity-defining constants — DO NOT rename or "tidy"

These live in [`Labels.kt`](src/commonMain/kotlin/one/rarebit/voidbind/Labels.kt).
Changing any string silently derives different keys / incompatible tokens. It is a
total break, not a cosmetic edit. The `heyarr` heritage in the names/HRP is
deliberate and load-bearing.

| Constant | Value | Role |
|---|---|---|
| HKDF label | `heyarr/recovery/v1/user-identity-ed25519-seed` | derives the user identity Ed25519 seed from the recovery secret |
| Recovery HRP | `heyarr` | bech32m human-readable-part for the recovery secret |
| Pairing labels | `heyarr/pairing/v1/{commit,sas}` | pairing transcript domain separation |

### Algorithms (fixed)

- **Identity / signing** = **Ed25519**, rendered `ed25519:<hex>`.
- **Device encryption** = **X25519**, rendered `x25519:<hex>`.
- Key rendering is always `<alg>:<lowercase-hex>` — a bare hex key is invalid.

### Wire formats

- **Enrolment cert token** = `base64url(json payload) + "." + base64url(ed25519 sig)`.
  `base64url` is URL-safe, **no padding** (Go's `RawURLEncoding`). The payload is
  **compact** JSON with fields in this exact order (they are signed as-is):
  `{v, usr, dev, denc, iat, exp}`. The signer is the **user identity** key.
- **Membership op token** (v3, ADR-0005) = the same `base64url(payload).base64url(sig)`
  shape with payload `{v:3, usr, op, dev, denc?, by, prev:[…], cosig?, iat, exp?}` in
  that order, signed by `by` (a member device key, or `usr` for genesis). A v1/v2 cert
  IS a v3 add signed by genesis with no `prev`. `Membership.evaluate` is a line-for-line
  port of voidbind-go `enrolment.Evaluate`; the golden vectors in
  `src/jvmTest/resources/vectors/membership/` (21 today, incl. the ADR-0008 cosig
  cases) are copied from voidbind-go's `testvectors/vectors/` and must replay
  byte-for-byte — never edit them here, re-copy from Go and bump
  `src/jvmTest/resources/vectors/VOIDBIND_GO_REF`; the `vector-drift` CI job
  (`scripts/check-vector-drift.sh`) fails on any difference. `MembershipVectorTest` enumerates the directory, so a
  newly copied vector is picked up automatically.
- **Recovery secret** = 256-bit, **bech32m** (BIP-350, *not* bech32) with HRP
  `heyarr`.
- **Pairing** = short-authentication-string with **commit-before-reveal**: each
  side commits to its nonce (H(label ‖ role ‖ nonce)) before either nonce is
  revealed, so neither party can bias the final digits; both derive the SAS from a
  bound transcript and humans compare it out-of-band. Under ADR-0005 the initiator
  is ANY member device (`PairflowAuthority.Device`, or `Genesis` for the first
  device / recovery): the invite is v3 (`usr`), the initiator's reveal carries its
  `ops`, the responder EVALUATES them and refuses a non-member before any SAS
  exists, and the sealed `cert` message carries the admission `{op, ops}`.

## The hardware keystore is the whole point

`DeviceKeyStore` is an `expect class`. The reason this library exists is that a
device's signing key must be **non-extractable at rest** and gated by the secure
element. Neither the Secure Enclave nor StrongBox can hold Ed25519, so every
hardware `actual` keeps a **software Ed25519 seed** (`Ed25519Engine`) **sealed by a
hardware wrapping key**, unsealed only transiently (behind the biometric gate) to
sign one message, then zeroized — see
[ADR-0001](docs/adr/0001-hardware-keystore-mechanism.md):

- **iOS** `actual` → seed sealed by a **Secure-Enclave P-256** key (ECIES). The
  Enclave + Keychain work is done by the app-provided Swift `SecureEnclaveSealer`
  (injected via `VoidbindIos`); the Kotlin side owns signing + the store contract.
- **Android** `actual` → seed sealed with an **AES-GCM key in StrongBox / TEE
  AndroidKeyStore** (`setIsStrongBoxBacked(true)`, TEE fallback), user-auth gated.
  Needs `VoidbindAndroid.init(context)`.
- **JVM** `actual` → **software key, `isHardwareBacked == false`**, for tests/dev
  only. Never ship the JVM keystore as production signing.

## Architecture invariant: `commonMain` is platform-free

`commonMain` contains **no platform APIs** (`java.*`, `android.*`, Foundation).
Its one third-party dependency is **cryptography-kotlin** (`cryptography-core` +
`cryptography-provider-optimal`, 0.6.0), which supplies SHA-256, HKDF, Ed25519 and
X25519 on every target by delegating to vetted platform primitives (JDK on
JVM/Android, CryptoKit on Apple). The wire **encodings** stay hand-written and
dependency-free (`crypto/`), so the signed/encoded bytes are fully under our control.
Platform behaviour is reached through seams:

- `Ed25519Signer` / `Ed25519Verifier` (fun interfaces) for signing with a key the
  caller holds (e.g. the hardware-sealed device key),
- `internal expect fun aeadIetfSeal/Open` (`crypto/Aead.kt`) for IETF
  ChaCha20-Poly1305 (a fresh javax `Cipher` per op on JVM),
- `expect class DeviceKeyStore` for the hardware key,
- `net.HttpTransport` for HTTP (`JdkHttpTransport` in `jvmMain`; apps supply their
  own — OkHttp in `androidApp`, `URLSessionHttpTransport` in `iosApp`).

Platform code (`jvmMain`, `androidMain`, `iosMain`) supplies the `actual`s. Do not
reach for `java.*` / platform APIs from `commonMain`, and do not add further
third-party deps there without a reason as strong as cryptography-kotlin's.

## Build & test

Host toolchain: **JDK 21** (bytecode targets JVM 17), no system `gradle`/`kotlin` —
use the **wrapper** (`./gradlew`, self-downloads Gradle 8.9; the Kotlin plugin is
**2.3.20**, required by cryptography-kotlin 0.6.0's metadata; AGP 8.7.3). The
Android target and the `:androidApp` module need an Android SDK (`ANDROID_HOME` or
`local.properties`). The iOS targets compile only on **macOS** (Kotlin/Native Apple
targets are skipped on a Linux host).

```sh
./gradlew jvmTest                          # primary: compiles + runs common + JVM tests
./gradlew compileReleaseKotlinAndroid      # Android library compile (needs the SDK)
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest   # Cruciform app
./gradlew compileKotlinIosSimulatorArm64  # iOS compile (macOS only)
./gradlew assembleVoidbindXCFramework      # → build/XCFrameworks/{debug,release}/Voidbind.xcframework
```

Targets: `jvm()` (dev/test, software keystore), `androidTarget()` (StrongBox/TEE),
`iosArm64()` + `iosSimulatorArm64()` (Secure Enclave via the Swift sealer, exported
as the `Voidbind` XCFramework). Tests in `commonTest` run on every target; `jvmTest`
adds the golden-vector parity suites, the JVM keystore test and live-voidbind-go
interop (skipped when `go`/the voidbind-go checkout is absent). CI (`test.yml`) runs
`jvmTest` + the Android compile, the app build + unit tests, and the iOS compile on
macOS. `iosSimulatorArm64Test` is not in CI yet: the commonTest cases that compare
Ed25519 signature BYTES against Go vectors fail on Apple, because CryptoKit's
Ed25519 signing is randomized (the signatures still verify).

Published as `one.rarebit.voidbind:voidbind-client` (GitHub Packages) by
`publish.yml` on a `v*` tag, which must equal `version` in `build.gradle.kts`. The
Cruciform APK is released separately on `app-v*` tags (`release.yml`).

## Layout

```
src/
  commonMain/kotlin/one/rarebit/voidbind/
    Labels.kt          identity-defining constants (DO NOT rename)
    KeyRef.kt          ed25519:/x25519: hex rendering + parse
    RecoverySecret.kt  256-bit bech32m secret (HRP heyarr)
    Cert.kt            enrolment cert model + token encode/parse/verify
    MembershipOp.kt    v3 membership op (add/remove) sign/verify/hash; v1/v2 certs read as genesis adds
    Membership.kt      the CRDT evaluator (voidbind-go enrolment.Evaluate, ADR-0007) + merge
    Ed25519.kt         signer/verifier seams; Ed25519Engine.kt = software Ed25519 (cryptography-kotlin)
    Pairing.kt         commit-before-reveal SAS derivation
    UserIdentity.kt / DeviceIdentity.kt / Enrolment.kt   identity + self-enrolment
    Invite.kt / LoginQr.kt / WebLogin.kt / DeepLink.kt / PushPing.kt   QR, deep-link, push wire
    DeviceKeyStore.kt  expect: hardware signing key
    auth/              Device-scheme possession proof, credential, 401 re-mint policy
    net/               HttpTransport seam; Relay/Pairflow/WebLogin/Notify clients; cert sealer
    flow/              LoginApproval / DevicePairing / DeviceAuthorization coordinators
    offload/           cruciform-offload wire + phone responders (ADR-0098)
    policy/            per-RP approval policy (ADR-0002)
    crypto/            Hex, Base64Url (no-pad), Bech32m, MiniJson (compact, ordered),
                       X25519, Ed25519Group, XChaCha20-Poly1305, VoidbindEncryption, expect AEAD
  commonTest/…         pure + cryptography-kotlin tests (run on every target)
  jvmMain/…            DeviceKeyStore actual (software), JdkHttpTransport, AEAD actual
  jvmTest/…            JvmEd25519 (JDK provider, test-only) + keystore test; golden-vector
                       parity (resources/vectors/ = voidbind-go testdata, verbatim);
                       live-voidbind-go interop
  androidMain/…        DeviceKeyStore actual (StrongBox/TEE AES-GCM seal), VoidbindAndroid
  iosMain/…            DeviceKeyStore actual (Secure-Enclave seal via SecureEnclaveSealer), VoidbindIos
androidApp/            Cruciform Android app (Compose), depends on project(":")
iosApp/                Cruciform iOS app (SwiftUI, XcodeGen), links Voidbind.xcframework
```

## House style

- Port encodings from voidbind-go; never redesign a wire format here.
- Keep `commonMain` backend-free; add platform behaviour behind an `expect`/seam.
- Treat the `Labels` strings as immutable protocol identity.
- Prefer explicit, dependency-free codecs (as in `crypto/`) so the signed/encoded
  bytes are fully under our control and match Go byte-for-byte.
