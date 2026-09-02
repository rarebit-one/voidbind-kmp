package one.rarebit.cruciform.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TabletMac
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.rarebit.cruciform.domain.PairSession
import one.rarebit.cruciform.ui.components.AppTopBar
import one.rarebit.cruciform.ui.components.DangerButton
import one.rarebit.cruciform.ui.components.HSpace
import one.rarebit.cruciform.ui.components.PrimaryButton
import one.rarebit.cruciform.ui.components.ScreenPadding
import one.rarebit.cruciform.ui.components.SecureScreen
import one.rarebit.cruciform.ui.components.VSpace
import one.rarebit.cruciform.ui.components.WashCard
import one.rarebit.cruciform.ui.theme.VbColors
import one.rarebit.cruciform.ui.theme.VbType

/** Pair a device, step 2 VERIFY (Mockup 6): compare the 7-digit SAS out of band. */
@Composable
fun PairVerifyScreen(
    session: PairSession,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SecureScreen()
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppTopBar(title = "Pair a device", onBack = onCancel)
        Column(Modifier.padding(ScreenPadding).padding(bottom = 24.dp)) {
            Text("2 of 2 · VERIFY", style = VbType.SectionLabel, color = VbColors.Mint)
            VSpace(20)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                DeviceGlyph(Icons.Rounded.Smartphone, session.thisDeviceName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dots()
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(VbColors.SurfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Lock, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(18.dp)) }
                    Dots()
                }
                DeviceGlyph(Icons.Rounded.TabletMac, session.peerDeviceName)
            }

            VSpace(28)
            Text("Compare security codes", style = MaterialTheme.typography.headlineMedium, color = VbColors.TextPrimary)
            VSpace(8)
            Text("Does this match the code on your other device?", style = MaterialTheme.typography.bodyLarge, color = VbColors.TextSecondary)

            VSpace(20)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(VbColors.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(session.securityCode, style = VbType.SecurityCode, color = VbColors.Mint)
                    VSpace(10)
                    Text("Read every digit aloud or compare side by side.", style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary)
                }
            }

            VSpace(20)
            WashCard(accent = VbColors.Coral, wash = VbColors.CoralDim.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = VbColors.Coral, modifier = Modifier.size(28.dp))
                    HSpace(12)
                    Text(
                        "If the codes differ, someone may be intercepting the pairing. Choose No and start again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VbColors.TextSecondary,
                    )
                }
            }

            VSpace(24)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DangerButton("No, cancel", onClick = onCancel, leadingIcon = Icons.Rounded.Close, modifier = Modifier.weight(1f))
                PrimaryButton("Yes, they match", onClick = onConfirm, leadingIcon = Icons.Rounded.Check, fill = VbColors.Mint, modifier = Modifier.weight(1f))
            }
            VSpace(12)
            Text(
                "Biometric confirmation required on both devices",
                style = MaterialTheme.typography.bodyMedium,
                color = VbColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DeviceGlyph(icon: ImageVector, name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 120.dp, height = 120.dp)) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(VbColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(34.dp)) }
        VSpace(8)
        Text(name, style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Dots() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(Modifier.padding(horizontal = 2.dp).size(4.dp).clip(CircleShape).background(VbColors.Mint))
        }
    }
}
