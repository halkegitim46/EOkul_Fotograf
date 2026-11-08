package com.eokul.studio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Uygulama genelinde TEK düğme standardı (ölçüler ve roller):
 *  - Yükseklik 52 dp (başparmak için rahat), köşe 14 dp, yazı 15 sp yarı kalın, simge 20 dp.
 *  - Birincil  : dolu, ana renk      → ekranın TEK ana eylemi (Kaydet, Fotoğraf çek, Uygula)
 *  - İkincil   : dolu, nötr yüzey    → yan eylemler (Kırpmayı düzenle, Önceki/Sonraki)
 *  - Tehlike   : çerçeveli, kırmızı  → geri alınması zor / vazgeçme eylemleri (Yeniden çek, İptal)
 *  - Üçüncül   : yalnız yazı         → önemsiz eylemler (Vazgeç)
 * Koyu ekranlar (kamera, inceleme, düzenleyici) dark = true kullanır.
 */
enum class BtnStyle { Primary, Secondary, Danger, Tertiary }

val BtnHeight = 52.dp
private val BtnShape = RoundedCornerShape(14.dp)
private val BtnPad = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private val DangerRed = Color(0xFFE5645F)
private val DarkSurface = Color(0xFF2A3042)
private val DarkOnSurface = Color(0xFFE8EBF4)
private val DarkDisabled = Color(0xFF59627A)

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    dark: Boolean = false,
    /** Aynı satırda 4 düğme gerektiğinde: dar iç boşluk, 14 sp yazı. */
    compact: Boolean = false
) {
    val pad = if (compact) PaddingValues(horizontal = 6.dp, vertical = 8.dp) else BtnPad
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text, fontSize = if (compact) 14.sp else 15.sp, maxLines = 1, softWrap = false,
            fontWeight = if (style == BtnStyle.Primary) FontWeight.Bold else FontWeight.SemiBold
        )
    }
    val m = modifier.defaultMinSize(minHeight = BtnHeight)
    when (style) {
        BtnStyle.Primary -> Button(
            onClick = onClick, modifier = m, enabled = enabled, shape = BtnShape, contentPadding = pad,
            content = content
        )
        BtnStyle.Secondary -> Button(
            onClick = onClick, modifier = m, enabled = enabled, shape = BtnShape, contentPadding = pad,
            colors = if (dark) ButtonDefaults.buttonColors(
                containerColor = DarkSurface, contentColor = DarkOnSurface,
                disabledContainerColor = Color(0xFF1B1F2B), disabledContentColor = DarkDisabled
            ) else ButtonDefaults.filledTonalButtonColors(),
            content = content
        )
        BtnStyle.Danger -> OutlinedButton(
            onClick = onClick, modifier = m, enabled = enabled, shape = BtnShape, contentPadding = pad,
            border = BorderStroke(1.5.dp, if (enabled) DangerRed else DarkDisabled),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed, disabledContentColor = DarkDisabled),
            content = content
        )
        BtnStyle.Tertiary -> TextButton(
            onClick = onClick, modifier = m, enabled = enabled, shape = BtnShape, contentPadding = pad,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (dark) DarkOnSurface else androidx.compose.material3.MaterialTheme.colorScheme.primary,
                disabledContentColor = DarkDisabled
            ),
            content = content
        )
    }
}

/** Standart boşluk: aynı satırdaki düğmeler arası 10 dp. */
val BtnGap = Arrangement.spacedBy(10.dp)
