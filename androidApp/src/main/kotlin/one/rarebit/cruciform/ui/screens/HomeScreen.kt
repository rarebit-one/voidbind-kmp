package one.rarebit.cruciform.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.rarebit.cruciform.domain.DeviceInfo
import one.rarebit.cruciform.domain.HardwareBacking
import one.rarebit.cruciform.domain.Identity
import one.rarebit.cruciform.domain.TrustedSite
import one.rarebit.cruciform.ui.components.HSpace
import one.rarebit.cruciform.ui.components.IdentityFingerprint
import one.rarebit.cruciform.ui.components.RowItem
import one.rarebit.cruciform.ui.components.ScreenPadding
import one.rarebit.cruciform.ui.components.SectionLabel
import one.rarebit.cruciform.ui.components.SiteAvatar
import one.rarebit.cruciform.ui.components.StatusPill
import one.rarebit.cruciform.ui.components.VSpace
import one.rarebit.cruciform.ui.components.VbCard
import one.rarebit.cruciform.ui.components.VbHairline
import one.rarebit.cruciform.ui.components.CruciformMark
import one.rarebit.cruciform.ui.components.WashCard
import one.rarebit.cruciform.ui.theme.VbColors
import one.rarebit.cruciform.ui.theme.VbType

/**
 * Home / identity dashboard: the identity fingerprint, the StrongBox status card,
 * this device, and the trusted sites. (Mockup 2.)
 */
@Composable
fun HomeScreen(
    identity: Identity,
    device: DeviceInfo,
    trustedSites: List<TrustedSite>,
    onSettings: () -> Unit,
    onCopyIdentity: () -> Unit,
    onDevice: () -> Unit,
    onSite: (TrustedSite) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding)
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            CruciformMark(size = 28.dp)
            HSpace(10)
            Text("Cruciform", style = MaterialTheme.typography.titleLarge, color = VbColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = VbColors.TextSecondary)
            }
        }

        VSpace(18)
        SectionLabel("Your identity")
        VSpace(10)
        IdentityFingerprint(identity.label, identity.fingerprint, onCopy = onCopyIdentity)
        VSpace(14)
        if (identity.offlineVerifiable) {
            StatusPill("Offline-verifiable", accent = VbColors.Mint, leadingIcon = Icons.Rounded.Fingerprint)
        }

        VSpace(18)
        StrongBoxCard(device)

        VSpace(26)
        SectionLabel("This device")
        VSpace(10)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDevice)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(VbColors.Blue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = VbColors.Blue, modifier = Modifier.size(26.dp))
                }
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary)
                    Text(device.label, style = VbType.Mono, color = VbColors.TextSecondary)
                    VSpace(6)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(16.dp))
                        HSpace(6)
                        Text(
                            if (device.biometricRequired) "Biometric required" else "No biometric gate",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VbColors.TextSecondary,
                        )
                    }
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VbColors.TextMuted)
            }
        }

        VSpace(26)
        SectionLabel("Trusted sites", trailing = {
            Text("${trustedSites.size}", style = MaterialTheme.typography.labelMedium, color = VbColors.TextMuted)
        })
        VSpace(10)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                trustedSites.forEachIndexed { i, site ->
                    RowItem(
                        title = site.domain,
                        subtitle = "${site.appName} · ${site.lastUsed}",
                        onClick = { onSite(site) },
                        leading = { SiteAvatar(site.accent) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VbColors.TextMuted) },
                    )
                    if (i < trustedSites.lastIndex) VbHairline(Modifier.padding(start = 74.dp))
                }
            }
        }
    }
}

@Composable
private fun StrongBoxCard(device: DeviceInfo) {
    val label = when (device.backing) {
        HardwareBacking.STRONGBOX -> "Hardware-backed (StrongBox)"
        HardwareBacking.TEE -> "Hardware-backed (TEE)"
        HardwareBacking.SOFTWARE -> "Software key (no secure element)"
    }
    val accent = if (device.hardwareBacked) VbColors.Mint else VbColors.Amber
    WashCard(accent = accent, wash = VbColors.MintWash, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Memory, contentDescription = null, tint = accent, modifier = Modifier.size(44.dp))
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    if (device.biometricRequired) "Signing key requires biometrics" else "Signing key on this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VbColors.TextSecondary,
                )
                VSpace(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(accent)
                    HSpace(6)
                    Text(
                        if (device.hardwareBacked) "Protected" else "Not hardware-backed",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color, size: Int = 8) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}
