package one.rarebit.voidbind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic non-cryptographic hash for exercising the pairing transcript
 * logic. Real callers pass SHA-256; determinism is all these tests need.
 */
private val testHash = Pairing.HashFunction { input ->
    val out = ByteArray(32)
    var h = -0x61c8864680b583ebL
    for (b in input) {
        h = h xor (b.toLong() and 0xFF)
        h *= 0x100000001b3L
    }
    for (i in out.indices) {
        h = h xor (i.toLong() * 0x2545F4914F6CDD1DL)
        h *= 0x100000001b3L
        out[i] = (h ushr ((i % 8) * 8)).toByte()
    }
    out
}

class PairingTest {

    private val idA = KeyRef.ed25519(ByteArray(32) { (it + 1).toByte() })
    private val idB = KeyRef.ed25519(ByteArray(32) { (it * 5 + 2).toByte() })
    private val nonceA = ByteArray(16) { (it * 3 + 1).toByte() }
    private val nonceB = ByteArray(16) { (it * 7 + 9).toByte() }

    private fun transcript() = Pairing.Transcript(idA, idB, nonceA, nonceB)

    @Test
    fun sasDerivationIsDeterministic() {
        val sas1 = Pairing.deriveSas(transcript(), testHash)
        val sas2 = Pairing.deriveSas(transcript(), testHash)
        assertEquals(sas1, sas2)
        assertEquals(6, sas1.digits.length)
        assertTrue(sas1.digits.all { it in '0'..'9' }, "digits only: ${sas1.digits}")
    }

    @Test
    fun bothSidesDeriveTheSameSas() {
        // Two independent Transcript instances with identical content → same SAS.
        val a = Pairing.Transcript(idA, idB, nonceA.copyOf(), nonceB.copyOf())
        val b = Pairing.Transcript(idA, idB, nonceA.copyOf(), nonceB.copyOf())
        assertEquals(Pairing.deriveSas(a, testHash), Pairing.deriveSas(b, testHash))
    }

    @Test
    fun differentNoncesChangeTheSas() {
        val base = Pairing.deriveSas(transcript(), testHash)
        val tweaked = Pairing.Transcript(idA, idB, nonceA, ByteArray(16) { (it * 7 + 10).toByte() })
        assertFalse(base == Pairing.deriveSas(tweaked, testHash))
    }

    @Test
    fun commitBeforeRevealVerifies() {
        val commitA = Pairing.commit(Pairing.Role.INITIATOR, nonceA, testHash)
        // Peer later reveals nonceA; recompute against the held commitment.
        assertTrue(Pairing.verifyReveal(Pairing.Role.INITIATOR, nonceA, commitA, testHash))
        // A different nonce (or a role mismatch) must be rejected.
        assertFalse(Pairing.verifyReveal(Pairing.Role.INITIATOR, nonceB, commitA, testHash))
        assertFalse(Pairing.verifyReveal(Pairing.Role.RESPONDER, nonceA, commitA, testHash))
    }

    @Test
    fun digitCountIsConfigurable() {
        assertEquals(4, Pairing.deriveSas(transcript(), testHash, digitCount = 4).digits.length)
        assertEquals(8, Pairing.deriveSas(transcript(), testHash, digitCount = 8).digits.length)
    }
}
