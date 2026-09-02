package one.rarebit.cruciform.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.rarebit.cruciform.ui.theme.VbColors

/** The three-slot bottom bar: Home, a centered Scan action, Settings. */
@Composable
fun CruciformBottomBar(
    currentRoute: String?,
    onHome: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = VbColors.Background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarItem(Icons.Rounded.Home, "Home", selected = currentRoute == Routes.HOME, onClick = onHome)
            ScanButton(onScan)
            BarItem(Icons.Rounded.Settings, "Settings", selected = currentRoute == Routes.SETTINGS, onClick = onSettings)
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) VbColors.Blue else VbColors.TextMuted
    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = tint, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ScanButton(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(BorderStroke(2.dp, VbColors.Blue), CircleShape)
                .background(VbColors.Blue.copy(alpha = 0.10f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan", tint = VbColors.Blue, modifier = Modifier.size(28.dp))
        }
        Text("Scan", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = VbColors.Blue)
    }
}
