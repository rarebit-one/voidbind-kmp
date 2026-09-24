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
class SealedSecretStore internal constructor(
    /** The directory the sealed blobs live in; resolved (and created) on each access. */
    private val dir: () -> File,
    /** Where the per-secret wrapping keys live — the AndroidKeyStore in production. */
    private val wrapKeys: WrapKeys,
) : SecretSealer {

    constructor(context: Context) : this({ File(context.filesDir, "voidbind") }, AndroidKeyStoreWrapKeys)

    override fun exists(name: String): Boolean = file(name).exists()

    override fun seal(name: String, secret: ByteArray) {
        val key = wrapKeys.load(wrapAlias(name)) ?: wrapKeys.create(wrapAlias(name))
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = cipher.doFinal(secret)
        writeFramed(file(name), cipher.iv, ct)
    }

    override fun unseal(name: String): ByteArray? {
        val f = file(name)
        if (!f.exists()) return null
        val (iv, ct) = readFramed(f) ?: return null
        val key = wrapKeys.load(wrapAlias(name)) ?: return null
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ct)
        }
    }

    private fun file(name: String): File {
        val root = dir().apply { mkdirs() }
        return File(root, "secret.$name")
    }

    private fun writeFramed(f: File, iv: ByteArray, ct: ByteArray) {
        val out = ArrayList<Byte>()
        fun put(b: ByteArray) {
            val n = b.size
            out.add((n ushr 24).toByte())
            out.add((n ushr 16).toByte())
            out.add((n ushr 8).toByte())
            out.add(n.toByte())
            b.forEach { out.add(it) }
        }
        put(iv)
        put(ct)
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
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        fun wrapAlias(name: String) = "voidbind.secret.wrap.$name"
    }
}

/**
 * Named secrets sealed at rest. [SealedSecretStore] is the hardware-backed
 * implementation; the seam lets [IdentityStore] be unit-tested without a keystore.
 */
interface SecretSealer {
    fun exists(name: String): Boolean

    fun seal(name: String, secret: ByteArray)

    /** The secret, or null when it was never sealed or its wrapping key is gone. */
    fun unseal(name: String): ByteArray?
}

/** Holds the non-extractable AES wrapping key for each sealed secret, by alias. */
internal interface WrapKeys {
    fun load(alias: String): SecretKey?

    fun create(alias: String): SecretKey
}

/** The AndroidKeyStore: StrongBox where the device has one, TEE otherwise. */
internal object AndroidKeyStoreWrapKeys : WrapKeys {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_KEY_BITS = 256

    override fun load(alias: String): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey
    }

    override fun create(alias: String): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(WRAP_KEY_BITS)
            .setIsStrongBoxBacked(strongBox)
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }
}
