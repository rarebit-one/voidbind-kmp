package one.rarebit.voidbind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end on the JVM target using the REAL JDK Ed25519 backend: the software
 * [DeviceKeyStore] signs an enrolment cert and the JDK verifier accepts it. Proves
 * the pure domain composes with a real crypto backend across the `expect`/`actual`
 * seam (the iOS Secure Enclave `actual` slots into the same interface).
 */
class JvmDeviceKeyStoreTest {

    @Test
    fun softwareKeyStoreSignsAndVerifiesACert() {
        val store = DeviceKeyStore.getOrCreate("test-device")
        assertFalse(store.isHardwareBacked, "JVM keystore is explicitly software-only")

        // Device signing key IS the cert's user identity key for this smoke test.
        val deviceKey = store.publicKey()
        assertEquals(Labels.ALG_ED25519, deviceKey.alg)

        val cert = Cert(
            version = Labels.CERT_VERSION,
            user = deviceKey,
            device = deviceKey,
            deviceEnc = KeyRef.x25519(ByteArray(32) { (it + 4).toByte() }),
            issuedAt = 1_724_700_000L,
            expiresAt = 1_756_236_000L,
        )

        val token = cert.encode(store.asSigner())
        assertTrue(cert.verify(token, JvmEd25519.verifier()), "real Ed25519 must verify")

        val parsed = Cert.parse(token)
        assertEquals(cert, parsed.cert)
        assertTrue(parsed.verify(JvmEd25519.verifier()))
    }

    @Test
    fun getOrCreateIsStablePerAlias() {
        val a = DeviceKeyStore.getOrCreate("alias-x")
        val b = DeviceKeyStore.getOrCreate("alias-x")
        assertEquals(a.publicKey(), b.publicKey(), "same alias → same key")
    }
}
