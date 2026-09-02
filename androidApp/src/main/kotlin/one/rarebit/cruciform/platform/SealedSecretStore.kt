package one.rarebit.cruciform.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals app-owned secrets at rest with a non-extractable AES-256-GCM key held in
 * the AndroidKeyStore (StrongBox where the device has one, TEE otherwise) — the
 * same mechanism the library's [one.rarebit.voidbind.DeviceKeyStore] uses for the
 * Ed25519 seed (ADR-0001), applied here to the device **X25519 encryption private
 * key**, which no secure element can hold as an agreement key.
 *
 * Deliberately **not** user-authentication-gated: this is at-rest protection, and
 * the key is unsealed inside a pairing flow that is already gated on the SAS + a
 * biometric signature. An attacker with the flash but not the secure element cannot
 * recover the sealed secret.
 */
class SealedSecretStore(private val context: Context) {

    fun exists(name: String): Boolean = file(name).exists()

    fun seal(name: String, secret: ByteArray) {
        val key = getOrCreateWrapKey(name)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = cipher.doFinal(secret)
        writeFramed(file(name), cipher.iv, ct)
    }

    fun unseal(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        val (iv, ct) = readFramed(f) ?: return null
        val key = loadWrapKey(name) ?: return null
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ct)
        }
    }

    private fun getOrCreateWrapKey(name: String): SecretKey =
        loadWrapKey(name) ?: createWrapKey(name)

    private fun createWrapKey(name: String): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            wrapAlias(name),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(WRAP_KEY_BITS)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            gen.init(spec(strongBox = true)); gen.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            gen.init(spec(strongBox = false)); gen.generateKey()
        }
    }

    private fun loadWrapKey(name: String): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getKey(wrapAlias(name), null) as? SecretKey
    }

    private fun file(name: String): File {
        val dir = File(context.filesDir, "voidbind").apply { mkdirs() }
        return File(dir, "secret.$name")
    }

    private fun writeFramed(f: File, iv: ByteArray, ct: ByteArray) {
        val out = ArrayList<Byte>()
        fun put(b: ByteArray) {
            val n = b.size
            out.add((n ushr 24).toByte()); out.add((n ushr 16).toByte())
            out.add((n ushr 8).toByte()); out.add(n.toByte())
            b.forEach { out.add(it) }
        }
        put(iv); put(ct)
        f.writeBytes(out.toByteArray())
    }

    private fun readFramed(f: File): Pair<ByteArray, ByteArray>? = try {
        val bytes = f.readBytes()
        var i = 0
        fun take(): ByteArray {
            val n = ((bytes[i].toInt() and 0xFF) shl 24) or ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            i += 4
            val slice = bytes.copyOfRange(i, i + n)
            i += n
            return slice
        }
        take() to take()
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val WRAP_KEY_BITS = 256
        fun wrapAlias(name: String) = "voidbind.secret.wrap.$name"
    }
}
