package one.rarebit.cruciform.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import one.rarebit.cruciform.ui.theme.VbColors

/**
 * "Scan recovery sheet" under a recovery-secret field, followed by [spacing] dp. Shows
 * nothing when [onScan] is null.
 */
@Composable
fun ScanSheetButton(onScan: (() -> Unit)?, enabled: Boolean, spacing: Int) {
    if (onScan == null) return
    Column {
        OutlineButton(
            text = "Scan recovery sheet",
            onClick = onScan,
            enabled = enabled,
            accent = VbColors.Mint,
            leadingIcon = Icons.Rounded.QrCodeScanner,
            modifier = Modifier.fillMaxWidth(),
        )
        VSpace(spacing)
    }
}

/**
 * Put a [scanned] recovery secret into its field once ([fill]), then let the holder
 * forget it ([onConsumed]). Nothing is submitted: the human still presses the button.
 */
@Composable
fun ScannedSecretEffect(scanned: String?, onConsumed: () -> Unit, fill: (String) -> Unit) {
    LaunchedEffect(scanned) {
        if (scanned != null) {
            fill(scanned)
            onConsumed()
        }
    }
}
