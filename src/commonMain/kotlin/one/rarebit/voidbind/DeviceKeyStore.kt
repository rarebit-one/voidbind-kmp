package one.rarebit.voidbind

/**
 * The **whole point** of voidbind-kmp: a device's signing key must live in
 * hardware and never leave it. This is the platform seam for that key.
 *
 * The `actual` for each target binds to the platform secure element:
 * - **iOS** → Secure Enclave (`kSecAttrTokenIDSecureEnclave`), key non-extractable.
 * - **Android** → StrongBox / TEE-backed AndroidKeyStore, `setIsStrongBoxBacked(true)`.
 * - **JVM** → software key only (dev/test); [isHardwareBacked] is `false`. NOT for production.
 *
 * The key is Ed25519 (identity/signing). The store exposes the public key as a
 * [KeyRef] and can [sign] via [asSigner], but never exposes private key material.
 */
expect class DeviceKeyStore {

    /** True only when the private key is held in a hardware secure element. */
    val isHardwareBacked: Boolean

    /** The device signing public key, rendered as `ed25519:<hex>`. */
    fun publicKey(): KeyRef

    /** Sign [message] with the non-extractable device key; returns a 64-byte Ed25519 signature. */
    fun sign(message: ByteArray): ByteArray

    companion object {
        /**
         * Create or load the device signing key under [alias]. On iOS/Android this
         * provisions a hardware-backed, non-extractable key on first use.
         */
        fun getOrCreate(alias: String): DeviceKeyStore
    }
}

/** Adapt a [DeviceKeyStore] to the pure [Ed25519Signer] seam used by [Cert.encode]. */
fun DeviceKeyStore.asSigner(): Ed25519Signer = Ed25519Signer { message -> sign(message) }
