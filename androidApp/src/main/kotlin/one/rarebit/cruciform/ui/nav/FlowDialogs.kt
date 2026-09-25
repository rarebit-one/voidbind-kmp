package one.rarebit.cruciform.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import one.rarebit.cruciform.domain.EngineFailure
import one.rarebit.cruciform.ui.flow.EngineErrorState
import one.rarebit.cruciform.ui.flow.LoginErrorState
import one.rarebit.cruciform.ui.theme.VbColors

/**
 * A login-fetch or approval failure (couldn't reach / site refused / stale code) — a
 * dismissible dialog, not a crash. A stale sign-in code (404/410) is titled "Expired"
 * and reads as "scan a fresh QR"; everything else keeps "Sign-in unavailable".
 */
@Composable
internal fun LoginErrorDialog(error: LoginErrorState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(if (error.expired) "Expired" else "Sign-in unavailable") },
        text = { Text(error.message) },
    )
}

/**
 * An engine-step failure: titled by kind, with a Retry that re-runs the same step when
 * that can help (unreachable relay → once Wi-Fi/VPN is back; expired invite → a fresh
 * one). [onClosed] runs after the dialog closes without Retry (the caller routes a
 * deep-link handoff back to its app). A failure against this phone's own relay offers
 * "Change relay" → [onChangeRelay].
 */
@Composable
internal fun EngineErrorDialog(error: EngineErrorState, onClosed: () -> Unit, onChangeRelay: () -> Unit) {
    val dismiss = {
        error.onDismiss()
        onClosed()
    }
    val retry = error.retry
    val relayUrl = error.relayUrl
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = {
            if (retry != null) {
                TextButton(onClick = retry) { Text("Retry") }
            } else {
                TextButton(onClick = dismiss) { Text("OK") }
            }
        },
        dismissButton = when {
            relayUrl != null -> (
                {
                    Row {
                        TextButton(onClick = {
                            error.onDismiss()
                            onChangeRelay()
                        }) { Text("Change relay") }
                        if (retry != null) TextButton(onClick = dismiss) { Text("Cancel") }
                    }
                }
                )

            retry != null -> ({ TextButton(onClick = dismiss) { Text("Cancel") } })

            else -> null
        },
        // A blank relayUrl is a build with no default relay and nothing in Settings:
        // the message already says to add one, so don't print an empty URL under it.
        title = { Text(if (relayUrl?.isBlank() == true) "No pairing relay" else titleFor(error.failure.kind)) },
        text = {
            Text(
                if (!relayUrl.isNullOrBlank()) {
                    "${error.failure.message}\n\nPairing relay: $relayUrl"
                } else {
                    error.failure.message
                },
            )
        },
    )
}

/** Dialog title per failure kind — the message itself already says what to do. */
private fun titleFor(kind: EngineFailure.Kind): String = when (kind) {
    EngineFailure.Kind.UNREACHABLE -> "Can't reach the relay"
    EngineFailure.Kind.TIMEOUT -> "Pairing timed out"
    EngineFailure.Kind.REJECTED -> "Pairing refused"
    EngineFailure.Kind.PROTOCOL -> "Pairing didn't verify"
    EngineFailure.Kind.CANCELLED -> "Cancelled"
    EngineFailure.Kind.EXPIRED -> "Expired"
    EngineFailure.Kind.INTERNAL -> "Something went wrong"
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = VbColors.Mint)
    }
}
