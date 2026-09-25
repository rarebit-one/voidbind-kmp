package one.rarebit.cruciform.ui.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureCounterTest {

    /** Records the window's current protections, as the real window would hold them. */
    private class FakeWindow : SecureWindow {
        var secure = false
        var sensitive = false

        override fun setSecure(secure: Boolean) {
            this.secure = secure
        }

        override fun setAccessibilityDataSensitive(sensitive: Boolean) {
            this.sensitive = sensitive
        }
    }

    @Test
    fun `a secure screen turns on both protections and clears them when it leaves`() {
        val counter = SecureCounter()
        val window = FakeWindow()

        counter.acquire(window)
        assertTrue(window.secure)
        assertTrue(window.sensitive)

        counter.release(window)
        assertFalse(window.secure)
        assertFalse(window.sensitive)
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
        assertTrue(window.secure)
        assertTrue(window.sensitive)
        assertEquals(1, counter.count)

        counter.release(window)
        assertFalse(window.secure)
        assertFalse(window.sensitive)
    }

    @Test
    fun `an extra release never goes negative or re-clears a later screen`() {
        val counter = SecureCounter()
        val window = FakeWindow()

        counter.release(window)
        assertEquals(0, counter.count)

        counter.acquire(window)
        assertTrue(window.secure)
        assertTrue(window.sensitive)
    }

    @Test
    fun `no window is tolerated`() {
        val counter = SecureCounter()
        counter.acquire(null)
        counter.release(null)
        assertEquals(0, counter.count)
    }
}
