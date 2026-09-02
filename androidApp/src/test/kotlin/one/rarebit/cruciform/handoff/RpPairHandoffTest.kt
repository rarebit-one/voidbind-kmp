package one.rarebit.cruciform.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reverse handoff URI (ADR-0006): an RP's own pair-callback scheme carrying the
 * byte-identical invite as one percent-encoded query value. Pure JVM.
 */
class RpPairHandoffTest {

    private val invite = "voidbind:pair?relay=http%3A%2F%2F192.168.16.224%3A8788&salt=" +
        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff&session=sess1" +
        "&usr=ed25519%3Af947b10c8089aa8fed2d435fae069d0ca1513b33691955ae963dfe8bc5b398c4&v=3"
    private val heyarr = RpPairHandoff.KNOWN.first { it.appName == "heyarr" }

    @Test
    fun uriIsTheRpSchemePlusOneEncodedInviteValue() {
        val uri = RpPairHandoff.uriFor(heyarr, invite)
        assertTrue(uri, uri.startsWith("heyarr-mobile://pair?invite="))
        val value = uri.substringAfter("invite=")
        // The tuple's own delimiters are all encoded, so the RP sees a single value.
        assertTrue(value, '?' !in value && '&' !in value && '=' !in value && ':' !in value)
        assertEquals(invite, percentDecode(value))
    }

    @Test
    fun encodingIsRfc3986UppercaseAndKeepsTheUnreservedSet() {
        assertEquals("a-b_c.d~e", RpPairHandoff.percentEncode("a-b_c.d~e"))
        assertEquals("%3A%2F%3F%26%3D%25%20%2B", RpPairHandoff.percentEncode(":/?&=% +"))
        assertEquals("%C3%A9", RpPairHandoff.percentEncode("é"))
    }

    @Test
    fun knownTargetsAreNeverTheVoidbindScheme() {
        assertTrue(RpPairHandoff.KNOWN.isNotEmpty())
        RpPairHandoff.KNOWN.forEach { t ->
            assertTrue(t.callbackBase, t.scheme != "voidbind")
            assertTrue(t.callbackBase, t.callbackBase.endsWith("://pair"))
        }
        assertEquals("allthing", RpPairHandoff.KNOWN.first { it.appName == "All Thing" }.scheme)
    }

    @Test
    fun aLoginTupleIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            RpPairHandoff.uriFor(heyarr, "voidbind:login?id=x&rp=http%3A%2F%2Fh")
        }
    }

    private fun percentDecode(s: String): String {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') { out.add(s.substring(i + 1, i + 3).toInt(16).toByte()); i += 3 } else { out.add(s[i].code.toByte()); i++ }
        }
        return out.toByteArray().decodeToString()
    }
}
