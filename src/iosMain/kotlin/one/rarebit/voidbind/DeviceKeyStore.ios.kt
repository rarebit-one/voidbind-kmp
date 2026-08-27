package one.rarebit.voidbind

/**
 * iOS `actual` for [DeviceKeyStore] — **hardware-backed**.
 *
 * The device signing key is a software Ed25519 seed ([Ed25519Engine]) sealed at
 * rest by a **Secure-Enclave P-256 key** (the Enclave cannot hold Ed25519). The
 * Secure Enclave + Keychain work is done by the app-provided [SecureEnclaveSealer]
 * (Swift/CryptoKit); this class owns the Ed25519 signing and the store contract.
 * The seed is unsealed only transiently — behind the Enclave's biometric gate —
 * to produce one signature, then zeroized. See
 * docs/adr/0001-hardware-keystore-mechanism.md.
 */
actual class DeviceKeyStore private constructor(
    private val alias: String,
    private val publicKeyBytes: ByteArray,
) {

    actual val isHardwareBacked: Boolean = true

    actual fun publicKey(): KeyRef = KeyRef.ed25519(publicKeyBytes)

    actual fun sign(message: ByteArray): ByteArray {
        // Unseal is Enclave-gated (biometric); the seed is used once and wiped.
        val seed = VoidbindIos.requireSealer().unsealSeed(alias)
        try {
            return Ed25519Engine.sign(seed, message)
        } finally {
            seed.fill(0)
        }
    }

    actual companion object {
        actual fun getOrCreate(alias: String): DeviceKeyStore {
            val sealer = VoidbindIos.requireSealer()
            if (sealer.sealExists(alias)) {
                return DeviceKeyStore(alias, sealer.loadPublicKey(alias))
            }
            val generated = Ed25519Engine.generate()
            try {
                sealer.provision(alias, generated.publicKey, generated.privateSeed)
            } finally {
                generated.privateSeed.fill(0)
            }
            return DeviceKeyStore(alias, generated.publicKey)
        }
    }
}
