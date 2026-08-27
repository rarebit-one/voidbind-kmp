package one.rarebit.voidbind

import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM `actual` for [DeviceKeyStore]. **Software key — NOT hardware-backed.**
 *
 * Intended for unit tests and desktop/dev runs only. It generates an in-heap
 * Ed25519 key pair per alias (cached for the process lifetime) so the JVM target
 * compiles and the pure domain is testable end-to-end. There is no secure
 * element on a plain JVM; [isHardwareBacked] is therefore always `false`.
 */
actual class DeviceKeyStore private constructor(private val keyPair: KeyPair) {

    actual val isHardwareBacked: Boolean = false

    actual fun publicKey(): KeyRef = KeyRef.ed25519(JvmEd25519.rawPublicKey(keyPair.public))

    actual fun sign(message: ByteArray): ByteArray = JvmEd25519.sign(keyPair.private, message)

    actual companion object {
        private val store = ConcurrentHashMap<String, DeviceKeyStore>()

        actual fun getOrCreate(alias: String): DeviceKeyStore =
            store.getOrPut(alias) { DeviceKeyStore(JvmEd25519.generate()) }
    }
}
