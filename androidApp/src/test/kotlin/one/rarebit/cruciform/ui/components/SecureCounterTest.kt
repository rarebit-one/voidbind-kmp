package one.rarebit.cruciform.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureCounterTest {

    /** Records the window's current protections, as the real window would hold them. */
    private class FakeWindow : SecureWindow {
        var secureOn = false
        var sensitiveOn = false

        override fun setSecure(secure: Boolean) {
            secureOn = secure
        }

        override fun setAccessibilityDataSensitive(sensitive: Boolean) {
            sensitiveOn = sensitive
        }
    }

    @Test
    fun `a secure screen turns on both protections and clears them when it leaves`() {
        val counter = SecureCounter()
        val window = FakeWindow()

        counter.acquire(window)
        assertTrue(window.secureOn)
        assertTrue(window.sensitiveOn)

        counter.release(window)
        assertFalse(window.secureOn)
        assertFalse(window.sensitiveOn)
    }

    @Test
    fun `secure to secure navigation keeps both on until the last screen leaves`() {
        val counter = SecureCounter()
        val window = FakeWindow()

        // Pair·Connect → Pair·Verify: the incoming screen acquires before the outgoing
        // one's dispose runs.
        counter.acquire(window)
        counter.acquire(window)
        counter.release(window)
        assertTrue(window.secureOn)
        assertTrue(window.sensitiveOn)
        assertEquals(1, counter.count)

        counter.release(window)
        assertFalse(window.secureOn)
        assertFalse(window.sensitiveOn)
    }

    @Test
    fun `an extra release never goes negative or re-clears a later screen`() {
        val counter = SecureCounter()
        val window = FakeWindow()

        counter.release(window)
        assertEquals(0, counter.count)

        counter.acquire(window)
        assertTrue(window.secureOn)
        assertTrue(window.sensitiveOn)
    }

    @Test
    fun `no window is tolerated`() {
        val counter = SecureCounter()
        counter.acquire(null)
        counter.release(null)
        assertEquals(0, counter.count)
    }
}
