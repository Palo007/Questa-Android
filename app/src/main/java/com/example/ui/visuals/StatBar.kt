package com.example.ui.visuals

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OptiRootTokens
import com.example.ui.theme.OptiSelectorTokens

/** `.bar.hp>i` / `.bar.xp>i` fill gradients (index.html:74-75). */
val HpFill = listOf(Color(0xFFFF7E7E), OptiRootTokens.Hp)
val XpFill = listOf(Color(0xFFFFF07A), OptiRootTokens.Xp)

private val BarShape = RoundedCornerShape(OptiDims.BAR_RADIUS.dp)
private val LabelShadow = Color.Black.copy(alpha = 0.8f)

/**
 * Opti `.bar` (index.html:70-81, PX-102): 14 dp, radius 6, `#23143d` track with a `#1a0f2e`
 * border, a gradient fill that eases to its width in .35 s, the `shine` sweep every 3.2 s and a
 * centered 10 sp bold white label with the 4-way 1 px outline plus a soft drop shadow.
 */
@Composable
fun OptiStatBar(fraction: Float, fill: List<Color>, label: String, modifier: Modifier = Modifier, animate: Boolean = true) {
    val w by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(OptiDims.BAR_FILL_MS, easing = CssEase), label = "barFill")
    val shine = if (animate) {
        val tr = rememberInfiniteTransition(label = "shine")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(OptiDims.SHINE_MS, easing = LinearEasing), RepeatMode.Restart), label = "shineT").value
    } else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(OptiDims.BAR_H.dp)
            .clip(BarShape)
            .background(OptiSelectorTokens.BarTrackBg, BarShape)
            .border(1.dp, OptiSelectorTokens.BarTrackBorder, BarShape)
            .semantics { contentDescription = label },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (animate) w else fraction.coerceIn(0f, 1f))
                .background(Brush.horizontalGradient(fill), BarShape)
                .drawWithContent {
                    drawContent()
                    // .bar>i::after: a transparent -> white .35 -> transparent band the fill's width,
                    // radius 6. The fill does not clip it; only the `.bar` track does
                    // (`overflow:hidden`, index.html:71), so the glint runs on past the fill end.
                    val dx = shineOffset(shine) * size.width
                    drawRoundRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                            startX = dx, endX = dx + size.width,
                        ),
                        topLeft = Offset(dx, 0f),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(OptiDims.BAR_RADIUS.dp.toPx()),
                    )
                }
                .testTag("bar_fill"),
        )
        OutlinedLabel(label, Modifier.align(Alignment.Center).padding(top = 1.dp))
    }
}

/**
 * `.bar .lab` (index.html:80-81): `text-shadow:-1px -1px 0, 1px -1px 0, -1px 1px 0, 1px 1px 0`
 * at black .8 plus `0 1px 2px` at black .9. Compose draws one shadow per text, so the four hard
 * offsets are four black copies under the white text, which carries the soft shadow.
 */
@Composable
fun OutlinedLabel(text: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        val base = TextStyle(fontSize = OptiDims.BAR_LABEL_SP.sp, fontWeight = FontWeight.Bold)
        for ((x, y) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
            Text(text, style = base.copy(color = LabelShadow), modifier = Modifier.padding(1.dp).offset(x.dp, y.dp))
        }
        val px = with(LocalDensity.current) { 1.dp.toPx() }
        Text(
            text,
            style = base.copy(color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, px), 2 * px)),
            modifier = Modifier.padding(1.dp).testTag("bar_label"),
        )
    }
}
