package one.rarebit.voidbind

/**
 * iOS `actual` for [DeviceKeyStore] — the production target this library exists for.
 *
 * SCAFFOLD STATUS: the Secure Enclave binding is stubbed. The real implementation
 * uses the Security framework via cinterop:
 *   - `SecKeyCreateRandomKey` with attributes
 *     `kSecAttrTokenID = kSecAttrTokenIDSecureEnclave`,
 *     `kSecAttrKeyType = kSecAttrKeyTypeECSECPrimeRandom` (P-256 on the Enclave;
 *     Ed25519 is emulated at a higher layer where a raw Ed25519 Enclave key is not
 *     available — mirror voidbind-go's device-key policy here),
 *     `kSecAttrIsPermanent = true`, access control
 *     `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` + `.privateKeyUsage`.
 *   - The private key is non-extractable; signing calls `SecKeyCreateSignature`.
 *   - `getOrCreate` looks up the key by [alias] (`kSecAttrApplicationTag`) and
 *     provisions on first use.
 *
 * Building/testing the iOS targets requires the Kotlin/Native toolchain and (for
 * the cinterop above) Xcode; that is intentionally out of scope for the JVM-verified
 * build in this environment. This stub keeps the target's shape honest and compiles
 * without cinterop, and throws loudly if invoked so it can never be mistaken for a
 * working Enclave binding.
 */
actual class DeviceKeyStore private constructor(private val rawPublicKey: ByteArray) {

    actual val isHardwareBacked: Boolean = true

    actual fun publicKey(): KeyRef = KeyRef.ed25519(rawPublicKey)

    actual fun sign(message: ByteArray): ByteArray =
        throw NotImplementedError(
            "Secure Enclave signing not yet wired — implement via Security.framework " +
                "SecKeyCreateSignature (cinterop). See class KDoc."
        )

    actual companion object {
        actual fun getOrCreate(alias: String): DeviceKeyStore =
            throw NotImplementedError(
                "Secure Enclave key provisioning not yet wired for alias='$alias' — " +
                    "implement via SecKeyCreateRandomKey (cinterop). See class KDoc."
            )
    }
}
