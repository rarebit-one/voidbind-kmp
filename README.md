# voidbind-kmp

Voidbind — the **Kotlin Multiplatform device authenticator**: hardware-backed
device keys, commit-before-reveal pairing, and account recovery, sharing **one
wire contract** with [`voidbind-go`](https://github.com/rarebit-one/voidbind-go).

Voidbind is a device-authentication protocol extracted from **heyarr**.
`voidbind-go` holds the wire contract (the source of truth); this repo is the
on-device side for **iOS / Android** (plus a JVM target for dev/test), and its
reason to exist is keeping the device signing key **non-extractable in the secure
element** (Secure Enclave / StrongBox).

## Status

Scaffold. The pure-Kotlin domain + JVM target build and test; iOS targets compile;
the iOS Secure Enclave binding and an Android target are stubbed/pending (see
[`CLAUDE.md`](CLAUDE.md)).

## What's here

Pure-Kotlin (`commonMain`) re-implementations of the voidbind-go wire types — no
platform APIs, no third-party deps:

- **`RecoverySecret`** — 256-bit account secret as **bech32m** (HRP `heyarr`).
- **`Cert`** — enrolment cert token `base64url(json).base64url(sig)`, payload
  `{v, usr, dev, denc, iat, exp}`, signed by the user identity Ed25519 key.
- **`Pairing`** — short-authentication-string derivation with **commit-before-reveal**.
- **`Invite`** — the pairing QR `voidbind:pair?v=2&relay=&session=&salt=<hex>`;
  `encode` is byte-identical to voidbind-go's `pairflow.EncodeInvite`.
- **`KeyRef`** — `ed25519:<hex>` / `x25519:<hex>` key rendering.
- **`DeviceKeyStore`** — `expect class` for the hardware signing key (Secure
  Enclave on iOS, StrongBox on Android; software-only on the JVM, for tests).

### Network clients (`net/`)

The wire types above plus an `HttpTransport` seam (a platform supplies the engine;
`JdkHttpTransport` backs JVM/Android and the tests) drive the live voidbind-go
services:

- **`RelayClient`** — the dumb pairing relay (`POST /v1/sessions`, `PUT`/`GET`
  `/v1/sessions/{id}/{role}/{type}`); `fetch` polls the peer slot.
- **`PairflowInitiator` / `PairflowResponder`** — the commit-before-reveal
  handshake with the human gate (`handshake()` returns the SAS and signs nothing;
  `authorise` / `receive` move the cert). The X25519 cert **seal** is
  `VoidbindCertSealer` (the default), completing the sealed cert delivery.
- **`VoidbindCertSealer` / `crypto.VoidbindEncryption`** — the ephemeral-static
  X25519 ECDH seal + XChaCha20-Poly1305, byte-identical to voidbind-go/encryption
  (`Seal`/`Unwrap`/`EncryptChange`/`DecryptChange`). Pure-Kotlin X25519 (a
  TweetNaCl port, so `unwrap` can derive the recipient public key the JDK X25519
  provider will not) + a hand-written HChaCha20 over cryptography-kotlin's IETF
  ChaCha20-Poly1305 and HKDF. Pinned by RFC vectors and a **live-voidbind-go
  KAT** (`CertSealerCryptoTest.goSealKat`: Kotlin unwraps + decrypts a blob that
  Go sealed, to the exact bytes).
- **`WebLoginClient`** — the QR web-login: device side (`fetchChallenge`,
  `approve` with a `WebLogin.signAssertion` assertion) and browser side
  (`createLogin`, `poll`).

These are **proven against a live voidbind-go** in `GoInteropTest` (JVM): two
Kotlin sides pair through the real Go relay (SAS matches), and a Kotlin device
approves a login on the real Go RP, which verifies the Kotlin-signed cert and
assertion and mints a token. That test builds + runs the `voidbind` CLI, so it is
skipped (not failed) when `go`/the voidbind-go checkout is absent — CI keeps the
in-JVM mock coverage in `NetworkClientsTest`.

Crypto backends (Ed25519, the pairing hash) are reached through interface seams so
the encodings stay backend-free and portable.

Identity/signing = **Ed25519**; device encryption = **X25519**. Certain constants
(the HKDF label, the `heyarr` HRP, the pairing labels) are **identity-defining** —
see [`CLAUDE.md`](CLAUDE.md); changing them silently breaks wire compatibility.

## Build

Requires **JDK 21**. Uses the Gradle wrapper (self-downloads Gradle 8.9 +
Kotlin 2.0.21); no Android SDK required (there is no `androidTarget` yet).

```sh
./gradlew jvmTest                          # compile + run common + JVM tests
./gradlew compileKotlinJvm                 # JVM compile only
./gradlew compileKotlinIosSimulatorArm64   # iOS compile (Kotlin/Native)
```

## Targets

| Target | State |
|---|---|
| `jvm()` | primary — buildable **and** testable (software keystore) |
| `iosArm64()`, `iosSimulatorArm64()` | compile; Secure Enclave `actual` is a documented stub |
| Android | not yet added (needs the Android Gradle Plugin + SDK) |

## Layout

See [`CLAUDE.md`](CLAUDE.md) for the full source map and the wire-contract rules.
