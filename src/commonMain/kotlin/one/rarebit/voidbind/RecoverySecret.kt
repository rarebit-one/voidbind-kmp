package one.rarebit.voidbind

import one.rarebit.voidbind.crypto.Bech32m

/**
 * The 256-bit account recovery secret, rendered as a bech32m string with HRP
 * [Labels.RECOVERY_HRP] (`heyarr`) — the same human-facing format as voidbind-go.
 *
 * The raw 32 bytes seed HKDF (label [Labels.HKDF_USER_IDENTITY_ED25519_SEED]) to
 * re-derive the user identity Ed25519 key on a fresh device; that derivation is
 * an [expect] crypto op and lives outside this pure model.
 */
class RecoverySecret private constructor(val bytes: ByteArray) {

    init {
        require(bytes.size == Labels.RECOVERY_SECRET_LEN) {
            "recovery secret must be ${Labels.RECOVERY_SECRET_LEN} bytes, got ${bytes.size}"
        }
    }

    /** Render as a bech32m string, e.g. `heyarr1...`. */
    fun format(): String {
        val fiveBit = Bech32m.convertBits(Bech32m.bytesToInts(bytes), 8, 5, pad = true)
        return Bech32m.encode(Labels.RECOVERY_HRP, fiveBit)
    }

    override fun toString(): String = format()

    override fun equals(other: Any?): Boolean =
        other is RecoverySecret && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** Wrap raw 32 bytes (e.g. freshly generated CSPRNG output). */
        fun of(bytes: ByteArray): RecoverySecret = RecoverySecret(bytes.copyOf())

        /** Parse a bech32m recovery string. Enforces the `heyarr` HRP and 32-byte length. */
        fun parse(s: String): RecoverySecret {
            val decoded = Bech32m.decode(s.trim())
            require(decoded.hrp == Labels.RECOVERY_HRP) {
                "wrong HRP: expected '${Labels.RECOVERY_HRP}', got '${decoded.hrp}'"
            }
            val bytes = Bech32m.intsToBytes(Bech32m.convertBits(decoded.data, 5, 8, pad = false))
            return RecoverySecret(bytes)
        }
    }
}
