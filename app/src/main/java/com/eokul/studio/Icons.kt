package com.eokul.studio

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 48 dp dokunma alanlı, 28 dp'lik belirgin simge düğmesi. */
@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true
) {
    IconButton(onClick = onClick, modifier = modifier.size(52.dp), enabled = enabled) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun BackBtn(onClick: () -> Unit, tint: Color = LocalContentColor.current) =
    IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Geri", onClick, tint = tint)

@Composable
fun CloseBtn(onClick: () -> Unit, tint: Color = Color.White) =
    IconBtn(Icons.Filled.Close, "Kapat", onClick, tint = tint)

@Composable
fun MoreBtn(onClick: () -> Unit, tint: Color = LocalContentColor.current) =
    IconBtn(Icons.Filled.MoreVert, "Diğer işlemler", onClick, tint = tint)

@Composable
fun SettingsBtn(onClick: () -> Unit, tint: Color = LocalContentColor.current) =
    IconBtn(Icons.Filled.Settings, "Ayarlar", onClick, tint = tint)

@Composable
fun PrevBtn(onClick: () -> Unit, enabled: Boolean, tint: Color = Color.White) =
    IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Önceki öğrenci", onClick, tint = if (enabled) tint else Color.Gray, enabled = enabled)

@Composable
fun NextBtn(onClick: () -> Unit, enabled: Boolean, tint: Color = Color.White) =
    IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Sonraki öğrenci", onClick, tint = if (enabled) tint else Color.Gray, enabled = enabled)

private fun vec(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

object CamIcons {
    val FlashOn: ImageVector by lazy { vec("FlashOn", "M7 2v11h3v9l7-12h-4l4-8z") }
    val FlashOff: ImageVector by lazy {
        vec("FlashOff", "M3.27 3L2 4.27l5 5V13h3v9l3.58-6.14L17.73 20 19 18.73 3.27 3zM17 10h-4l4-8H7v2.18l8.46 8.46L17 10z")
    }
    val Gallery: ImageVector by lazy {
        vec(
            "Gallery",
            "M22 16V4c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2zm-11-4l2.03 2.71L16 11l4 5H8l3-4zM2 6v14c0 1.1.9 2 2 2h14v-2H4V6H2z"
        )
    }
    val SkipNext: ImageVector by lazy { vec("SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z") }
}
