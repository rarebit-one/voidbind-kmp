package one.rarebit.cruciform.platform

import one.rarebit.cruciform.platform.RelayConfig.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-JVM rules for the "Push plane" setting: the default and what the field accepts. */
class NotifyConfigTest {

    @Test
    fun `default is the LAN notify plane, and is itself valid`() {
        assertEquals("http://192.168.16.224:2587", NotifyConfig.DEFAULT_NOTIFY)
        assertEquals(
            Validation.Valid(NotifyConfig.DEFAULT_NOTIFY),
            NotifyConfig.validate(NotifyConfig.DEFAULT_NOTIFY),
        )
    }

    @Test
    fun `the default is on the one host cleartext is permitted for`() {
        // network_security_config.xml permits cleartext to 192.168.16.224 alone. A
        // default on any other host would have to be https or every registration
        // would be blocked by the platform, silently, as a cleartext refusal.
        assertTrue(NotifyConfig.DEFAULT_NOTIFY.startsWith("http://192.168.16.224:"))
    }

    @Test
    fun `accepts http and https bases with a host`() {
        assertEquals(Validation.Valid("https://notify.thesim.family"), NotifyConfig.validate("https://notify.thesim.family"))
        assertEquals(Validation.Valid("http://192.168.24.1:2587"), NotifyConfig.validate("http://192.168.24.1:2587"))
    }

    @Test
    fun `normalises whitespace and trailing slashes so the client's slash-v1 join cannot double up`() {
        assertEquals(Validation.Valid("http://192.168.16.224:2587"), NotifyConfig.validate("  http://192.168.16.224:2587/  "))
        assertEquals("https://notify.thesim.family", NotifyConfig.normalizeOrNull("https://notify.thesim.family///"))
    }

    @Test
    fun `rejects blank, non-http schemes, credentials and query fragments`() {
        assertIs<Validation.Invalid>(NotifyConfig.validate(""))
        assertIs<Validation.Invalid>(NotifyConfig.validate("ftp://notify.example"))
        assertIs<Validation.Invalid>(NotifyConfig.validate("http://user:pw@notify.example"))
        assertIs<Validation.Invalid>(NotifyConfig.validate("http://notify.example?a=1"))
        assertNull(NotifyConfig.normalizeOrNull("notify.example"))
    }

    @Test
    fun `its messages name the push plane, not the relay`() {
        val reason = (NotifyConfig.validate("") as Validation.Invalid).reason
        assertTrue(reason.contains("push plane"), "message should name this field: $reason")
    }
}
