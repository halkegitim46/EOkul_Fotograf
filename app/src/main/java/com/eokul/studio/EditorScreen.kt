package com.eokul.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private val EditorBg = Color(0xFF101014)
private val Accent = Color(0xFF7AA2F7)
private val FaceColor = Color(0xFF9ECE6A)
private val WarnTxt = Color(0xFFE0AF68)
private val DimTxt = Color(0xFFB0B6C8)

/** Görüntünün tuval üzerindeki ölçeği ve ofseti. */
private class ViewTf(val scale: Float, val ox: Float, val oy: Float)

private fun viewTf(w: Float, h: Float, iw: Int, ih: Int): ViewTf {
    if (iw <= 0 || ih <= 0 || w <= 0f || h <= 0f) return ViewTf(1f, 0f, 0f)
    val sc = min((w - 8f) / iw, (h - 8f) / ih).coerceAtLeast(0.0001f)
    return ViewTf(sc, (w - iw * sc) / 2f, (h - ih * sc) / 2f)
}

/** Gerçek çıktı önizlemesi (133x171) ve JPEG boyutu — kutu değişince kısa gecikmeyle yenilenir. */
@Composable
fun rememberPreview(s: EditSession?, settings: Settings): Pair<Bitmap, Int>? {
    val appCtx = LocalContext.current.applicationContext
    var result by remember(s) { mutableStateOf<Pair<Bitmap, Int>?>(null) }
    if (s != null) {
        LaunchedEffect(s, s.box, s.loading, settings) {
            if (s.loading) return@LaunchedEffect
            val px = s.pixels ?: return@LaunchedEffect
            delay(60)
            val box = s.box
            result = withContext(Dispatchers.Default) { ImageOps.preview(appCtx, px, s.iw, s.ih, box, settings) }
        }
    }
    return result
}

@Composable
fun EditorScreen(vm: AppViewModel) {
    val s = vm.session
    val position = if (s == null) 0 else vm.items.indexOfFirst { it.name == s.name } + 1
    EditorContent(
        s = s,
        title = if (s == null) "" else "${s.name}   ($position/${vm.items.size})",
        settings = vm.settings,
        onCancel = { vm.closeEditor() },
        onApply = { vm.applyEditor() },
        onResetFace = { vm.editorResetToFace() },
        onPickFace = { vm.editorPickFace(it) },
        onPrev = { vm.navigateEditor(-1) },
        onNext = { vm.navigateEditor(1) },
        applyLabel = "Uygula"
    )
}

