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
- **Recovery secret** = 256-bit, **bech32m** (BIP-350, *not* bech32) with HRP
  `heyarr`.
- **Pairing** = short-authentication-string with **commit-before-reveal**: each
  side commits to its nonce (H(label ‖ role ‖ nonce)) before either nonce is
  revealed, so neither party can bias the final digits; both derive the SAS from a
  bound transcript and humans compare it out-of-band.

## The hardware keystore is the whole point

`DeviceKeyStore` is an `expect class`. The reason this library exists is that a
device's signing key must be **non-extractable** and live in the secure element:

- **iOS** `actual` → Secure Enclave (`kSecAttrTokenIDSecureEnclave`), via
  Security.framework cinterop. **(currently a documented stub — see the file.)**
- **Android** `actual` → StrongBox / TEE AndroidKeyStore
  (`setIsStrongBoxBacked(true)`). *(target not yet added — needs the Android SDK.)*
- **JVM** `actual` → **software key, `isHardwareBacked == false`**, for tests/dev
  only. Never ship the JVM keystore as production signing.

## Architecture invariant: `commonMain` is pure

`commonMain` contains **only pure Kotlin** — no platform APIs, no crypto backend,
no third-party deps. All actual crypto is reached through seams:

- `Ed25519Signer` / `Ed25519Verifier` (fun interfaces) for the curve ops,
- `Pairing.HashFunction` for the pairing hash,
- `expect class DeviceKeyStore` for the hardware key.

Platform code (`jvmMain`, `iosMain`) supplies the `actual`s. This keeps the
encodings unit-testable with no backend and portable to every target. Do not
reach for `java.*` / platform APIs from `commonMain`.

## Build & test

Host toolchain: **JDK 21**, no system `gradle`/`kotlin` — use the **wrapper**
(`./gradlew`, self-downloads Gradle 8.9 + Kotlin 2.0.21). No Android SDK here, so
there is **no `androidTarget`** (adding one needs AGP + the SDK).

```sh
./gradlew jvmTest              # primary: compiles + runs common + JVM tests
./gradlew compileKotlinJvm     # JVM compile only
./gradlew compileKotlinIosSimulatorArm64   # iOS compile (Kotlin/Native, no Xcode needed)
```

Targets: `jvm()` (primary, buildable + testable), `iosArm64()`,
`iosSimulatorArm64()` (declared for structure). Tests in `commonTest` are pure and
run on every target; `jvmTest` adds a real-Ed25519 end-to-end check via the JDK
provider.

## Layout

```
src/
  commonMain/kotlin/one/rarebit/voidbind/
    Labels.kt          identity-defining constants (DO NOT rename)
    KeyRef.kt          ed25519:/x25519: hex rendering + parse
    RecoverySecret.kt  256-bit bech32m secret (HRP heyarr)
    Cert.kt            enrolment cert model + token encode/parse/verify
    Ed25519.kt         signer/verifier seams
    Pairing.kt         commit-before-reveal SAS derivation
    DeviceKeyStore.kt  expect: hardware signing key
    crypto/            Hex, Base64Url (no-pad), Bech32m, MiniJson (compact, ordered)
  commonTest/…         bech32m roundtrip, cert roundtrip, SAS determinism
  jvmMain/…            DeviceKeyStore actual (software) + JvmEd25519 (JDK provider)
  jvmTest/…            real-Ed25519 end-to-end
  iosMain/…            DeviceKeyStore actual (Secure Enclave — stub, documented)
```

## House style

- Port encodings from voidbind-go; never redesign a wire format here.
- Keep `commonMain` backend-free; add platform behaviour behind an `expect`/seam.
- Treat the `Labels` strings as immutable protocol identity.
- Prefer explicit, dependency-free codecs (as in `crypto/`) so the signed/encoded
  bytes are fully under our control and match Go byte-for-byte.
