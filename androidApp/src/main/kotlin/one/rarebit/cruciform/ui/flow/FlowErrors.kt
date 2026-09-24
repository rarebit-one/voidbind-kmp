package one.rarebit.cruciform.ui.flow

import one.rarebit.cruciform.domain.EngineFailure

/**
 * A dismissible login error to show as a dialog. [expired] flags a stale sign-in code
 * (a 404/410 on the challenge fetch) so the dialog can title it "Expired" and say to
 * scan a fresh QR, rather than the generic "Sign-in unavailable" used for an
 * unreachable or refusing RP.
 */
data class LoginErrorState(val message: String, val expired: Boolean = false) {
    companion object {
        fun of(f: EngineFailure) = LoginErrorState(f.message, expired = f.kind == EngineFailure.Kind.EXPIRED)
    }
}

/**
 * An engine failure to show as a dismissible dialog. [retry], when present, re-runs
 * the SAME step (re-join the same invite, re-mint the invite, re-confirm) and clears
 * the dialog itself. [relayUrl] is set when the failure is against THIS phone's
 * configured pairing relay (minting an invite — not joining someone else's, whose relay
 * is in the invite): the dialog then names that URL and offers "Change relay" →
 * Settings. [onDismiss] clears the dialog when it closes without Retry (OK / Cancel /
 * Change relay).
 *
 * Holds callbacks, so it is never put in a SavedStateHandle: a dialog that was up when
 * the process died is simply gone, and the flow's persisted state decides what to show.
 */
data class EngineErrorState(
    val failure: EngineFailure,
    val retry: (() -> Unit)? = null,
    val relayUrl: String? = null,
    val onDismiss: () -> Unit,
)
