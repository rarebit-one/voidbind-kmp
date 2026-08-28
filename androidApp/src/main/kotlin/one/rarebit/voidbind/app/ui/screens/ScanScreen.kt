package one.rarebit.voidbind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.rarebit.voidbind.app.ui.components.HSpace
import one.rarebit.voidbind.app.ui.components.OutlineButton
import one.rarebit.voidbind.app.ui.components.VSpace
import one.rarebit.voidbind.app.ui.scan.QrScanner
import one.rarebit.voidbind.app.ui.theme.VbColors

/**
 * QR scanner (Mockup 3): a live camera viewfinder that decodes Voidbind login and
 * pairing codes, with a manual-entry fallback. The scanned payload is handed up via
 * [onCode]; the nav layer parses it (VoidbindQr) and routes to login or pairing.
 */
@Composable
fun ScanScreen(
    onClose: () -> Unit,
    onCode: (String) -> Unit,
    onEnterManually: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(VbColors.Background)) {
        QrScanner(
            onQr = onCode,
            modifier = Modifier.fillMaxSize(),
            noPermission = {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Camera access is needed to scan Voidbind codes.",
                        style = MaterialTheme.typography.titleMedium,
                        color = VbColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    VSpace(16)
                    OutlineButton("Enter code instead", onClick = onEnterManually, accent = VbColors.Mint)
                }
            },
        )

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(VbColors.Background.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = VbColors.TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            Text("Scan QR code", style = MaterialTheme.typography.titleMedium, color = VbColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { /* torch toggle: wired with the camera controller later */ }) {
                Icon(Icons.Rounded.FlashlightOn, contentDescription = "Torch", tint = VbColors.TextPrimary)
            }
        }

        // Viewfinder
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val corner = size.minDimension * 0.14f
                val sw = 8f
                val stroke = Stroke(width = sw)
                fun l(x1: Float, y1: Float, x2: Float, y2: Float) =
                    drawLine(VbColors.Blue, Offset(x1, y1), Offset(x2, y2), strokeWidth = sw)
                // corner brackets
                l(0f, 0f, corner, 0f); l(0f, 0f, 0f, corner)
                l(size.width, 0f, size.width - corner, 0f); l(size.width, 0f, size.width, corner)
                l(0f, size.height, corner, size.height); l(0f, size.height, 0f, size.height - corner)
                l(size.width, size.height, size.width - corner, size.height); l(size.width, size.height, size.width, size.height - corner)
                // scan line
                drawLine(VbColors.Mint, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 4f)
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Point your camera at a Voidbind code",
                style = MaterialTheme.typography.titleMedium,
                color = VbColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            VSpace(6)
            Text(
                "Login requests and device invites are verified before approval.",
                style = MaterialTheme.typography.bodyMedium,
                color = VbColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            VSpace(16)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(VbColors.Surface),
            ) {
                ModeHint(Icons.Rounded.Public, "Web login", Modifier.weight(1f))
                ModeHint(Icons.Rounded.Smartphone, "Pair device", Modifier.weight(1f))
            }
            VSpace(14)
            OutlineButton("Enter code instead", onClick = onEnterManually, accent = VbColors.Mint, leadingIcon = Icons.Rounded.Keyboard)
        }
    }
}

@Composable
private fun ModeHint(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = VbColors.Mint, modifier = Modifier.size(18.dp))
        HSpace(8)
        Text(label, style = MaterialTheme.typography.labelLarge, color = VbColors.TextPrimary)
    }
}
