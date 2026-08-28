package one.rarebit.voidbind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.voidbind.app.domain.SiteAccent
import one.rarebit.voidbind.app.ui.theme.VbColors
import one.rarebit.voidbind.app.ui.theme.VbType

/** The identity line: `vb1 · 7C4A 91D2 0E8F` in mono, with an optional copy action. */
@Composable
fun IdentityFingerprint(
    version: String,
    fingerprint: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$version · $fingerprint",
            style = VbType.FingerprintLarge,
            color = VbColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onCopy != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "Copy identity",
                    tint = VbColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** A generic list row: leading slot, title + optional subtitle, trailing slot. */
@Composable
fun RowItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color = VbColors.TextPrimary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = VbColors.TextSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A relying-party avatar — a tinted rounded square with a glyph, keyed by accent. */
@Composable
fun SiteAvatar(accent: SiteAccent, modifier: Modifier = Modifier, diameter: Int = 44) {
    val (bg, tint) = when (accent) {
        SiteAccent.BLUE -> VbColors.Blue.copy(alpha = 0.18f) to VbColors.Blue
        SiteAccent.PURPLE -> Color(0xFF8B5CF6).copy(alpha = 0.20f) to Color(0xFFB794F6)
        SiteAccent.MINT -> VbColors.Mint.copy(alpha = 0.16f) to VbColors.Mint
    }
    val icon = when (accent) {
        SiteAccent.BLUE -> Icons.Rounded.Hub
        SiteAccent.PURPLE -> Icons.Rounded.Public
        SiteAccent.MINT -> Icons.Rounded.Home
    }
    Box(
        modifier = modifier
            .size(diameter.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
