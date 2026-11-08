package com.eokul.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh

import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.eokul.studio.core.Quality
import com.eokul.studio.core.Score
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler

private val Bg = Color(0xFF101014)
private val Warn = Color(0xFFE0AF68)
private val Dim = Color(0xFFB0B6C8)

@Composable
fun ReviewScreen(studio: StudioViewModel) {
    BackHandler { studio.discardCapture() }
    val cap = studio.capture
    if (cap == null) {
        Box(Modifier.fillMaxSize().background(Bg))
        return
    }
    val cls = studio.classById(cap.classId)
    val student = cls?.students?.firstOrNull { it.no == cap.no }
    val s = cap.session
    var autoStopped by remember(cap) { mutableStateOf(false) }
    val ctxR = androidx.compose.ui.platform.LocalContext.current
    val exifDir by produceState(-1, cap) {
        value = withContext(Dispatchers.IO) {
            try {
                ctxR.contentResolver.openInputStream(cap.source)?.use {
                    androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, -1)
                } ?: -1
            } catch (e: Throwable) { -1 }
        }
    }
    val preview = rememberPreview(s, studio.settings)
    val quality by produceState<Quality.Report?>(null, s, s?.box, s?.loading) {
        val ss = s
        val px = ss?.pixels
        if (ss == null || px == null || ss.loading) {
            value = null
        } else {
            val box = ss.box
            val iw = ss.iw
            val ih = ss.ih
            value = withContext(Dispatchers.Default) { ImageOps.quality(px, iw, ih, box) }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {
        Text(
            student?.name ?: cap.no, color = Color.White,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
        )
        Text(
            "${cap.no}  ·  ${cls?.name ?: ""}" + if (cap.fromSaved) "  ·  kayıtlı orijinalden" else "",
            color = Dim, fontSize = 12.sp
        )
        Spacer(Modifier.size(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val err = cap.error
            when {
                err != null -> Text("Fotoğraf açılamadı. Orijinal dosya taşınmış, silinmiş veya bozuk olabilir; fotoğrafı yeniden çekin.\n\nTeknik ayrıntı: $err", color = Warn, fontSize = 14.sp)
                cap.loading || s == null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(8.dp))
                        Text("Yüz algılanıyor…", color = Dim)
                    }
                }
                else -> {
                    val pb = preview?.first
                    if (pb != null) {
                        Image(
                            bitmap = pb.asImageBitmap(),
                            contentDescription = "Önizleme",
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxHeight().aspectRatio(TARGET_W.toFloat() / TARGET_H).clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        if (s != null && !cap.loading) {
            val warnings = ArrayList<String>()
            if (s.faces.isEmpty()) warnings.add("⚠ Yüz bulunamadı — alanı elle ayarlayın veya yeniden çekin.")
            if (s.faces.size > 1) warnings.add("⚠ ${s.faces.size} yüz algılandı — en büyüğü seçildi. Doğruluğunu kontrol edin.")
            if (s.box.width < TARGET_W) warnings.add("⚠ Kırpılan alan hedef çözünürlükten küçük; görüntü bulanık olabilir.")
            val q = quality
            if (q != null) {
                if (q.blurry()) warnings.add("⚠ Fotoğraf bulanık görünüyor — yeniden çekmeniz önerilir.")
                if (q.dark()) warnings.add("⚠ Fotoğraf çok karanlık — daha aydınlık bir yerde çekin.")
                if (q.bright()) warnings.add("⚠ Fotoğraf aşırı parlak — ışığı azaltın.")
                if (q.flat() && !q.dark() && !q.bright()) warnings.add("⚠ Fotoğraf soluk / düşük kontrastlı — yüzü ve arka planı ayıran daha iyi ışık kullanın.")
            }
            val f0 = s.faces.firstOrNull()
            if (f0 != null) {
                for (code in com.eokul.studio.core.Framing.check(f0.box.toArray(), f0.headTop, f0.eyes, s.box.toArray())) {
                    warnings.add(
                        when (code) {
                            com.eokul.studio.core.Framing.HEAD_CUT -> "⚠ Başın üst kısmı kadraj dışında kalıyor — kırpma alanını yukarı taşıyın (✂ Düzenle)."
                            com.eokul.studio.core.Framing.HEAD_TIGHT -> "⚠ Baş üstü boşluğu çok az — kırpma alanını biraz yukarı alın."
                            com.eokul.studio.core.Framing.CHIN_CUT -> "⚠ Çene kadrajın alt kenarına çok yakın — kırpma alanını aşağı alın."
                            com.eokul.studio.core.Framing.FACE_SMALL -> "⚠ Yüz kadrajda küçük kalıyor — kameraya yaklaşın veya kırpmayı daraltın."
                            com.eokul.studio.core.Framing.FACE_LARGE -> "⚠ Yüz kadrajı fazla dolduruyor — biraz uzaklaşın veya kırpmayı genişletin."
                            com.eokul.studio.core.Framing.OFF_CENTER -> "⚠ Yüz yatayda ortalı değil — kırpma alanını yüze doğru kaydırın."
                            com.eokul.studio.core.Framing.EYE_LEVEL -> "⚠ Göz hizası beklenen aralığın dışında — kadrajı kontrol edin."
                            else -> "⚠ Göz çizgisi eğik — başı dik tutturup tekrar çekin."
                        }
                    )
                }
                if (Math.abs(f0.roll) > 10f) warnings.add("⚠ Baş yana eğik (${Math.abs(f0.roll).toInt()}°) — başı dik tutturun.")
                if (Math.abs(f0.yaw) > 15f) warnings.add("⚠ Yüz kameraya dönük değil (${Math.abs(f0.yaw).toInt()}°) — karşıdan çekin.")
            }
            androidx.compose.runtime.LaunchedEffect(warnings.size) { cap.warned = warnings.isNotEmpty() }
            val f1 = s.faces.firstOrNull()
            val score = if (q != null && f1 != null) {
                Score.total(
                    if (warnings.isEmpty()) 0.0 else 1.5, q.blurRatio,
                    Math.abs(f1.roll).toDouble(), Math.abs(f1.yaw).toDouble(), q.brightness, q.contrast, -1.0
                )
            } else -1
            androidx.compose.runtime.LaunchedEffect(score) { if (score >= 0) cap.score = score }
            val kbNow = (preview?.second ?: 0) / 1024.0
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (score >= 0) {
                    val col = when {
                        score >= 80 -> Color(0xFF4CC38A)
                        score >= 60 -> Warn
                        else -> Color(0xFFE5645F)
                    }
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1B2232))
                            .padding(start = 10.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                        Text(
                            score.toString(), color = col, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Text(
                            Score.label(score), color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                } else {
                    Text("Kalite hesaplanıyor…", color = Dim, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("${TARGET_W}×${TARGET_H} px · ${"%.1f".format(kbNow)} KB · yön $exifDir", color = Dim, fontSize = 12.sp)
            }
            if (warnings.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(max = 112.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2415))
                        .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    for (w in warnings) Text(w, color = Warn, fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            val autoPending = studio.settings.autoAccept && !cap.fromSaved && !autoStopped &&
                cap.error == null && !cap.saving && score >= studio.settings.autoAcceptScore && warnings.isEmpty()
            LaunchedEffect(autoPending) {
                if (autoPending) {
                    delay(1200)
                    studio.saveCapture()
                }
            }
            if (autoPending) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "✓ İyi çekim — otomatik kaydediliyor…", color = Color(0xFF9ECE6A),
                        fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                    AppButton("Durdur", onClick = { autoStopped = true }, style = BtnStyle.Danger, dark = true, compact = true)
                }
            }
        }

        Spacer(Modifier.size(10.dp))
        val ready = s != null && !cap.loading && !cap.saving
        // Ana eylem tek başına, en üstte ve tam genişlikte; yan eylemler altında eşit genişlikte.
        AppButton(
            text = if (cap.saving) "Kaydediliyor…" else if (cap.warned) "Yine de kaydet" else "Kaydet",
            onClick = { studio.saveCapture() }, enabled = ready, style = BtnStyle.Primary, dark = true,
            icon = if (cap.saving) null else Icons.Filled.Check,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        )
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = BtnGap) {
            AppButton(
                text = "Kırpmayı düzenle", onClick = { studio.openCaptureEditor() }, enabled = ready,
                style = BtnStyle.Secondary, dark = true, icon = Icons.Filled.Edit,
                modifier = Modifier.weight(1f)
            )
            AppButton(
                text = if (cap.fromSaved) "İptal" else "Yeniden çek", onClick = { studio.discardCapture() },
                enabled = !cap.saving, style = BtnStyle.Danger, dark = true,
                icon = if (cap.fromSaved) Icons.Filled.Close else Icons.Filled.Refresh,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
