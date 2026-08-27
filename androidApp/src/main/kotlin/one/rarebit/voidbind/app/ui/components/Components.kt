package one.rarebit.voidbind.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.rarebit.voidbind.app.ui.theme.VbColors
import one.rarebit.voidbind.app.ui.theme.VbType

/** All-caps tracked section label, e.g. "YOUR IDENTITY". */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = VbColors.TextMuted,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = VbType.SectionLabel, color = color)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** A rounded, bordered card — the app's primary content container. */
@Composable
fun VbCard(
    modifier: Modifier = Modifier,
    color: Color = VbColors.Surface,
    border: BorderStroke? = BorderStroke(1.dp, VbColors.OutlineSoft),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit,
) {
    Surface(color = color, shape = shape, border = border, modifier = modifier) {
        content()
    }
}

/** An outlined status pill, e.g. "Hardware-backed", "Offline-verifiable". */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = VbColors.Mint,
    leadingIcon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A circular icon badge with a tinted background. */
@Composable
fun IconCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = VbColors.Mint,
    background: Color = VbColors.SurfaceElevated,
    diameter: Int = 44,
    iconSize: Int = 22,
) {
    Box(
        modifier = modifier
            .size(diameter.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}

/** A thin divider matching the card interior hairline. */
@Composable
fun VbHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(VbColors.OutlineSoft),
    )
}

/** A tinted wash card used for the status callouts (StrongBox, recovery warning). */
@Composable
fun WashCard(
    accent: Color,
    wash: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = wash,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

/** Consistent screen content padding. */
val ScreenPadding = PaddingValues(horizontal = 20.dp)

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))
