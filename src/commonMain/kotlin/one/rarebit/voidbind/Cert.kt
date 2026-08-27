package one.rarebit.voidbind

import one.rarebit.voidbind.crypto.Base64Url
import one.rarebit.voidbind.crypto.MiniJson

/**
 * The enrolment certificate — the signed token an enrolled device presents.
 *
 * Wire shape (byte-identical to voidbind-go):
 * ```
 * token = base64url(json payload) + "." + base64url(ed25519 sig)
 * ```
 * The payload is a compact JSON object with fields **in this exact order**
 * (they are signed as-is, so order is part of the contract):
 *
 * | field  | meaning                                   | rendered as        |
 * |--------|-------------------------------------------|--------------------|
 * | `v`    | payload version ([Labels.CERT_VERSION])   | int                |
 * | `usr`  | user identity key                         | `ed25519:<hex>`    |
 * | `dev`  | device signing key                        | `ed25519:<hex>`    |
 * | `denc` | device encryption key                     | `x25519:<hex>`     |
 * | `iat`  | issued-at (unix seconds)                  | int                |
 * | `exp`  | expiry (unix seconds)                     | int                |
 *
 * The signature is produced by the *user identity* key over the raw payload
 * JSON bytes.
 */
data class Cert(
    val version: Int,
    val user: KeyRef,
    val device: KeyRef,
    val deviceEnc: KeyRef,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    init {
        require(user.alg == Labels.ALG_ED25519) { "usr must be ed25519, got ${user.alg}" }
        require(device.alg == Labels.ALG_ED25519) { "dev must be ed25519, got ${device.alg}" }
        require(deviceEnc.alg == Labels.ALG_X25519) { "denc must be x25519, got ${deviceEnc.alg}" }
    }

    /** The exact JSON bytes that are signed (and that `base64url` wraps). */
    fun signingBytes(): ByteArray = MiniJson.encodeObject(
        listOf(
            "v" to version,
            "usr" to user.render(),
            "dev" to device.render(),
            "denc" to deviceEnc.render(),
            "iat" to issuedAt,
            "exp" to expiresAt,
        )
    ).encodeToByteArray()

    /** Sign the payload with the user identity key and render the token. */
    fun encode(signer: Ed25519Signer): String {
        val payload = signingBytes()
        val sig = signer.sign(payload)
        return Base64Url.encode(payload) + "." + Base64Url.encode(sig)
    }

    /** Verify the token's signature against this cert's `usr` (user identity) key. */
    fun verify(token: String, verifier: Ed25519Verifier): Boolean {
        val dot = token.indexOf('.')
        if (dot < 0) return false
        val payload = Base64Url.decode(token.substring(0, dot))
        val sig = Base64Url.decode(token.substring(dot + 1))
        return verifier.verify(user.bytes, payload, sig)
    }

    companion object {
        /**
         * Parse a token into a [Cert] and its raw signature. Does NOT verify the
         * signature (that needs an [Ed25519Verifier]) — call [Cert.verify] with the
         * same token afterwards.
         */
        fun parse(token: String): Parsed {
            val dot = token.indexOf('.')
            require(dot > 0 && dot < token.length - 1) { "malformed cert token (missing '.')" }
            val payloadBytes = Base64Url.decode(token.substring(0, dot))
            val sig = Base64Url.decode(token.substring(dot + 1))
            val obj = MiniJson.parseObject(payloadBytes.decodeToString())

            fun str(k: String): String =
                (obj[k] as? String) ?: throw IllegalArgumentException("cert payload missing string '$k'")
            fun num(k: String): Long =
                (obj[k] as? Long) ?: throw IllegalArgumentException("cert payload missing int '$k'")

            val cert = Cert(
                version = num("v").toInt(),
                user = KeyRef.parse(str("usr")),
                device = KeyRef.parse(str("dev")),
                deviceEnc = KeyRef.parse(str("denc")),
                issuedAt = num("iat"),
                expiresAt = num("exp"),
            )
            return Parsed(cert, payloadBytes, sig)
        }
    }

    /** A parsed token: the [cert], the raw signed [payload] bytes, and the [signature]. */
    data class Parsed(val cert: Cert, val payload: ByteArray, val signature: ByteArray) {
        fun verify(verifier: Ed25519Verifier): Boolean =
            verifier.verify(cert.user.bytes, payload, signature)

        override fun equals(other: Any?): Boolean =
            other is Parsed && cert == other.cert &&
                payload.contentEquals(other.payload) && signature.contentEquals(other.signature)

        override fun hashCode(): Int {
            var h = cert.hashCode()
            h = 31 * h + payload.contentHashCode()
            h = 31 * h + signature.contentHashCode()
            return h
        }
    }
}
