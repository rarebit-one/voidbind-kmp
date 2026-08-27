package one.rarebit.voidbind.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhonelinkRing
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.rarebit.voidbind.app.ui.components.IconCircle
import one.rarebit.voidbind.app.ui.components.ScreenPadding
import one.rarebit.voidbind.app.ui.components.StatusPill
import one.rarebit.voidbind.app.ui.components.VSpace
import one.rarebit.voidbind.app.ui.components.VbCard
import one.rarebit.voidbind.app.ui.components.VoidbindMark
import one.rarebit.voidbind.app.ui.theme.VbColors

/**
 * Welcome / onboarding: "Your identity, under your control." The three sovereign
 * entry points — create, restore, add-this-device — with the hardware-key promise
 * pinned at the bottom. (Mockup 1.)
 */
@Composable
fun OnboardingScreen(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding)
            .padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VoidbindMark(size = 76.dp)
        VSpace(12)
        Text("Voidbind", style = MaterialTheme.typography.headlineMedium, color = VbColors.TextPrimary)
        VSpace(28)
        Text(
            "Your identity,\nunder your control.",
            style = MaterialTheme.typography.headlineLarge,
            color = VbColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        VSpace(16)
        Text(
            "No account. No authority.\nYour cryptographic keys are your identity.",
            style = MaterialTheme.typography.bodyLarge,
            color = VbColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        VSpace(36)

        OnboardingAction(
            icon = Icons.Rounded.VpnKey,
            accent = VbColors.Blue,
            title = "Create a new identity",
            subtitle = "Generate a new identity and recovery secret",
            onClick = onCreate,
        )
        VSpace(14)
        OnboardingAction(
            icon = Icons.Rounded.History,
            accent = VbColors.Mint,
            title = "Restore from a recovery secret",
            subtitle = "Recover your identity on this device",
            onClick = onRestore,
        )
        VSpace(14)
        OnboardingAction(
            icon = Icons.Rounded.PhonelinkRing,
            accent = VbColors.Blue,
            title = "Add this device",
            subtitle = "Pair with a device you already trust",
            onClick = onAddDevice,
        )

        Spacer(Modifier.weight(1f))
        VSpace(28)
        StatusPill(
            text = "Keys protected by Android secure hardware",
            accent = VbColors.Mint,
            leadingIcon = Icons.Rounded.Shield,
        )
    }
}

@Composable
private fun OnboardingAction(
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    VbCard(modifier = Modifier.fillMaxWidth(), color = VbColors.Surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(icon = icon, tint = accent, background = accent.copy(alpha = 0.14f), diameter = 52, iconSize = 26)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = VbColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VbColors.TextMuted)
        }
    }
}
