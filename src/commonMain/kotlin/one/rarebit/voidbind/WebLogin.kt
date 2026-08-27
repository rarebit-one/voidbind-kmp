package one.rarebit.voidbind

import one.rarebit.voidbind.crypto.Base64Url

/**
 * The device side of Voidbind's **web QR-login** (WhatsApp-Web pattern), ported
 * byte-for-byte from voidbind-go's `weblogin` package. A relying party (All Thing,
 * a homelab web app) shows a QR `voidbind:login?rp=<base>&id=<id>`; the device
 * fetches the challenge, **signs it with its hardware device key**, and posts the
 * assertion; the RP verifies it offline against the pinned user key (via
 * voidbind-go/rp) and issues a short-lived session token.
 *
 * This object is the crypto half — the exact preimage the device signs and the
 * assertion it returns. The HTTP fetch/approve calls and the QR scanning live in
 * the app. Every hashed/signed byte matches voidbind-go, so an assertion produced
 * here is accepted by voidbind-go's `weblogin.Verify` unchanged.
 */
object WebLogin {

    /** Domain separation for the signed challenge preimage (matches voidbind-go). */
    const val ASSERTION_DOMAIN = "voidbind/weblogin/challenge/v1"

    /** Challenge nonce length — 256 bits of freshness (anti-replay). */
    const val NONCE_LEN = 32

    /**
     * A login challenge the RP issues and the device signs. [expiresAt] is unix
     * seconds. [issuedAt] is not part of the signed preimage (voidbind-go binds
     * id, nonce, audience, expiry) so it is omitted here.
     */
    class Challenge(
        val id: String,
        val nonce: ByteArray,
        val audience: String,
        val expiresAt: Long,
    )

    /**
     * A device's approval of a challenge: its enrolment [cert] token and the
     * base64url [sig]nature by its device key over the challenge preimage. This is
     * the JSON the device POSTs to the RP's approve endpoint (`{"cert":...,"sig":...}`).
     */
    data class Assertion(val cert: String, val sig: String)

    /**
     * The exact, domain-separated, 8-byte-big-endian length-framed preimage the
     * device signs and the RP verifies — matching voidbind-go's
     * `Challenge.signingBytes`. Framing every field means the signature binds
     * precisely (id, nonce, audience, expiry) and nothing else.
     */
    fun signingBytes(challenge: Challenge): ByteArray {
        val exp = ByteArray(8)
        var e = challenge.expiresAt
        for (i in 7 downTo 0) {
            exp[i] = (e and 0xFF).toByte()
            e = e ushr 8
        }
        return frame(
            listOf(
                ASSERTION_DOMAIN.encodeToByteArray(),
                challenge.id.encodeToByteArray(),
                challenge.nonce,
                challenge.audience.encodeToByteArray(),
                exp,
            ),
        )
    }

    /**
     * Sign [challenge] with the device key and return the assertion to POST. [sign]
     * is the device signer — in production `deviceKeyStore::sign` (hardware,
     * biometric-gated); in tests a software signer over a known seed. [certToken]
     * is the device's enrolment cert; the signing key must be the one that cert
     * names, or the RP's `weblogin.Verify` refuses the assertion.
     */
    fun signAssertion(
        challenge: Challenge,
        certToken: String,
        sign: (ByteArray) -> ByteArray,
    ): Assertion {
        require(certToken.isNotEmpty()) { "weblogin: a device enrolment cert is required" }
        val sig = sign(signingBytes(challenge))
        return Assertion(cert = certToken, sig = Base64Url.encode(sig))
    }

    /** 8-byte big-endian length-framed preimage, matching voidbind-go's `writeField`. */
    private fun frame(parts: List<ByteArray>): ByteArray {
        var size = 0
        for (p in parts) size += 8 + p.size
        val out = ByteArray(size)
        var i = 0
        for (p in parts) {
            val n = p.size.toLong()
            for (shift in 56 downTo 0 step 8) {
                out[i++] = (n ushr shift).toByte()
            }
            p.copyInto(out, i)
            i += p.size
        }
        return out
    }
}
