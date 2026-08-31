package one.rarebit.voidbind.app.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import one.rarebit.voidbind.app.domain.LoginRequest
import one.rarebit.voidbind.app.domain.SitePolicyView
import one.rarebit.voidbind.app.ui.components.DangerButton
import one.rarebit.voidbind.app.ui.components.HSpace
import one.rarebit.voidbind.app.ui.components.PrimaryButton
import one.rarebit.voidbind.app.ui.components.StatusPill
import one.rarebit.voidbind.app.ui.components.VSpace
import one.rarebit.voidbind.app.ui.components.VbCard
import one.rarebit.voidbind.app.ui.components.VbHairline
import one.rarebit.voidbind.app.ui.theme.VbColors

/**
 * Web-login approval (Mockup 4): the sovereign consent sheet, biometric-gated.
 *
 * [policy] is the RP's current per-site approval policy — trust-on-first-use vs.
 * always-ask. The sheet shows it as a pill and lets the user pin/clear "always ask"
 * inline via [onSetAlwaysAsk]. The policy governs UI friction only; the Approve path
 * still produces the same hardware-gated signature regardless of the toggle.
 */
@Composable
fun LoginApprovalScreen(
    request: LoginRequest,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    policy: SitePolicyView? = null,
    onSetAlwaysAsk: (Boolean) -> Unit = {},
) {
    var remaining by remember { mutableIntStateOf(request.expiresInSeconds) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VbColors.Background)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(VbColors.Mint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(32.dp))
        }
        VSpace(14)
        Text("LOGIN REQUEST", style = MaterialTheme.typography.labelMedium, color = VbColors.Mint)
        VSpace(6)
        Text(request.domain, style = MaterialTheme.typography.headlineMedium, color = VbColors.TextPrimary)
        // The web-login wire carries no human app name (only rp + audience), so this
        // line renders only when a display name is actually known.
        if (request.appName.isNotBlank()) {
            Text(request.appName, style = MaterialTheme.typography.bodyLarge, color = VbColors.TextSecondary)
        }
        VSpace(14)
        if (request.signatureValid) {
            StatusPill("Request signature valid", accent = VbColors.Mint, leadingIcon = Icons.Rounded.VerifiedUser)
        }

        VSpace(18)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("You're approving", style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary, modifier = Modifier.padding(16.dp))
                ApprovalRow(Icons.Rounded.VerifiedUser, "Sign in as", request.signInAs)
                ApprovalRow(Icons.Rounded.Public, "Origin", request.origin)
                ApprovalRow(Icons.Rounded.Schedule, "Requested", if (remaining > 0) "Now · expires in ${remaining}s" else "Expired")
                ApprovalRow(Icons.Rounded.Lock, "Access", request.access, last = true)
            }
        }

        VSpace(12)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(18.dp))
            HSpace(8)
            Text("No personal data is shared.", style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary)
        }

        if (policy != null) {
            VSpace(14)
            PolicyControl(policy = policy, onSetAlwaysAsk = onSetAlwaysAsk)
        }

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DangerButton("Deny", onClick = onDeny, leadingIcon = Icons.Rounded.Cancel, modifier = Modifier.weight(1f))
            PrimaryButton(
                text = "Approve",
                onClick = onApprove,
                enabled = remaining > 0,
                fill = VbColors.BlueSoft,
                onFill = VbColors.OnBlue,
                leadingIcon = Icons.Rounded.Fingerprint,
                modifier = Modifier.weight(1f),
            )
        }
        VSpace(10)
        Text("Biometric confirmation is required to sign", style = MaterialTheme.typography.bodyMedium, color = VbColors.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        VSpace(14)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(VbColors.SurfaceElevated)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Memory, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(16.dp))
            HSpace(8)
            Text("Signed by StrongBox key · this device", style = MaterialTheme.typography.labelMedium, color = VbColors.TextSecondary)
        }
    }
}

/**
 * The per-RP policy control on the consent sheet: a pill showing whether this site is
 * trusted (TOFU) or always-asks, and a switch to pin "always ask". Toggling ON forces
 * the full sheet every time; OFF trusts the site. The signature path is unchanged.
 */
@Composable
private fun PolicyControl(policy: SitePolicyView, onSetAlwaysAsk: (Boolean) -> Unit) {
    VbCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(20.dp))
                HSpace(12)
                Column(Modifier.weight(1f)) {
                    Text("Always ask", style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary)
                    Text(
                        if (policy.pinnedAlwaysAsk) "Full review every time (pinned)"
                        else if (policy.trusted) "Trusted — streamlined next time"
                        else "Review this site every time",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VbColors.TextSecondary,
                    )
                }
                Switch(
                    checked = policy.pinnedAlwaysAsk,
                    onCheckedChange = onSetAlwaysAsk,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VbColors.OnMint,
                        checkedTrackColor = VbColors.Mint,
                        uncheckedTrackColor = VbColors.SurfaceElevated,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ApprovalRow(icon: ImageVector, label: String, value: String, last: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = VbColors.TextMuted, modifier = Modifier.size(20.dp))
        HSpace(12)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = VbColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = VbColors.TextPrimary, fontWeight = FontWeight.Medium)
    }
    if (!last) VbHairline(Modifier.padding(horizontal = 16.dp))
}
