# commonMain seams

Moved verbatim from the old `CLAUDE.md` ("Architecture invariant: `commonMain` is platform-free"). The invariant itself stays in [`AGENTS.md`](../../AGENTS.md).

Platform behaviour is reached through seams:

- `Ed25519Signer` / `Ed25519Verifier` (fun interfaces) for signing with a key the
  caller holds (e.g. the hardware-sealed device key),
- `internal expect fun aeadIetfSeal/Open` (`crypto/Aead.kt`) for IETF
  ChaCha20-Poly1305 (a fresh javax `Cipher` per op on JVM),
- `expect class DeviceKeyStore` for the hardware key,
- `net.HttpTransport` for HTTP (`JdkHttpTransport` in `jvmMain`; apps supply their
  own — OkHttp in `androidApp`, `URLSessionHttpTransport` in `iosApp`).

Platform code (`jvmMain`, `androidMain`, `iosMain`) supplies the `actual`s. Do not
