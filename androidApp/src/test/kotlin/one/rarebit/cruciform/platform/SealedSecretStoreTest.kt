package one.rarebit.cruciform.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertFailsWith

/**
 * The seal/unseal framing and key handling of [SealedSecretStore], with the
 * AndroidKeyStore swapped for plain JCA AES keys (the cipher, AES-256-GCM, is the
 * same). StrongBox/TEE non-extractability itself is device-tested (docs/DEVICE-TESTING.md).
 */
class SealedSecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Software wrap keys keyed by alias; [created] records every alias provisioned. */
    private class SoftwareWrapKeys : WrapKeys {
        val keys = HashMap<String, SecretKey>()
        val created = mutableListOf<String>()

        override fun load(alias: String): SecretKey? = keys[alias]

        override fun create(alias: String): SecretKey {
            created += alias
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it }
        }
    }

    private val keys = SoftwareWrapKeys()
    private val dir: File by lazy { File(tmp.root, "voidbind") }
    private val store by lazy { SealedSecretStore({ dir }, keys) }

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun `seal then unseal round-trips the secret`() {
        store.seal("device-enc", secret)

        assertTrue(store.exists("device-enc"))
        assertArrayEquals(secret, store.unseal("device-enc"))
    }

    @Test
    fun `the blob at rest is not the plaintext`() {
        store.seal("recovery", secret)

        val onDisk = File(dir, "secret.recovery").readBytes()
        // 4-byte length + 12-byte IV + 4-byte length + ciphertext (32) + GCM tag (16).
        assertEquals(4 + 12 + 4 + 32 + 16, onDisk.size)
        assertFalse(onDisk.toList().windowed(secret.size).any { it == secret.toList() })
    }

    @Test
    fun `each secret gets its own wrapping key alias`() {
        store.seal("device-enc", secret)
        store.seal("recovery", secret)

        assertEquals(listOf("voidbind.secret.wrap.device-enc", "voidbind.secret.wrap.recovery"), keys.created)
    }

    @Test
    fun `re-sealing reuses the existing wrapping key and replaces the value`() {
        store.seal("device-enc", secret)
        val replacement = ByteArray(32) { 7 }
        store.seal("device-enc", replacement)

        assertEquals(1, keys.created.size)
        assertArrayEquals(replacement, store.unseal("device-enc"))
    }

    @Test
    fun `an unknown name is absent and unseals to null`() {
        assertFalse(store.exists("nope"))
        assertNull(store.unseal("nope"))
    }

    @Test
    fun `a blob whose wrapping key is gone unseals to null`() {
        store.seal("device-enc", secret)
        keys.keys.clear() // e.g. the keystore entry was wiped (lock-screen removal on some OEMs)

        assertTrue(store.exists("device-enc"))
        assertNull(store.unseal("device-enc"))
    }

    @Test
    fun `a truncated blob unseals to null instead of throwing`() {
        store.seal("device-enc", secret)
        val f = File(dir, "secret.device-enc")
        f.writeBytes(f.readBytes().copyOf(10))

        assertNull(store.unseal("device-enc"))
    }

    @Test
    fun `a tampered ciphertext fails authentication`() {
        store.seal("device-enc", secret)
        val f = File(dir, "secret.device-enc")
        val bytes = f.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        f.writeBytes(bytes)

        // GCM refuses the forged tag rather than returning garbage.
        assertFailsWith<AEADBadTagException> { store.unseal("device-enc") }
    }

    @Test
    fun `the directory is created on first use`() {
        assertFalse(dir.exists())
        store.seal("device-enc", secret)
        assertTrue(dir.isDirectory)
    }
}
