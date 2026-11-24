package si.skladisce.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SkladisceColors {
    val BgPrimary = Color(0xFF0B0F19)
    val BgCard = Color(0xFF151D2F)
    val BgCardElevated = Color(0xFF1E293B)
    val BgHeader = Color(0xFF0F172A)
    val TextMain = Color(0xFFF8FAFC)
    val TextMuted = Color(0xFF94A3B8)
    val TextDim = Color(0xFF64748B)

    val Primary = Color(0xFF38BDF8)
    val PrimaryDark = Color(0xFF0284C7)
    val Accent = Color(0xFF6366F1)

    val Success = Color(0xFF10B981)
    val SuccessBg = Color(0x2610B981)
    val Warning = Color(0xFFF59E0B)
    val WarningBg = Color(0x26F59E0B)
    val Danger = Color(0xFFEF4444)
    val DangerBg = Color(0x26EF4444)

    val Border = Color(0xFF223049)
}

private val DarkScheme = darkColorScheme(
    primary = SkladisceColors.Primary,
    onPrimary = Color(0xFF042F2E),
    secondary = SkladisceColors.Success,
    onSecondary = Color(0xFF042F2E),
    tertiary = SkladisceColors.Accent,
    background = SkladisceColors.BgPrimary,
    onBackground = SkladisceColors.TextMain,
    surface = SkladisceColors.BgCard,
    onSurface = SkladisceColors.TextMain,
    surfaceVariant = SkladisceColors.BgCardElevated,
    onSurfaceVariant = SkladisceColors.TextMuted,
    outline = SkladisceColors.Border,
    error = SkladisceColors.Danger
)

@Composable
fun SkladisceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content
    )
}

@Composable
fun SkladisceCard(
    modifier: Modifier = Modifier,
    borderColor: Color = SkladisceColors.Border,
    containerColor: Color = SkladisceColors.BgCard,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        content = content
    )
}

@Composable
fun StatusPill(
    text: String,
    textColor: Color,
    bgColor: Color,
    borderColor: Color = textColor.copy(alpha = 0.35f),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    valueColor: Color = SkladisceColors.TextMain,
    modifier: Modifier = Modifier
) {
    SkladisceCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = label,
                color = SkladisceColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

fun warehouseLabel(id: String): String = when (id) {
    "trgovina" -> "Prodajalna"
    "zgornje" -> "Zgornje skl."
    "spodnje" -> "Spodnje skl."
    else -> id
}

fun warehouseColor(id: String): Color = when (id) {
    "trgovina" -> SkladisceColors.Primary
    "zgornje" -> SkladisceColors.Success
    "spodnje" -> SkladisceColors.Warning
    else -> SkladisceColors.Accent
}
