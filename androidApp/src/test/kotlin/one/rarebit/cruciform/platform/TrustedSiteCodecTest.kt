package one.rarebit.cruciform.platform

import one.rarebit.cruciform.domain.SiteAccent
import one.rarebit.cruciform.domain.TrustedSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The trusted-sites list survives a round trip, and the pre-v1 blob is dropped, not misread. */
class TrustedSiteCodecTest {
    private val heyarr = TrustedSite("heyarr.br.thesim.family", "heyarr.br.thesim.family", "", "just now", SiteAccent.BLUE)
    private val cove = TrustedSite("home.cove.lan", "home.cove.lan", "Cove Control", "yesterday", SiteAccent.PURPLE)

    @Test
    fun `round-trips every field, in order`() {
        val sites = listOf(heyarr, cove)
        assertEquals(sites, TrustedSiteCodec.decode(TrustedSiteCodec.encode(sites)))
    }

    @Test
    fun `fields are separated by the unit separator under a header line`() {
        val encoded = TrustedSiteCodec.encode(listOf(heyarr))
        val fs = TrustedSiteCodec.FIELD
        assertEquals(
            "${TrustedSiteCodec.HEADER}\nheyarr.br.thesim.family${fs}heyarr.br.thesim.family${fs}${fs}just now${fs}BLUE",
            encoded,
        )
    }

    @Test
    fun `empty and blank decode to no sites, and an empty list encodes to just the header`() {
        assertTrue(TrustedSiteCodec.decode(null).isEmpty())
        assertTrue(TrustedSiteCodec.decode("").isEmpty())
        assertTrue(TrustedSiteCodec.decode(TrustedSiteCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun `the app-v0_7_2 undelimited blob is dropped rather than read one character at a time`() {
        // What the empty-string joiner wrote: one row, no separators at all.
        val legacy = "heyarr.br.thesim.familyheyarr.br.thesim.familyjust nowBLUE"
        assertTrue(TrustedSiteCodec.decode(legacy).isEmpty())
    }

    @Test
    fun `a malformed row is skipped and an unknown accent falls back to blue`() {
        val fs = TrustedSiteCodec.FIELD
        val raw = "${TrustedSiteCodec.HEADER}\n" +
            "short${fs}row\n" +
            "${fs}no-id${fs}${fs}now${fs}BLUE\n" +
            "id${fs}host.example${fs}App${fs}now${fs}CHARTREUSE"
        assertEquals(listOf(TrustedSite("id", "host.example", "App", "now", SiteAccent.BLUE)), TrustedSiteCodec.decode(raw))
    }
}
