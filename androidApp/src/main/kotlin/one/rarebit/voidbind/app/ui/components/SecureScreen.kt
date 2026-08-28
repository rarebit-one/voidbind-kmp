package one.rarebit.voidbind.app.ui.components

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Marks the current screen as secure: sets `FLAG_SECURE` while composed, so the OS
 * blocks screenshots and keeps the content out of the recents thumbnail. Used on the
 * recovery-secret and pairing screens, which display material that must never be
 * captured.
 *
 * `FLAG_SECURE` is a single window-level flag, but the flag is **reference-counted**
 * here so it composes correctly across a secure → secure navigation. Navigating
 * directly between two secure screens (e.g. Pair·Connect → Pair·Verify) interleaves
 * the incoming screen's set-up with the outgoing screen's `onDispose`; a naive
 * per-screen set/clear lets the outgoing clear run last and strip the flag off the
 * incoming secure screen for its whole lifetime. Counting acquisitions means the flag
 * is set while *any* secure screen is composed and cleared only when the last one
 * leaves.
 */
@Composable
fun SecureScreen() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        SecureFlag.acquire(window)
        onDispose { SecureFlag.release(window) }
    }
}

/**
 * Reference counter for `FLAG_SECURE`. Confined to the main thread (Compose effects
 * run there), so a plain `Int` is safe. `acquire` always (re)asserts the flag —
 * idempotent — and `release` clears it only when the count returns to zero.
 */
private object SecureFlag {
    private var count = 0

    fun acquire(window: Window?) {
        count += 1
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window?) {
        count = (count - 1).coerceAtLeast(0)
        if (count == 0) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
