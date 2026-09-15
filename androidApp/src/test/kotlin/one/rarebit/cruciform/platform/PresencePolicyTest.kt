package one.rarebit.cruciform.platform

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the security boundary of the two presence checks. These are pure integer flag
 * sets, so they are checkable without a device — and pinning them here means a later
 * edit that quietly re-admits the screen-lock credential to a destructive act (the
 * exact hole this closes) fails the build instead of shipping.
 */
class PresencePolicyTest {

    @Test
    fun `a destructive act accepts a strong biometric and nothing weaker`() {
        // Strong biometric is required…
        assertEquals(BIOMETRIC_STRONG, PresencePolicy.DESTRUCTIVE and BIOMETRIC_STRONG)
        // …and NO device-credential (PIN / pattern / password) fallback: possession of
        // the screen-lock secret must not be enough to remove or admit a device.
        assertEquals(0, PresencePolicy.DESTRUCTIVE and DEVICE_CREDENTIAL)
        // …and not the weak (class-2) biometric class either.
        assertEquals(0, PresencePolicy.DESTRUCTIVE and BIOMETRIC_WEAK)
        assertEquals(BIOMETRIC_STRONG, PresencePolicy.DESTRUCTIVE)
    }

    @Test
    fun `the low-stakes check keeps its credential fallback`() {
        assertTrue(PresencePolicy.ANY and BIOMETRIC_STRONG != 0, "strong biometric still accepted")
        assertTrue(PresencePolicy.ANY and DEVICE_CREDENTIAL != 0, "credential fallback kept for usability")
    }

    @Test
    fun `the destructive check is strictly stronger than the low-stakes one`() {
        assertNotEquals(PresencePolicy.ANY, PresencePolicy.DESTRUCTIVE)
        // Destructive is a subset of ANY (it only ever removes factors, never adds).
        assertEquals(PresencePolicy.DESTRUCTIVE, PresencePolicy.DESTRUCTIVE and PresencePolicy.ANY)
    }
}
