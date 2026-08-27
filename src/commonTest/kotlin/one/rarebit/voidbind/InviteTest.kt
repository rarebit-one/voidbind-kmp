package one.rarebit.voidbind

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden vector CAPTURED FROM voidbind-go's `pairflow.EncodeInvite` with fixed
 * inputs (relay = "http://relay.local:8090", session = "sess123", salt = 0x33×32).
 * It proves the KMP invite encoder is byte-identical to the Go side — the wire a
 * device scans must render the same on both, or an invite produced by one is not
 * decodable-as-expected by the other.
 */
class InviteTest {

    private val relay = "http://relay.local:8090"
    private val session = "sess123"
    private val salt = ByteArray(32) { 0x33 }

    private val golden =
        "voidbind:pair?relay=http%3A%2F%2Frelay.local%3A8090" +
            "&salt=3333333333333333333333333333333333333333333333333333333333333333" +
            "&session=sess123&v=2"

    @Test
    fun encodeMatchesVoidbindGo() {
        assertEquals(golden, Invite.encode(relay, session, salt))
    }

    @Test
    fun decodeParsesTheGoldenInvite() {
        val p = Invite.decode(golden)
        assertEquals(relay, p.relay)
        assertEquals(session, p.session)
        assertContentEquals(salt, p.salt)
    }

    @Test
    fun roundTrips() {
        val p = Invite.decode(Invite.encode(relay, session, salt))
        assertEquals(relay, p.relay)
        assertEquals(session, p.session)
        assertContentEquals(salt, p.salt)
    }

    @Test
    fun decodeIsKeyOrderIndependent() {
        // Same fields, keys in a different order than Encode emits.
        val reordered = "voidbind:pair?v=2&session=sess123&relay=http%3A%2F%2Frelay.local%3A8090" +
            "&salt=3333333333333333333333333333333333333333333333333333333333333333"
        val p = Invite.decode(reordered)
        assertEquals(relay, p.relay)
        assertEquals(session, p.session)
        assertContentEquals(salt, p.salt)
    }

    @Test
    fun rejectsWrongSchemeVersionAndShortSalt() {
        assertFailsWith<IllegalArgumentException> {
            Invite.decode(golden.replaceFirst("voidbind:", "https:"))
        }
        assertFailsWith<IllegalArgumentException> {
            Invite.decode(golden.replaceFirst("v=2", "v=9"))
        }
        assertFailsWith<IllegalArgumentException> {
            Invite.decode("voidbind:pair?v=2&relay=http://r&session=s&salt=33")
        }
        assertFailsWith<IllegalArgumentException> {
            Invite.decode("https://example.com/not-an-invite")
        }
    }

    @Test
    fun encodeRejectsEmptyAndShortSalt() {
        assertFailsWith<IllegalArgumentException> { Invite.encode("", session, salt) }
        assertFailsWith<IllegalArgumentException> { Invite.encode(relay, "", salt) }
        assertFailsWith<IllegalArgumentException> { Invite.encode(relay, session, ByteArray(8)) }
    }

    @Test
    fun encodesTheMinimumSaltLength() {
        // A salt at exactly the floor is accepted and round-trips.
        val minSalt = ByteArray(Pairing.MIN_SALT_LEN) { 0x44 }
        val p = Invite.decode(Invite.encode(relay, session, minSalt))
        assertTrue(p.salt.size == Pairing.MIN_SALT_LEN)
        assertContentEquals(minSalt, p.salt)
    }
}
