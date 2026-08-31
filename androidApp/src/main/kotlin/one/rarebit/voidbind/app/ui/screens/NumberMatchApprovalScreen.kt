package one.rarebit.voidbind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import one.rarebit.voidbind.app.domain.LoginRequest
import one.rarebit.voidbind.app.domain.SampleData
import one.rarebit.voidbind.app.ui.components.DangerButton
import one.rarebit.voidbind.app.ui.components.HSpace
import one.rarebit.voidbind.app.ui.components.VSpace
import one.rarebit.voidbind.app.ui.components.VbCard
import one.rarebit.voidbind.app.ui.components.VbHairline
import one.rarebit.voidbind.app.ui.theme.VbColors
import one.rarebit.voidbind.app.ui.theme.VoidbindTheme

/**
 * Number-match approval — the anti-phishing sheet for a **push-woken** login (a v2
 * challenge, ADR-0006). Nothing was scanned, so the QR's origin-binding is restored
 * by a human step: the initiating surface (the browser signing in) shows one number;
 * this screen shows several candidates; the user taps the one that matches. The tap
 * signs the challenge bound to THAT number — a decoy tap binds the wrong number and
 * the RP refuses it, so a login triggered by an attacker cannot be waved through.
 *
 * Same visual language as [LoginApprovalScreen]; the difference is the number grid
 * in place of a single Approve button. Tapping a number is the biometric-gated
 * approval, so [onApprove] fires the hardware signature for the chosen value.
 */
@Composable
fun NumberMatchApprovalScreen(
    request: LoginRequest,
    onDeny: () -> Unit,
    onApprove: (chosen: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var remaining by remember { mutableIntStateOf(request.expiresInSeconds) }
    var chosen by remember { mutableStateOf<Int?>(null) }
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
            Icon(Icons.Rounded.PhonelinkLock, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(32.dp))
        }
        VSpace(14)
        Text("CONFIRM IT'S YOU", style = MaterialTheme.typography.labelMedium, color = VbColors.Mint)
        VSpace(6)
        Text(request.domain, style = MaterialTheme.typography.headlineMedium, color = VbColors.TextPrimary)
        VSpace(6)
        Text(
            "Tap the number shown on the screen you're signing in on.",
            style = MaterialTheme.typography.bodyMedium,
            color = VbColors.TextSecondary,
            textAlign = TextAlign.Center,
        )

        VSpace(18)
        VbCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                MatchRow(Icons.Rounded.Public, "Origin", request.origin)
                MatchRow(
                    Icons.Rounded.Schedule,
                    "Requested",
                    if (remaining > 0) "Now · expires in ${remaining}s" else "Expired",
                    last = true,
                )
            }
        }

        VSpace(18)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (n in request.candidates) {
                NumberTile(
                    number = n,
                    selected = chosen == n,
                    enabled = remaining > 0 && chosen == null,
                    onClick = {
                        chosen = n
                        onApprove(n)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        DangerButton("Not me · Deny", onClick = onDeny, leadingIcon = Icons.Rounded.Cancel, modifier = Modifier.fillMaxWidth())
        VSpace(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = VbColors.TextMuted, modifier = Modifier.size(16.dp))
            HSpace(8)
            Text(
                "Tapping a number signs with your device key",
                style = MaterialTheme.typography.bodyMedium,
                color = VbColors.TextMuted,
                textAlign = TextAlign.Center,
            )
        }
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

@Composable
private fun NumberTile(
    number: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) VbColors.Mint.copy(alpha = 0.16f) else VbColors.SurfaceElevated
    val fg = if (selected) VbColors.Mint else VbColors.TextPrimary
    Box(
        modifier = modifier
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // Two digits, padded, so a glance-match is unambiguous (00–99).
        Text(
            number.toString().padStart(2, '0'),
            style = MaterialTheme.typography.headlineMedium,
            color = fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MatchRow(icon: ImageVector, label: String, value: String, last: Boolean = false) {
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

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14)
@Composable
private fun NumberMatchApprovalPreview() {
    VoidbindTheme {
        NumberMatchApprovalScreen(request = SampleData.numberMatchRequest, onDeny = {}, onApprove = {})
    }
}
