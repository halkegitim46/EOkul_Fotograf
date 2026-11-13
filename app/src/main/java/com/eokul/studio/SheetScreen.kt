package com.eokul.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun rememberThumb(classId: String, s: Student): Bitmap? {
    val ctx = LocalContext.current
    val b by produceState<Bitmap?>(null, classId, s.no, s.updatedAt, s.captured) {
        value = if (!s.captured) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(Naming.privatePhoto(ctx, classId, s.no).absolutePath)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    return b
}

/** Sınıfın tüm fotoğraflarını ızgarada gösterir; tek dokunuşla büyütüp yeniden çekme / düzenleme. */
@Composable
fun SheetScreen(studio: StudioViewModel, classId: String) {
    val cls = studio.classById(classId)
    if (cls == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    var onlyMissing by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    val shown = if (onlyMissing) cls.students.filter { !it.captured } else cls.students

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                BackBtn(onClick = { studio.back() }, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(
                        "${cls.name} · kontrol", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "${cls.capturedCount}/${cls.students.size} fotoğraf",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                FilterChip(
                    selected = onlyMissing, onClick = { onlyMissing = !onlyMissing },
                    label = { Text("Yalnız eksikler") },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 92.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(shown, key = { _, st -> st.no }) { index, s ->
                val thumb = rememberThumb(cls.id, s)
                Column(Modifier.clickable { viewer = index }) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(TARGET_W.toFloat() / TARGET_H)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x22888888))
                            .border(
                                if (s.captured) 0.dp else 1.dp,
                                if (s.captured) Color.Transparent else MaterialTheme.colorScheme.error,
                                RoundedCornerShape(6.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(), contentDescription = s.name,
                                contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(if (s.captured) "…" else "📷", fontSize = 22.sp)
                        }
                    }
                    Text(s.no, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(s.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    val vi = viewer
    if (vi != null && shown.isNotEmpty()) {
        PhotoPager(
            studio = studio, classId = cls.id, list = shown, startIndex = vi,
            onClose = { viewer = null }
        )
    }
    }   // Box
}

/**
 * Tam ekran fotoğraf gezgini: sağa-sola kaydırarak sıradaki / önceki öğrenciye geçilir. Üstte sıra ve ilerleme,
 * altta o öğrenciye özel işlemler (çek / yeniden çek / kırpmayı düzenle) ve önceki-sonraki düğmeleri bulunur.
 */
@Composable
private fun PhotoPager(
    studio: StudioViewModel, classId: String, list: List<Student>, startIndex: Int, onClose: () -> Unit
) {
    val total = list.size
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, total - 1)) { total }
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }
    val page = pager.currentPage.coerceIn(0, total - 1)
    val cur = list[page]
    val okColor = Color(0xFF4CC38A)
    val warnColor = Color(0xFFE0A030)
    val onDark = Color.White
    val dim = Color(0xFFA9B3C4)

    Column(Modifier.fillMaxSize().background(Color(0xFF0E1420))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackBtn(onClick = onClose, tint = onDark)
            Column(Modifier.weight(1f)) {
                Text("${cur.no} · ${cur.name}", color = onDark, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (label, col) = when {
                    cur.retake -> "Tekrar çekilmeli" to warnColor
                    cur.captured -> "Çekildi" to okColor
                    else -> "Fotoğraf yok" to dim
                }
                Text(label, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${page + 1} / $total", color = dim, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp))
        }
        LinearProgressIndicator(
            progress = { (page + 1f) / total },
            color = MaterialTheme.colorScheme.primary, trackColor = Color(0xFF232D3F),
            modifier = Modifier.fillMaxWidth()
        )
        HorizontalPager(
            state = pager,
            pageSpacing = 16.dp,
            key = { list[it.coerceIn(0, total - 1)].no },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { pg ->
            val s = list[pg.coerceIn(0, total - 1)]
            val thumb = rememberThumb(classId, s)
            Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(), contentDescription = s.name,
                        contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (s.captured) {
                    CircularProgressIndicator(color = dim)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📷", fontSize = 48.sp)
                        Text("Henüz fotoğraf çekilmemiş", color = dim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        Text(
            if (cur.captured) "e-Okul çıktısı · ${TARGET_W}×${TARGET_H} px · kaydırarak gezin" else "Kaydırarak gezin",
            color = dim, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton(
                text = if (cur.captured) "Yeniden çek" else "Fotoğraf çek",
                onClick = { onClose(); studio.openCamera(classId, cur.no) },
                style = BtnStyle.Primary, dark = true,
                icon = if (cur.captured) Icons.Filled.Refresh else null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            )
            if (cur.captured) {
                AppButton(
                    text = "Kırpmayı düzenle",
                    onClick = { onClose(); studio.editSavedCrop(classId, cur.no) },
                    style = BtnStyle.Secondary, dark = true,
                    icon = Icons.Filled.Edit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = BtnGap) {
                AppButton(
                    "Önceki", onClick = { scope.launch { pager.animateScrollToPage(page - 1) } },
                    enabled = page > 0, style = BtnStyle.Secondary, dark = true,
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft, modifier = Modifier.weight(1f)
                )
                AppButton(
                    "Sonraki", onClick = { scope.launch { pager.animateScrollToPage(page + 1) } },
                    enabled = page < total - 1, style = BtnStyle.Secondary, dark = true,
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
