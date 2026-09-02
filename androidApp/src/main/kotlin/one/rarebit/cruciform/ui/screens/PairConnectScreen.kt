package one.rarebit.cruciform.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import one.rarebit.cruciform.domain.PairInviteDisplay
import one.rarebit.cruciform.handoff.RpPairTarget
import one.rarebit.cruciform.ui.components.AppTopBar
import one.rarebit.cruciform.ui.components.HSpace
import one.rarebit.cruciform.ui.components.OutlineButton
import one.rarebit.cruciform.ui.components.PrimaryButton
import one.rarebit.cruciform.ui.components.QrImage
import one.rarebit.cruciform.ui.components.ScreenPadding
import one.rarebit.cruciform.ui.components.SecureScreen
import one.rarebit.cruciform.ui.components.VSpace
import one.rarebit.cruciform.ui.components.VbCard
import one.rarebit.cruciform.ui.theme.VbColors
import one.rarebit.cruciform.ui.theme.VbType

/**
 * Pair a device, step 1 CONNECT (Mockup 5): show the one-time encrypted invite.
 *
 * [sameDeviceTargets] are the relying-party apps installed on THIS phone that take a
 * pairing invite by deep link (ADR-0006) — one "Send to <app> on this phone" button
 * each, and none when the list is empty. [onShare] is the Sharesheet fallback for an
 * app we do not know. The rest of the flow is unchanged: once the new device joins,
 * this screen advances to VERIFY and the SAS is confirmed HERE, biometric-gated.
 */
@Composable
fun PairConnectScreen(
    invite: PairInviteDisplay,
    onBack: () -> Unit,
    onScanInstead: () -> Unit,
    modifier: Modifier = Modifier,
    sameDeviceTargets: List<RpPairTarget> = emptyList(),
    onSendTo: (RpPairTarget) -> Unit = {},
    onShare: () -> Unit = {},
    /**
     * Seconds left on the invite's relay session, supplied by the owner of the invite
     * (the app-scoped coordinator, ADR-0007) so it is the SAME clock whether this screen
     * is entered fresh, recomposed, or returned to from the relying-party app. Null →
     * count down locally from [PairInviteDisplay.expiresInSeconds] (previews).
     */
    remainingSeconds: Int? = null,
    /** A one-line status under the timer, e.g. "Waiting for the new device to join…". */
    status: String? = null,
) {
    SecureScreen()
    var localRemaining by remember { mutableIntStateOf(invite.expiresInSeconds) }
    LaunchedEffect(remainingSeconds == null) {
        if (remainingSeconds == null) while (localRemaining > 0) { delay(1000); localRemaining -= 1 }
    }
    val remaining = remainingSeconds ?: localRemaining

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar(title = "Pair a device", onBack = onBack)
        Column(Modifier.padding(ScreenPadding).padding(bottom = 24.dp)) {
            Text("1 of 2 · CONNECT", style = VbType.SectionLabel, color = VbColors.Mint)
            VSpace(10)
            Text("Add another trusted device", style = MaterialTheme.typography.headlineMedium, color = VbColors.TextPrimary)
            VSpace(8)
            Text(
                "On your new device, choose Add this device, then scan this invite.",
                style = MaterialTheme.typography.bodyLarge,
                color = VbColors.TextSecondary,
            )

            VSpace(20)
            VbCard(modifier = Modifier.fillMaxWidth(), color = VbColors.Surface) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    QrImage(content = invite.qrPayload, modifier = Modifier.size(240.dp))
                    VSpace(14)
                    Text(invite.inviteId, style = VbType.Mono, color = VbColors.TextPrimary)
                    VSpace(8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = VbColors.TextMuted, modifier = Modifier.size(16.dp))
                        HSpace(6)
                        Text("Expires in ${format(remaining)}", style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary)
                    }
                    if (status != null) {
                        VSpace(6)
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = VbColors.TextMuted, textAlign = TextAlign.Center)
                    }
                }
            }

            VSpace(14)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(18.dp))
                HSpace(8)
                Text("The invite is encrypted and can be used once.", style = MaterialTheme.typography.bodyMedium, color = VbColors.Mint)
            }

            if (sameDeviceTargets.isNotEmpty()) {
                VSpace(18)
                Text("ON THIS PHONE", style = VbType.SectionLabel, color = VbColors.TextMuted)
                VSpace(8)
                Text(
                    "An app on this phone can't scan this screen — send it the invite instead. " +
                        "It will show a security code; come back here to compare and approve.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VbColors.TextSecondary,
                )
                VSpace(10)
                sameDeviceTargets.forEach { target ->
                    PrimaryButton(
                        "Send to ${target.appName} on this phone",
                        onClick = { onSendTo(target) },
                        leadingIcon = Icons.Rounded.Smartphone,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VSpace(8)
                }
            }

            VSpace(8)
            OutlineButton(
                "Share invite…",
                onClick = onShare,
                accent = VbColors.TextSecondary,
                leadingIcon = Icons.Rounded.Share,
                modifier = Modifier.fillMaxWidth(),
            )

            VSpace(10)
            OutlineButton(
                "Scan an invite instead",
                onClick = onScanInstead,
                accent = VbColors.Mint,
                leadingIcon = Icons.Rounded.QrCodeScanner,
                modifier = Modifier.fillMaxWidth(),
            )

            VSpace(18)
            VbCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("What happens next?", style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary)
                    VSpace(12)
                    NextStep(Icons.Rounded.Tag, "Compare a 7-digit security code")
                    VSpace(12)
                    NextStep(Icons.Rounded.Fingerprint, "Approve with biometrics on both devices")
                }
            }

            VSpace(16)
            Text(
                "Screenshots disabled",
                style = MaterialTheme.typography.bodyMedium,
                color = VbColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NextStep(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(22.dp))
        }
        HSpace(12)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = VbColors.TextSecondary)
    }
}

private fun format(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
