# Hardware keystore

Moved verbatim from the old `CLAUDE.md` ("The hardware keystore is the whole point").

`DeviceKeyStore` is an `expect class`. The reason this library exists is that a
device's signing key must be **non-extractable at rest** and gated by the secure
element. Neither the Secure Enclave nor StrongBox can hold Ed25519, so every
hardware `actual` keeps a **software Ed25519 seed** (`Ed25519Engine`) **sealed by a
hardware wrapping key**, unsealed only transiently (behind the biometric gate) to
sign one message, then zeroized — see
[ADR-0001](../adr/0001-hardware-keystore-mechanism.md):

- **iOS** `actual` → seed sealed by a **Secure-Enclave P-256** key (ECIES). The
  Enclave + Keychain work is done by the app-provided Swift `SecureEnclaveSealer`
  (injected via `VoidbindIos`); the Kotlin side owns signing + the store contract.
- **Android** `actual` → seed sealed with an **AES-GCM key in StrongBox / TEE
  AndroidKeyStore** (`setIsStrongBoxBacked(true)`, TEE fallback), user-auth gated.
  Needs `VoidbindAndroid.init(context)`.
- **JVM** `actual` → **software key, `isHardwareBacked == false`**, for tests/dev
  only. Never ship the JVM keystore as production signing.
