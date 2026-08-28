package one.rarebit.voidbind.app.ui.screens

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
import androidx.compose.material.icons.rounded.Shield
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
import one.rarebit.voidbind.app.domain.PairInviteDisplay
import one.rarebit.voidbind.app.ui.components.AppTopBar
import one.rarebit.voidbind.app.ui.components.HSpace
import one.rarebit.voidbind.app.ui.components.OutlineButton
import one.rarebit.voidbind.app.ui.components.QrImage
import one.rarebit.voidbind.app.ui.components.ScreenPadding
import one.rarebit.voidbind.app.ui.components.SecureScreen
import one.rarebit.voidbind.app.ui.components.VSpace
import one.rarebit.voidbind.app.ui.components.VbCard
import one.rarebit.voidbind.app.ui.theme.VbColors
import one.rarebit.voidbind.app.ui.theme.VbType

/** Pair a device, step 1 CONNECT (Mockup 5): show the one-time encrypted invite. */
@Composable
fun PairConnectScreen(
    invite: PairInviteDisplay,
    onBack: () -> Unit,
    onScanInstead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureScreen()
    var remaining by remember { mutableIntStateOf(invite.expiresInSeconds) }
    LaunchedEffect(Unit) { while (remaining > 0) { delay(1000); remaining -= 1 } }

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
                }
            }

            VSpace(14)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(18.dp))
                HSpace(8)
                Text("The invite is encrypted and can be used once.", style = MaterialTheme.typography.bodyMedium, color = VbColors.Mint)
            }

            VSpace(16)
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
