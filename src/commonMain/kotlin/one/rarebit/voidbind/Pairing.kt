package one.rarebit.voidbind

import one.rarebit.voidbind.crypto.Hex

/**
 * Short-Authentication-String (SAS) pairing with **commit-before-reveal**, as
 * pure transcript logic. The cryptographic hash is a seam ([HashFunction]) so
 * `commonMain` stays backend-free; the *structure* — commit, exchange, reveal,
 * verify, derive — is fixed here and must mirror voidbind-go's transcript.
 *
 * Flow (per side):
 * 1. Each side picks a random [nonce] and sends [commit] = H(`COMMIT_LABEL` ‖ role ‖ nonce).
 * 2. Both commitments are exchanged **before** either nonce is revealed — this is
 *    what stops a party from choosing its nonce to steer the final digits.
 * 3. Nonces are revealed; each side checks the peer's revealed nonce against the
 *    commitment it already holds via [verifyReveal].
 * 4. Both sides derive the SAS from the same [Transcript]; the humans compare the
 *    rendered [Sas.digits] out-of-band. Equal digits ⇒ no MITM.
 *
 * The transcript binds both participants' identity keys and both nonces in a
 * fixed order (initiator first), so both sides compute an identical SAS.
 */
object Pairing {

    /** Hash seam — supply SHA-256 (or the platform hash voidbind-go uses) at the edges. */
    fun interface HashFunction {
        fun hash(input: ByteArray): ByteArray
    }

    enum class Role(val tag: String) {
        INITIATOR("initiator"),
        RESPONDER("responder"),
    }

    /** Domain-separation labels. Must match voidbind-go's pairing transcript. */
    const val COMMIT_LABEL = "heyarr/pairing/v1/commit"
    const val SAS_LABEL = "heyarr/pairing/v1/sas"

    /** Compute the commitment a side broadcasts before revealing its nonce. */
    fun commit(role: Role, nonce: ByteArray, hash: HashFunction): ByteArray =
        hash.hash(frame(COMMIT_LABEL, listOf(role.tag.encodeToByteArray(), nonce)))

    /** Verify a peer's revealed [nonce] against the [commitment] it sent earlier. */
    fun verifyReveal(role: Role, nonce: ByteArray, commitment: ByteArray, hash: HashFunction): Boolean =
        commit(role, nonce, hash).contentEquals(commitment)

    /**
     * The bound pairing transcript. Field order is fixed (initiator side first)
     * so both participants derive the same SAS regardless of who computes it.
     */
    data class Transcript(
        val initiatorIdentity: KeyRef,
        val responderIdentity: KeyRef,
        val initiatorNonce: ByteArray,
        val responderNonce: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Transcript &&
                initiatorIdentity == other.initiatorIdentity &&
                responderIdentity == other.responderIdentity &&
                initiatorNonce.contentEquals(other.initiatorNonce) &&
                responderNonce.contentEquals(other.responderNonce)

        override fun hashCode(): Int {
            var h = initiatorIdentity.hashCode()
            h = 31 * h + responderIdentity.hashCode()
            h = 31 * h + initiatorNonce.contentHashCode()
            h = 31 * h + responderNonce.contentHashCode()
            return h
        }
    }

    /** A rendered short authentication string. */
    data class Sas(val digits: String)

    /**
     * Derive the SAS from a completed [transcript]. Deterministic: identical
     * transcripts always yield identical digits. [digitCount] decimal digits are
     * read from the transcript hash (default 6).
     */
    fun deriveSas(transcript: Transcript, hash: HashFunction, digitCount: Int = 6): Sas {
        require(digitCount in 1..18) { "digitCount out of range: $digitCount" }
        val h = hash.hash(
            frame(
                SAS_LABEL,
                listOf(
                    transcript.initiatorIdentity.render().encodeToByteArray(),
                    transcript.responderIdentity.render().encodeToByteArray(),
                    transcript.initiatorNonce,
                    transcript.responderNonce,
                ),
            )
        )
        // Fold the leading hash bytes into an unsigned integer, then take the low
        // `digitCount` decimal digits. Deterministic across platforms.
        var acc = 0L
        for (i in 0 until minOf(8, h.size)) {
            acc = (acc shl 8) or (h[i].toLong() and 0xFF)
        }
        acc = acc and Long.MAX_VALUE // keep positive
        var mod = 1L
        repeat(digitCount) { mod *= 10 }
        val value = acc % mod
        val s = value.toString()
        return Sas("0".repeat(digitCount - s.length) + s)
    }

    /**
     * Length-prefixed framing so concatenated fields are unambiguous (no field
     * can be shifted into another). `label` then each part, each prefixed with a
     * 4-byte big-endian length.
     */
    private fun frame(label: String, parts: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        fun put(b: ByteArray) {
            val n = b.size
            out.add((n ushr 24).toByte())
            out.add((n ushr 16).toByte())
            out.add((n ushr 8).toByte())
            out.add(n.toByte())
            for (x in b) out.add(x)
        }
        put(label.encodeToByteArray())
        for (p in parts) put(p)
        return out.toByteArray()
    }

    /** Convenience: hex-render a nonce/commitment for display or logs. */
    fun hex(bytes: ByteArray): String = Hex.encode(bytes)
}
