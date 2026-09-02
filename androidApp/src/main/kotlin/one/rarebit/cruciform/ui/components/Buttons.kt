package one.rarebit.cruciform.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.rarebit.cruciform.ui.theme.VbColors

private val ButtonShape = RoundedCornerShape(14.dp)
private const val ButtonMinHeight = 54

/** Primary mint CTA with black text — "Yes, they match", "I've saved it". */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fill: Color = VbColors.Mint,
    onFill: Color = VbColors.OnMint,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = onFill,
            disabledContainerColor = fill.copy(alpha = 0.35f),
            disabledContentColor = onFill.copy(alpha = 0.6f),
        ),
        modifier = modifier.heightIn(min = ButtonMinHeight.dp),
    ) {
        ButtonContent(text, leadingIcon)
    }
}

/** Neutral / secondary outlined button. */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = VbColors.Mint,
    leadingIcon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        modifier = modifier.heightIn(min = ButtonMinHeight.dp),
    ) {
        ButtonContent(text, leadingIcon)
    }
}

/** Destructive outlined button — Deny, Revoke, No cancel. */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) = OutlineButton(text, onClick, modifier, enabled, VbColors.Coral, leadingIcon)

@Composable
private fun ButtonContent(text: String, leadingIcon: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.height(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}
