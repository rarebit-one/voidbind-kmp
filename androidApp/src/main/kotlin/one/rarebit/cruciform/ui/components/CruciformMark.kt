package one.rarebit.cruciform.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import one.rarebit.cruciform.ui.theme.VbColors

/**
 * The Cruciform mark: two interlocking rings in the brand mint + blue. Drawn rather
 * than shipped as a raster so it stays crisp at any size and re-tints with the
 * theme. A placeholder for the final logo, matching the app icon.
 */
@Composable
fun CruciformMark(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val r = s * 0.24f
        val stroke = Stroke(width = s * 0.09f)
        val cy = s / 2f
        drawCircle(color = VbColors.Mint, radius = r, center = Offset(s * 0.40f, cy), style = stroke)
        drawCircle(color = VbColors.Blue, radius = r, center = Offset(s * 0.60f, cy), style = stroke)
    }
}
