package dk.lawejo.snoretracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** "Nattehimmel": night-blue sky, faint stars, moonlight gold as the one accent. */
object Sky {
    val Top = Color(0xFF1D2A5C)
    val Mid = Color(0xFF0B1129)
    val Bottom = Color(0xFF05070F)
    val Surface = Color.White.copy(alpha = 0.045f)
    val Border = Color.White.copy(alpha = 0.09f)
    val Text = Color(0xFFE8ECF8)
    val Muted = Color(0xFF8F9BBD)
    val Moon = Color(0xFFF2DFA7)
    val Done = Color(0xFF8FD3C1)
    val Skipped = Color(0xFFE59A9A)
    val Tempo = Color(0xFFF0B37E)

    val colors = darkColorScheme(
        primary = Moon,
        onPrimary = Mid,
        background = Color.Transparent,
        onBackground = Text,
        surface = Color.Transparent,
        onSurface = Text,
        error = Skipped,
        outline = Border,
        outlineVariant = Border,
    )

    // Tabular numbers everywhere, so times line up under each other.
    val typography = Typography().run {
        copy(
            bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp, fontFeatureSettings = "tnum"),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
    }

    val h1 = TextStyle(fontSize = 28.sp, fontWeight = FontWeight(650), letterSpacing = (-0.02).em)
    val eyebrow = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em, color = Moon)
}

/** x, y (0–1), radius (dp), alpha. */
private val stars = listOf(
    floatArrayOf(.12f, .18f, 1f, .9f), floatArrayOf(.41f, .09f, 1.3f, .6f), floatArrayOf(.83f, .22f, 1f, .75f),
    floatArrayOf(.27f, .34f, .8f, .45f), floatArrayOf(.66f, .06f, .9f, .5f), floatArrayOf(.92f, .41f, 1.2f, .7f),
    floatArrayOf(.05f, .52f, .9f, .4f), floatArrayOf(.54f, .29f, 1.1f, .8f), floatArrayOf(.36f, .61f, .8f, .35f),
    floatArrayOf(.74f, .55f, 1f, .55f), floatArrayOf(.18f, .73f, 1.2f, .5f), floatArrayOf(.88f, .69f, .8f, .4f),
    floatArrayOf(.47f, .79f, 1f, .45f), floatArrayOf(.61f, .88f, .9f, .35f), floatArrayOf(.09f, .91f, .8f, .4f),
    floatArrayOf(.31f, .04f, .9f, .65f), floatArrayOf(.97f, .12f, .8f, .5f), floatArrayOf(.79f, .83f, 1.1f, .45f),
    floatArrayOf(.22f, .47f, 1f, .6f), floatArrayOf(.58f, .45f, .8f, .4f),
)

/** Sky gradient with stars. Put it on the root so it stays put while lists scroll. */
fun Modifier.nightSky() = drawBehind {
    drawRect(Brush.verticalGradient(0f to Sky.Top, 0.55f to Sky.Mid, 1f to Sky.Bottom))
    stars.forEach { (x, y, r, a) ->
        drawCircle(Color.White.copy(alpha = a), r.dp.toPx(), Offset(x * size.width, y * size.height))
    }
}

private val cardShape = RoundedCornerShape(14.dp)

/** Translucent, tappable card. [highlight] marks "i dag" with a moon edge. */
fun Modifier.skyCard(highlight: Boolean, onClick: () -> Unit) = this
    .clip(cardShape)
    .clickable(onClick = onClick)
    .background(Sky.Surface)
    .border(1.dp, if (highlight) Sky.Moon.copy(alpha = 0.6f) else Sky.Border, cardShape)
    .padding(horizontal = 16.dp, vertical = 14.dp)

/** Pill badge; text and edge in the status colour. */
@Composable
fun Badge(text: String, color: Color = Sky.Muted) {
    val pill = RoundedCornerShape(999.dp)
    Text(
        text,
        color = color,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .border(1.dp, if (color == Sky.Muted) Sky.Border else color.copy(alpha = 0.5f), pill)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
