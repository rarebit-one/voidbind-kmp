package one.rarebit.voidbind.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.rarebit.voidbind.app.domain.IdentityState
import one.rarebit.voidbind.app.domain.TrustedSite
import one.rarebit.voidbind.policy.ApprovalPolicy
import one.rarebit.voidbind.app.ui.components.HSpace
import one.rarebit.voidbind.app.ui.components.IconCircle
import one.rarebit.voidbind.app.ui.components.OutlineButton
import one.rarebit.voidbind.app.ui.components.RowItem
import one.rarebit.voidbind.app.ui.components.ScreenPadding
import one.rarebit.voidbind.app.ui.components.SectionLabel
import one.rarebit.voidbind.app.ui.components.SiteAvatar
import one.rarebit.voidbind.app.ui.components.StatusPill
import one.rarebit.voidbind.app.ui.components.VSpace
import one.rarebit.voidbind.app.ui.components.VbCard
import one.rarebit.voidbind.app.ui.components.VbHairline
import one.rarebit.voidbind.app.ui.components.VoidbindMark
import one.rarebit.voidbind.app.ui.theme.VbColors
import one.rarebit.voidbind.app.ui.theme.VbType

/** Settings (Mockup 8): identity header, device, trusted sites, recovery, about. */
@Composable
fun SettingsScreen(
    state: IdentityState.Active,
    onRename: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onRevoke: (TrustedSite) -> Unit,
    onManageSites: () -> Unit,
    onRecoveryBackup: () -> Unit,
    onApprovalActivity: () -> Unit,
    onAbout: () -> Unit,
    onSecurity: () -> Unit,
    onLicenses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ScreenPadding)
            .padding(top = 20.dp, bottom = 28.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, color = VbColors.TextPrimary)

        VSpace(20)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                VoidbindMark(size = 40.dp)
                HSpace(14)
                Column(Modifier.weight(1f)) {
                    Text("${state.identity.version} · ${state.identity.fingerprint}", style = VbType.Mono, color = VbColors.TextPrimary)
                    VSpace(8)
                    StatusPill("Hardware-backed", accent = VbColors.Mint, leadingIcon = Icons.Rounded.Shield)
                }
            }
        }

        VSpace(24)
        SectionLabel("Device")
        VSpace(10)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                RowItem(
                    title = "Device name",
                    subtitle = state.device.name,
                    onClick = onRename,
                    leading = { IconCircle(Icons.Rounded.Fingerprint, tint = VbColors.Blue, background = VbColors.Blue.copy(alpha = 0.12f)) },
                    trailing = {
                        IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, contentDescription = "Rename", tint = VbColors.TextSecondary) }
                    },
                )
                VbHairline(Modifier.padding(horizontal = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconCircle(Icons.Rounded.Fingerprint, tint = VbColors.Mint, background = VbColors.Mint.copy(alpha = 0.12f))
                    HSpace(14)
                    Column(Modifier.weight(1f)) {
                        Text("Biometric approval", style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary)
                        Text("Required for every signature", style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary)
                    }
                    Switch(
                        checked = state.biometricApproval,
                        onCheckedChange = onToggleBiometric,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VbColors.OnMint,
                            checkedTrackColor = VbColors.Mint,
                            uncheckedTrackColor = VbColors.SurfaceElevated,
                        ),
                    )
                    HSpace(8)
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = VbColors.TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }

        VSpace(24)
        SectionLabel("Trusted sites")
        VSpace(10)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                state.trustedSites.take(2).forEachIndexed { i, site ->
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SiteAvatar(site.accent, diameter = 40)
                        HSpace(14)
                        Column(Modifier.weight(1f)) {
                            Text(site.domain, style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary)
                            Text("${site.appName} · ${site.lastUsed}", style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary)
                            VSpace(6)
                            PolicyPill(site)
                        }
                        OutlineButton("Revoke", onClick = { onRevoke(site) }, accent = VbColors.Coral)
                    }
                    if (i == 0) VbHairline(Modifier.padding(horizontal = 16.dp))
                }
                VbHairline(Modifier.padding(horizontal = 16.dp))
                RowItem(
                    title = "Approval activity",
                    subtitle = "Who you approved, and when",
                    onClick = onApprovalActivity,
                    leading = { IconCircle(Icons.Rounded.History, tint = VbColors.Blue, background = VbColors.Blue.copy(alpha = 0.12f)) },
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VbColors.TextMuted) },
                )
                VbHairline(Modifier.padding(horizontal = 16.dp))
                RowItem(
                    title = "Manage all ${state.trustedSites.size} trusted sites",
                    titleColor = VbColors.Mint,
                    onClick = onManageSites,
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VbColors.Mint) },
                )
            }
        }

        VSpace(24)
        SectionLabel("Recovery", color = VbColors.Amber)
        VSpace(10)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            RowItem(
                title = "Recovery backup",
                subtitle = "Re-show your recovery secret",
                onClick = onRecoveryBackup,
                leading = { IconCircle(Icons.Rounded.VpnKey, tint = VbColors.Amber, background = VbColors.Amber.copy(alpha = 0.12f)) },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("Biometric required", accent = VbColors.Amber, leadingIcon = Icons.Rounded.Lock)
                        HSpace(8)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VbColors.Amber)
                    }
                },
            )
        }

        VSpace(24)
        SectionLabel("About")
        VSpace(10)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                AboutRow(Icons.Rounded.Info, "About Voidbind", "Version 0.1.0", onAbout)
                VbHairline(Modifier.padding(horizontal = 16.dp))
                AboutRow(Icons.Rounded.Security, "Security & protocol", null, onSecurity)
                VbHairline(Modifier.padding(horizontal = 16.dp))
                AboutRow(Icons.Rounded.Code, "Open-source licenses", null, onLicenses)
            }
        }

        VSpace(28)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = VbColors.TextMuted, modifier = Modifier.size(16.dp))
            HSpace(8)
            Text("Your keys never leave your devices.", style = MaterialTheme.typography.bodyMedium, color = VbColors.TextMuted, textAlign = TextAlign.Center)
        }
    }
}

/** A small pill showing a trusted site's approval policy: trusted (TOFU) vs. always-ask. */
@Composable
private fun PolicyPill(site: TrustedSite) {
    val trusted = site.policy == ApprovalPolicy.TrustedTofu
    StatusPill(
        text = when {
            site.pinnedAlwaysAsk -> "Always ask (pinned)"
            trusted -> "Trusted"
            else -> "Always ask"
        },
        accent = if (trusted) VbColors.Mint else VbColors.Amber,
        leadingIcon = if (trusted) Icons.Rounded.Shield else Icons.Rounded.Lock,
    )
}

@Composable
private fun AboutRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    RowItem(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = { IconCircle(icon, tint = VbColors.Mint, background = VbColors.SurfaceElevated) },
        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VbColors.TextMuted) },
    )
}