@Composable
fun EditorContent(
    s: EditSession?,
    title: String,
    settings: Settings,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    onResetFace: () -> Unit,
    onPickFace: (Int) -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    applyLabel: String
) {
    BackHandler { onCancel() }
    if (s == null) {
        Box(Modifier.fillMaxSize().background(EditorBg))
        return
    }
    val preview = rememberPreview(s, settings)
    val previewBmp = preview?.first
    val previewBytes = preview?.second ?: 0

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = s.bitmap
    val img = remember(bitmap) { bitmap?.asImageBitmap() }
    Column(Modifier.fillMaxSize().background(EditorBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            CloseBtn(onClick = onCancel)
        }

        // ---- Tuval: tek parmak = kutuyu taşı, iki parmak = yakınlaştır / uzaklaştır
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it }
                .pointerInput(s) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (s.loading) return@detectTransformGestures
                        val tf = viewTf(canvasSize.width.toFloat(), canvasSize.height.toFloat(), s.iw, s.ih)
                        if (zoom != 1f) {
                            val ax = ((centroid.x - tf.ox) / tf.scale).toDouble()
                            val ay = ((centroid.y - tf.oy) / tf.scale).toDouble()
                            s.zoom(1.0 / zoom, ax, ay)
                        }
                        if (pan != Offset.Zero) {
                            s.pan((pan.x / tf.scale).toDouble(), (pan.y / tf.scale).toDouble())
                        }
                    }
                }
        ) {
            if (s.loading || img == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val tf = viewTf(size.width, size.height, s.iw, s.ih)
                    val dw = s.iw * tf.scale
                    val dh = s.ih * tf.scale
                    drawImage(
                        image = img,
                        dstOffset = IntOffset(tf.ox.roundToInt(), tf.oy.roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt())
                    )
                    val b = s.box
                    val bx1 = tf.ox + (b.x1 * tf.scale).toFloat()
                    val by1 = tf.oy + (b.y1 * tf.scale).toFloat()
                    val bx2 = tf.ox + (b.x2 * tf.scale).toFloat()
                    val by2 = tf.oy + (b.y2 * tf.scale).toFloat()

                    // Kutu dışını karart
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset(tf.ox, tf.oy), Size(dw, (by1 - tf.oy).coerceAtLeast(0f)))
                    drawRect(dim, Offset(tf.ox, by2), Size(dw, (tf.oy + dh - by2).coerceAtLeast(0f)))
                    drawRect(dim, Offset(tf.ox, by1), Size((bx1 - tf.ox).coerceAtLeast(0f), by2 - by1))
                    drawRect(dim, Offset(bx2, by1), Size((tf.ox + dw - bx2).coerceAtLeast(0f), by2 - by1))

                    // Üçler kuralı çizgileri
                    val grid = Color.White.copy(alpha = 0.45f)
                    for (k in 1..2) {
                        val gx = bx1 + (bx2 - bx1) * k / 3f
                        val gy = by1 + (by2 - by1) * k / 3f
                        drawLine(grid, Offset(gx, by1), Offset(gx, by2), strokeWidth = 1f)
                        drawLine(grid, Offset(bx1, gy), Offset(bx2, gy), strokeWidth = 1f)
                    }

                    // Algılanan yüzler
                    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    s.faces.forEachIndexed { i, f ->
                        val fx1 = tf.ox + (f.box.x1 * tf.scale).toFloat()
                        val fy1 = tf.oy + (f.box.y1 * tf.scale).toFloat()
                        val fx2 = tf.ox + (f.box.x2 * tf.scale).toFloat()
                        val fy2 = tf.oy + (f.box.y2 * tf.scale).toFloat()
                        drawRect(
                            color = if (i == 0) Accent else FaceColor,
                            topLeft = Offset(fx1, fy1),
                            size = Size(fx2 - fx1, fy2 - fy1),
                            style = Stroke(width = 2f, pathEffect = dash)
                        )
                        if (!f.headTop.isNaN()) {
                            val hy = tf.oy + (f.headTop * tf.scale).toFloat()
                            drawLine(
                                if (i == 0) Accent else FaceColor,
                                Offset(fx1, hy), Offset(fx2, hy), strokeWidth = 2f
                            )
                        }
                    }

                    // Kırpma çerçevesi ve köşe işaretleri
                    drawRect(Accent, Offset(bx1, by1), Size(bx2 - bx1, by2 - by1), style = Stroke(width = 3f))
                    val hs = 9f
                    for ((hx, hy) in listOf(bx1 to by1, bx2 to by1, bx1 to by2, bx2 to by2)) {
                        drawRect(Color.White, Offset(hx - hs, hy - hs), Size(hs * 2, hs * 2))
                        drawRect(Accent, Offset(hx - hs, hy - hs), Size(hs * 2, hs * 2), style = Stroke(width = 2f))
                    }
                }
            }
        }

        // ---- Önizleme ve bilgi
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pb = previewBmp
            val pimg = remember(pb) { pb?.asImageBitmap() }
            if (pimg != null) {
                Image(
                    bitmap = pimg,
                    contentDescription = "Önizleme",
                    modifier = Modifier.height(112.dp).aspectRatio(TARGET_W.toFloat() / TARGET_H)
                )
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Dosya: ${"%.1f".format(previewBytes / 1024.0)} KB", color = Color.White, fontSize = 13.sp)
                Text("Kaynak: ${s.iw}×${s.ih} px", color = DimTxt, fontSize = 12.sp)
                Text("Kırpma: ${s.box.width.toInt()}×${s.box.height.toInt()} px", color = DimTxt, fontSize = 12.sp)
                Text("Yüz: ${s.faces.size} adet", color = DimTxt, fontSize = 12.sp)
                val warn = when {
                    s.box.width < TARGET_W -> "⚠ Kırpılan alan hedef çözünürlükten küçük."
                    s.faces.size > 1 && !s.changed() -> "⚠ ${s.faces.size} yüz algılandı — aşağıdan seçin."
                    !s.loading && s.faces.isEmpty() && !s.faceFound && !s.changed() -> "⚠ Yüz bulunamadı; alanı elle ayarlayın."
                    else -> ""
                }
                if (warn.isNotEmpty()) Text(warn, color = WarnTxt, fontSize = 12.sp)
            }
        }

        if (s.faces.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Yüz:", color = DimTxt, fontSize = 13.sp)
                s.faces.forEachIndexed { i, _ ->
                    AppButton("${i + 1}", onClick = { onPickFace(i) }, style = BtnStyle.Secondary, dark = true, compact = true)
                }
            }
        }

        // ---- Düğmeler (standart: Buttons.kt) — ana eylem altta, geniş; yan eylemler eşit genişlikte
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onPrev != null || onNext != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = BtnGap) {
                    if (onPrev != null) AppButton(
                        "Önceki", onClick = onPrev, style = BtnStyle.Secondary, dark = true,
                        icon = Icons.Filled.KeyboardArrowLeft, modifier = Modifier.weight(1f)
                    )
                    if (onNext != null) AppButton(
                        "Sonraki", onClick = onNext, style = BtnStyle.Secondary, dark = true,
                        icon = Icons.Filled.KeyboardArrowRight, modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Uzak", onClick = { s.zoom(1.06, s.box.cx, s.box.cy) }, style = BtnStyle.Secondary, dark = true, compact = true, modifier = Modifier.weight(1f))
                AppButton("Yakın", onClick = { s.zoom(0.94, s.box.cx, s.box.cy) }, style = BtnStyle.Secondary, dark = true, compact = true, modifier = Modifier.weight(1f))
                AppButton("Geri", onClick = { s.undo() }, enabled = s.canUndo, style = BtnStyle.Secondary, dark = true, compact = true, modifier = Modifier.weight(1f))
                AppButton("Yüze dön", onClick = onResetFace, enabled = !s.loading, style = BtnStyle.Secondary, dark = true, compact = true, modifier = Modifier.weight(1.2f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = BtnGap) {
                AppButton("İptal", onClick = onCancel, style = BtnStyle.Danger, dark = true, modifier = Modifier.weight(1f))
                AppButton(
                    applyLabel, onClick = onApply, enabled = !s.loading, style = BtnStyle.Primary, dark = true,
                    icon = Icons.Filled.Check, modifier = Modifier.weight(1.6f)
                )
            }
        }
    }
}
