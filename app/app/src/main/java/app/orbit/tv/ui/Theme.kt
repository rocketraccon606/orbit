package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.tv.catalog.ChannelCard

object OrbitColors {
    val Background = Color(0xFF141414)
    val Surface = Color(0xFF181818)
    val SurfaceHigh = Color(0xFF232323)
    val Line = Color(0xFF333333)
    val Text = Color(0xFFF5F5F5)
    val Muted = Color(0xFFB3B3B3)
    val Accent = Color(0xFFE50914)
    val AccentSoft = Color(0x33E50914)
    val Gold = Color(0xFFFFC94A)
    val Live = Color(0xFF46D369)
    val Warning = Color(0xFFE8B45A)
    val Error = Color(0xFFE86A6A)
}

val AppBackdrop = Brush.verticalGradient(
    listOf(Color(0xFF1A1A1A), Color(0xFF141414)),
)

val HeroGradient = Brush.horizontalGradient(
    listOf(Color(0xFF10131A), Color(0xFF0B0D12), Color(0xFF07080B)),
)

val CardGradient = Brush.verticalGradient(
    listOf(Color(0xFF20242E), Color(0xFF12141A)),
)

/** Cinematic backdrop colours per channel category. */
data class CategoryPalette(val top: Color, val bottom: Color, val glow: Color)

fun categoryPalette(category: String): CategoryPalette = when (category.lowercase()) {
    "news" -> CategoryPalette(Color(0xFF172A44), Color(0xFF090E17), Color(0xFF4E8CFF))
    "movies" -> CategoryPalette(Color(0xFF321A22), Color(0xFF120A0D), Color(0xFFF0647A))
    "music" -> CategoryPalette(Color(0xFF241B3D), Color(0xFF0E0A18), Color(0xFF9A7BFF))
    "kids" -> CategoryPalette(Color(0xFF153626), Color(0xFF0A1610), Color(0xFF56E39A))
    "sports" -> CategoryPalette(Color(0xFF10333A), Color(0xFF081416), Color(0xFF38D6CC))
    "documentary" -> CategoryPalette(Color(0xFF1C302C), Color(0xFF0B1412), Color(0xFF63D6B0))
    "religious" -> CategoryPalette(Color(0xFF332A17), Color(0xFF140F09), Color(0xFFF0C25E))
    "entertainment" -> CategoryPalette(Color(0xFF33251B), Color(0xFF140F0A), Color(0xFFF2B36B))
    else -> CategoryPalette(Color(0xFF232735), Color(0xFF0C0E13), Color(0xFF8E99B5))
}

fun ChannelCard.yearLaunched(): String = launched.take(4)

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = OrbitColors.Text,
    weight: FontWeight = FontWeight.Normal,
    lines: Int = 1,
    letterSpacing: Float = 0f,
    drawStyle: DrawStyle? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            letterSpacing = letterSpacing.sp,
            drawStyle = drawStyle,
        ),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = OrbitColors.Muted,
    background: Color = OrbitColors.SurfaceHigh,
) {
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Label(text.uppercase(), size = 9, color = color, weight = FontWeight.Medium, letterSpacing = 0.8f)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xFF10231A), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .background(OrbitColors.Live, RoundedCornerShape(50))
                .padding(3.dp),
        )
        Label(" LIVE", size = 9, color = OrbitColors.Live, weight = FontWeight.Bold, letterSpacing = 0.8f)
    }
}
