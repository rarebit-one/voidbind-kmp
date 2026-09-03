# voidbind-kmp

Voidbind — the **Kotlin Multiplatform device-authenticator library**: hardware-backed
device keys, commit-before-reveal pairing, and account recovery, sharing **one
wire contract** with [`voidbind-go`](https://github.com/rarebit-one/voidbind-go).
The first-party authenticator **app** built on it is **Cruciform**
([`androidApp/`](androidApp/README.md), [`iosApp/`](iosApp/README.md)) — *Voidbind*
names the protocol and the `voidbind:` scheme, *Cruciform* names the app
([ADR-0004](docs/adr/0004-authenticator-named-cruciform.md)).

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
- **`LoginQr` / `VoidbindQr`** — the web-login QR `voidbind:login?rp=&id=`
  (byte-identical to voidbind-go's `weblogin.EncodeLogin`), and a single
  `VoidbindQr.parse` the Scan screen calls to dispatch a scanned code to the
  login-approval or pairing flow.
- **`VoidbindDeepLink`** — the **same-device app-to-app handoff** URI (ADR-0003): the
  QR tuple plus an optional `callback=<private-app-scheme-uri>`, for a relying-party
  app on the SAME phone to open the authenticator instead of showing a QR. See
  "Same-device handoff" below.
- **`KeyRef`** — `ed25519:<hex>` / `x25519:<hex>` key rendering.
- **`auth/`** — the **`Device` authorization scheme** a relying party's API accepts
  from an enrolled device: `PossessionProof` (byte-exact port of voidbind-go
  `enrolment.SignPossession`/`VerifyPossession`), `DeviceCredential`
  (`Authorization: Device <cert>~<proof>` with a reuse window + `refresh()`), and
  `DeviceAuthPolicy` (re-mint and retry once on `401`, transport-agnostic). See
  "Presenting a device credential" below.
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

### Identity & enrolment

The app-facing spine that turns a recovery secret into a usable identity — the
part an onboarding screen ("Create a new identity" / "Restore from recovery
secret") drives:

- **`UserIdentity`** — `create()` mints a fresh identity (returns the
  `RecoverySecret` to back up once); `restore(secret)` reconstructs it, failing
  **loud** on a mistyped secret. The user key is derived byte-identically to
  voidbind-go's `recovery.DeriveUserSeed`
  (`HKDF-SHA256(secret, info="heyarr/recovery/v1/user-identity-ed25519-seed")`),
  and its **public half** is recovered with a pure-Kotlin Ed25519
  (`crypto.Ed25519Group`, a TweetNaCl port) **because neither the JDK nor Apple
  will derive an Ed25519 public key from a seed** — proven against a
  live-voidbind-go KAT and cross-validated against Go stdlib + OpenSSL.
- **`DeviceIdentity`** — this device's key material: the hardware Ed25519 signing
  key (a public key + a `sign` function, from `DeviceKeyStore`) and its X25519
  encryption keypair (`generateEncryptionKey()`; sealed at rest by the app).
- **`Enrolment.selfEnrol`** — the first device self-signs its enrolment cert with
  the user key (the bootstrap case; a *second* device instead pairs over the relay).

### App-flow coordinators (`flow/`)

The thin, testable "brain" each app screen binds to — a `begin → show the human a
SAS/audience → confirm` shape around the human gate, wrapping the network clients
above. UI (Compose / SwiftUI) stays a thin view over these; run them off the main
thread (they block on the relay / network).

- **`LoginApproval`** — approve a browser web-login. `begin(qr)` fetches the
  challenge and returns what the approval sheet shows (rp, audience, expiry);
  `approve(request)` signs it with the device key (biometric-gated) and submits.
- **`DevicePairing`** — the NEW device joining. `begin(inviteQr)` runs the
  handshake and returns the SAS; `confirm(handshake)` (after the human matches the
  SAS) unseals + verifies the delivered cert and returns the token to persist.
- **`DeviceAuthorization`** — the EXISTING device adding a new one (mirror of
  `DevicePairing`). `invite(relayBase)` renders the invite QR; `handshake` returns
  the SAS; `authorise` signs + seals + delivers the cert.

These are proven against each other AND, in `CoordinatorGoInteropTest`, against a
**live voidbind-go** (a coordinator-driven login on the real Go RP; the two
pairing coordinators through the real Go relay).

Crypto backends (Ed25519, the pairing hash) are reached through interface seams so
the encodings stay backend-free and portable.

Identity/signing = **Ed25519**; device encryption = **X25519**. Certain constants
(the HKDF label, the `heyarr` HRP, the pairing labels) are **identity-defining** —
see [`CLAUDE.md`](CLAUDE.md); changing them silently breaks wire compatibility.

## Consuming `voidbind-client` as a dependency

The shared client — everything above (the `commonMain` identity/net/flow wire
brain: `UserIdentity`, `DeviceIdentity`, `Enrolment`, `RelayClient`,
`WebLoginClient`, `NotifyClient`, `PairflowInitiator`/`PairflowResponder`,
`VoidbindCertSealer`, the `LoginApproval`/`DevicePairing`/`DeviceAuthorization`
coordinators, `LoginQr`/`WebLogin` + the challenge-v2 number-match), **plus** the
`DeviceKeyStore` hardware seam — is published as a Kotlin Multiplatform artifact so
relying-party apps depend on it over the wire instead of re-implementing the login
seam.

- **Coordinates:** `one.rarebit.voidbind:voidbind-client:0.5.0` (Gradle resolves
  the right variant per target: `-jvm`, `-android`, `-iosarm64`,
  `-iossimulatorarm64`).
- **Registry:** GitHub Packages — `https://maven.pkg.github.com/rarebit-one/voidbind-kmp`
  (private; a read requires a token with `read:packages`).
- **Published by CI** on a `v*` tag / GitHub Release (`.github/workflows/publish.yml`).

### What the artifact does NOT carry (stays per-app)

The published module is the shared **wire/flow brain**. The **per-platform
hardware wiring** stays in each consuming app, because it needs app-owned objects
the library cannot hold:

- **Android** — call `VoidbindAndroid.init(applicationContext)` once (e.g. in
  `Application.onCreate`) and drive the biometric gate (`BiometricPrompt`) around
  `DeviceKeyStore`/the flow coordinators. The StrongBox/TEE seal itself is in the
  artifact (`androidMain`); the `Context` + prompt are yours.
- **iOS** — implement the `SecureEnclaveSealer` protocol in Swift (CryptoKit /
  Security) and inject it once via `VoidbindIos.shared.doInit(sealer:)`. (iOS apps
  typically link the `Voidbind.xcframework` — see below — rather than the Maven
  artifact.)

### Gradle setup (consuming app)

```kotlin
// settings.gradle.kts — add the GitHub Packages repo
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/rarebit-one/voidbind-kmp")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")   // a PAT with read:packages
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts — the single dependency line that replaces the login seam
implementation("one.rarebit.voidbind:voidbind-client:0.2.0")
```

Adding this lets `allthing-android` / `heyarr-mobile` **delete their thin
wire-compatible `login/` seam** and call the real `WebLoginClient` / `LoginQr` /
`LoginApproval` directly.

### Same-device handoff (app-to-app deep link)

An RP app running on the **same phone** as the Voidbind authenticator does not need a
second phone to scan its QR. It launches the authenticator with the same tuple its
broker returned, and resumes when the authenticator finishes (the Singpass
app-to-app model — ONE authenticator, N RPs). The contract:

```
voidbind:login?rp=<origin>&id=<login-id>[&callback=<private-app-scheme-uri>]
voidbind:pair?v=2&relay=&session=&salt=<hex>[&callback=<private-app-scheme-uri>]
```

- The tuple is **exactly** the `qr` string from `POST /login` (byte-identical to
  voidbind-go's `weblogin.EncodeLogin`); `callback` is the only addition.
- The authenticator runs its normal approval: fetches the challenge from `rp`, **shows
  the origin** (and the number-match grid for a v2 challenge), and signs hardware-gated
  only after the user taps Approve + biometrics. **A deep link can never auto-approve.**
- After the user decides, the authenticator **finishes** so the RP's task resumes. The
  RP learns the outcome **only** by polling its broker (`GET /login/{id}`) — nothing
  about the login is ever passed back through the link.
- `callback`, if present and well-formed (a private app scheme — not `http(s)`,
  `javascript`, `file`, `content`, `intent`, `voidbind`), is launched **bare**, only
  after a **successful** approval, so the RP can foreground itself. Treat it as a hint:
  it is dropped if malformed, never launched on deny, and ignored if no app handles it.

Build the URI with the helper so the wire is never re-encoded by hand:

```kotlin
// RP app (Android), after POST /login returned {id, qr}:
val uri = VoidbindDeepLink.loginUriFromTuple(qr, callback = "heyarr://login/done")
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))   // then keep polling GET /login/{id}
// (the authenticator is not installed → ActivityNotFoundException: fall back to showing the QR)
```

`VoidbindDeepLink.pairUri(inviteTuple, callback)` does the same for a pairing invite
(the authenticator joins as the **new** device — the same role as scanning the invite).
See [`docs/adr/0003-app-to-app-deeplink-handoff.md`](docs/adr/0003-app-to-app-deeplink-handoff.md).

The **reverse** direction — Cruciform hands the invite it minted ("Add a device") to an RP
app on the same phone — uses the RP's *own* scheme, `heyarr-mobile://pair?invite=<encoded
voidbind:pair tuple>` / `allthing://pair?invite=…`, from a small app-side registry
(`androidApp/…/handoff/RpPairHandoff.kt`), with a Sharesheet fallback; the SAS is still
confirmed on Cruciform. See [`docs/adr/0006-rp-pair-handoff-same-device.md`](docs/adr/0006-rp-pair-handoff-same-device.md).

On ONE phone that comparison is then made **by the apps, not by the human**: the RP reports
what it derived back over a local intent, `cruciform://pair-joined?session=&dev=&sas=`, and
Cruciform checks it against what the relay revealed for the same session. Match → one sheet
("Allow *heyarr* on this phone to act as you?") behind the biometric, no code on screen, and
the RP's `<scheme>://pair-done?session=` afterwards to land the user back where they started;
mismatch → the pairing fails and nothing is signed. **Cross-device pairing keeps the 7-digit
SAS** — there is no second channel there. See
[`docs/adr/0008-same-phone-one-tap-pairing.md`](docs/adr/0008-same-phone-one-tap-pairing.md).

### Presenting a device credential

Once a device is enrolled it calls a relying party's API (heyarr's `/api/v1`, All
Thing) directly as that device — no session token — under the **`Device`**
authorization scheme:

```
Authorization: Device <cert>~<proof>
```

- `<cert>` is the enrolment cert token the device persisted from pairing/enrolment.
- `<proof>` is a **possession proof**: the device signs, with its hardware-sealed
  key, `{"v":2,"crt":base64url(sha256(cert)),"iat":…,"exp":…}` (compact JSON, that
  order; the body IS the signed message) and renders it `base64url(body).base64url(sig)`.
  A cert says a user vouches for a device key; the proof shows the caller *holds*
  that key, bound to this very cert. It is stateless and short-lived — **120 s** by
  default — and the server honours it with **+30 s not-yet-valid tolerance and
  strict expiry** (voidbind-go `PossessionTTL` / `PossessionSkew`).
- `~` is the enrolment separator (outside the base64url alphabet, so unambiguous).

`auth/` is the one implementation every relying-party app shares — a byte-exact port
of voidbind-go v0.5.0 `enrolment.SignPossession`/`VerifyPossession`, pinned by Go-minted
golden vectors (`src/jvmTest/resources/vectors/`):

```kotlin
import one.rarebit.voidbind.auth.*

// The signer is the sealed device key — the same DeviceKeyStore seam the flows use.
val credential = DeviceCredential(
    certToken = persistedCertToken,
    signer = DeviceKeyStore.getOrCreate("device").asSigner(),
    clock = { System.currentTimeMillis() / 1000 },   // unix seconds
    // ttlSeconds = 120, reuseForSeconds = 90 (ttl − skew) are the defaults
)

// Any HTTP stack: stamp the header, and re-mint + retry ONCE on a 401.
val response = DeviceAuthPolicy.execute(credential, statusOf = { it.status }) { header ->
    transport.get(url, headers = mapOf(DeviceCredential.HEADER to header))
}
```

- **`credential.headerValue()`** returns the live `Device …` value, reusing one proof
  for `reuseForSeconds` (so a biometric-gated key is not prompted per request) and
  minting a fresh one once the window lapses; **`credential.refresh()`** forces a fresh
  proof (after a `401`, or on wake).
- **`DeviceAuthPolicy`** is transport-agnostic (no OkHttp, no coroutine runtime):
  `next(status, attempt)` is the pure decision, `execute` the generic `inline` driver
  (its `send` lambda may block or suspend). A relying party answers every refusal with
  the same `401`, so "re-mint once, then surface it" is the whole strategy.
- **Pure pieces** for tests and servers: `PossessionProof.mint/verify/parse/certHash`,
  `DeviceCredential.format/parse/headerValue/mint`.

## Build

Requires **JDK 21**. Uses the Gradle wrapper (self-downloads Gradle 8.9 +
Kotlin 2.3.20). The Android target needs an Android SDK (`ANDROID_HOME` /
`local.properties`); the iOS targets need the Kotlin/Native toolchain (auto-downloaded on macOS).

```sh
./gradlew jvmTest                          # compile + run common + JVM tests
./gradlew compileReleaseKotlinAndroid      # Android compile
./gradlew compileKotlinIosArm64            # iOS device compile (Kotlin/Native)
./gradlew assembleVoidbindXCFramework      # → build/XCFrameworks/{debug,release}/Voidbind.xcframework
```

### The iOS `Voidbind.xcframework`

The SwiftUI app links the library as a single **XCFramework** named `Voidbind`
(device `ios-arm64` + `ios-arm64-simulator` slices). Build it with
`./gradlew assembleVoidbindReleaseXCFramework`, drag `Voidbind.xcframework` into
the Xcode app target (Embed & Sign), then `import Voidbind` — every type here is
exported (`UserIdentity`, `DeviceIdentity`, `Enrolment`, `LoginQr`/`VoidbindQr`,
`LoginApproval`/`DevicePairing`/`DeviceAuthorization`, `SecureEnclaveSealer`,
`VoidbindIos`). The app implements the `SecureEnclaveSealer` protocol in Swift
(CryptoKit/Security) and injects it once via `VoidbindIos.shared.doInit(sealer:)`.

## Targets

| Target | State |
|---|---|
| `jvm()` | dev/test — buildable **and** testable (software keystore) |
| `androidTarget()` | StrongBox/TEE-sealed Ed25519 seed; builds on the Android SDK |
| `iosArm64()`, `iosSimulatorArm64()` | compile + export the `Voidbind.xcframework`; the Secure Enclave `actual` needs the app-provided Swift `SecureEnclaveSealer` |

## Layout

See [`CLAUDE.md`](CLAUDE.md) for the full source map and the wire-contract rules.

## Releases — signed APKs, installed and updated via Obtainium

Debug builds are a dead end: they are signed with the throwaway debug key, so they can
never be updated in place by a real release. Every tagged version is therefore built as
a **signed release APK** and attached to a GitHub Release, and the phone tracks that
Release feed through [Obtainium](https://github.com/ImranR98/Obtainium).

### Cutting a release

```sh
git tag app-v0.7.2          # `app-v<major>.<minor>.<patch>` — **the `app-v` prefix is load-bearing**: the shared `voidbind-client` library publishes on plain `v*` tags (`publish.yml`), so the app carries its own prefix and the two triggers never collide
git push origin app-v0.7.2
```

`.github/workflows/release.yml` picks the tag up, builds `:androidApp:assembleRelease`, verifies the
APK with `apksigner verify --print-certs`, and publishes the Release with
`cruciform-0.7.2.apk` attached. `versionName` comes from the tag; `versionCode` is derived from it
(`major*10000 + minor*100 + patch`), so neither is ever hand-edited.

A release build also **forces the hardware-backed device engine** (`USE_DEVICE_ENGINE = true` in the `release` build type, plus `-PdeviceEngine=true` in CI) — the `PreviewVoidbindEngine` is a debug/CI affordance and must never ship in a signed release.

### Signing

`signingConfigs.release` reads four values, from the environment first and gradle
properties second — nothing is committed, and `*.jks` is git-ignored:

| Env | Gradle property | What |
|-----|-----------------|------|
| `RELEASE_KEYSTORE_BASE64` | `release.keystoreBase64` | the keystore, base64 |
| `RELEASE_KEYSTORE_PASSWORD` | `release.keystorePassword` | store password |
| `RELEASE_KEY_ALIAS` | `release.keyAlias` | `one.rarebit.cruciform` |
| `RELEASE_KEY_PASSWORD` | `release.keyPassword` | key password |

With none of them set the release build type is simply **unsigned** — a local
`assembleRelease` still works. CI supplies them from the repo secrets of the same
names. The keystore itself (RSA 4096, 25 years) lives at
`~/.config/rarebit-android-signing/cruciform.jks` and in **1Password → Sysadmins**.
Losing it means no user can ever update in place again.

Build a signed APK locally:

```sh
export RELEASE_KEYSTORE_BASE64=$(base64 -i ~/.config/rarebit-android-signing/cruciform.jks | tr -d '\n')
export RELEASE_KEYSTORE_PASSWORD=$(cat ~/.config/rarebit-android-signing/cruciform.password)
export RELEASE_KEY_PASSWORD="$RELEASE_KEYSTORE_PASSWORD"
export RELEASE_KEY_ALIAS=one.rarebit.cruciform
./gradlew :androidApp:assembleRelease -PreleaseVersionName=app-v0.7.2
```

### Obtainium

`obtainium.json` is the source config, importable via Obtainium → **Import/Export →
Import from a file** (or add it by hand: **Add App** → URL
`https://github.com/rarebit-one/voidbind-kmp`, source GitHub):

- APK filter regex: `cruciform-.*\.apk`
- Version extraction regex: `^app-v(.*)$`, match group `1`
- Include pre-releases: off

Obtainium needs a GitHub token for this **private** repo (Settings → Source-specific →
GitHub → Personal Access Token).
