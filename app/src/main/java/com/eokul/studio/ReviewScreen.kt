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
    var actualSize by remember { mutableStateOf(false) }
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
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (actualSize) {
                                // %100: çıktının her pikseli ekranda bir fiziksel piksel (e-Okul'da görüneceği gerçek boyut)
                                val dens = androidx.compose.ui.platform.LocalDensity.current
                                Image(
                                    bitmap = pb.asImageBitmap(),
                                    contentDescription = "Önizleme (gerçek boyut)",
                                    filterQuality = FilterQuality.None,
                                    modifier = Modifier.size(with(dens) { TARGET_W.toDp() }, with(dens) { TARGET_H.toDp() })
                                )
                                Text(
                                    "Gerçek boyut (%100): ${TARGET_W}×${TARGET_H} piksel, büyütme yok",
                                    color = Dim, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter)
                                )
                            } else {
                                Image(
                                    bitmap = pb.asImageBitmap(),
                                    contentDescription = "Önizleme",
                                    filterQuality = FilterQuality.High,
                                    modifier = Modifier.fillMaxHeight().aspectRatio(TARGET_W.toFloat() / TARGET_H).clip(RoundedCornerShape(10.dp))
                                )
                            }
                            AppButton(
                                if (actualSize) "Büyük göster" else "%100 gerçek boyut",
                                onClick = { actualSize = !actualSize },
                                style = BtnStyle.Secondary, dark = true, compact = true,
                                modifier = Modifier.align(Alignment.BottomEnd)
                            )
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        if (s != null && !cap.loading) {
            // Uyarılar iki türdür. AĞIR (yüz yok, bulanık, çok karanlık, baş/çene kesik, eğik…): yeniden çekim önerilir,
            // otomatik kaydı durdurur. BİLGİ (ışık dengesi, hafif kadraj sapması): görünür ama fotoğrafı "yeniden çekilmeli"
            // işaretlemez ve otomatik kaydı engellemez; yalnızca puanı hafif düşürür.
            val warnings = ArrayList<String>()
            var hardCount = 0
            var frameIssues = 0
            fun hard(msg: String, frame: Boolean = false) {
                warnings.add(msg)
                hardCount++
                if (frame) frameIssues++
            }
            fun soft(msg: String, frame: Boolean = false) {
                warnings.add(msg)
                if (frame) frameIssues++
            }
            if (s.faces.isEmpty()) hard("⚠ Yüz bulunamadı — alanı elle ayarlayın veya yeniden çekin.", true)
            if (s.faces.size > 1) soft("⚠ ${s.faces.size} yüz algılandı — en büyüğü seçildi. Doğruluğunu kontrol edin.", true)
            if (s.box.width < TARGET_W) hard("⚠ Kırpılan alan hedef çözünürlükten küçük; görüntü bulanık olabilir.", true)
            val q = quality
            if (q != null) {
                if (q.blurry()) hard("⚠ Fotoğraf bulanık görünüyor — yeniden çekmeniz önerilir.")
                if (q.dark()) hard("⚠ Fotoğraf çok karanlık — daha aydınlık bir yerde çekin.")
                if (q.bright()) hard("⚠ Fotoğraf aşırı parlak — ışığı azaltın.")
                if (q.flat() && !q.dark() && !q.bright()) soft("⚠ Fotoğraf soluk / düşük kontrastlı — yüzü ve arka planı ayıran daha iyi ışık kullanın.")
            }
            var lightCode = com.eokul.studio.core.Lighting.OK
            val f0 = s.faces.firstOrNull()
            if (f0 != null) {
                // Işık dengesi: yüz ile arka plan ve yüzün iki yarısı karşılaştırılır (canlı kameradaki ölçümle aynı mantık)
                val light = androidx.compose.runtime.remember(s.pixels, f0) {
                    com.eokul.studio.core.Lighting.analyzeScaled(s.pixels, s.iw, s.ih, f0.box.x1, f0.box.y1, f0.box.x2, f0.box.y2)
                }
                lightCode = light?.code ?: com.eokul.studio.core.Lighting.OK
                when (lightCode) {
                    com.eokul.studio.core.Lighting.BACKLIT ->
                        soft("ℹ Arka ışık: yüz karanlık kalmış — pencereye sırtınız dönük olmasın, ışığa doğru dönün.")
                    com.eokul.studio.core.Lighting.UNEVEN ->
                        soft("ℹ Yüze tek yandan ışık vuruyor — ışığa doğru dönmek daha iyi olur.")
                    com.eokul.studio.core.Lighting.GLARE ->
                        soft("ℹ Yüzde parlama / patlak bölge var — doğrudan ışıktan uzaklaşın.")
                }
                for (code in com.eokul.studio.core.Framing.check(f0.box.toArray(), f0.headTop, f0.eyes, s.box.toArray())) {
                    val isHard = code == com.eokul.studio.core.Framing.HEAD_CUT || code == com.eokul.studio.core.Framing.CHIN_CUT
                    val msg =
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
                    if (isHard) hard(msg, true) else soft(msg, true)
                }
                if (Math.abs(f0.roll) > 10f) hard("⚠ Baş yana eğik (${Math.abs(f0.roll).toInt()}°) — başı dik tutturun.")
                if (Math.abs(f0.yaw) > 15f) hard("⚠ Yüz kameraya dönük değil (${Math.abs(f0.yaw).toInt()}°) — karşıdan çekin.")
            }
            androidx.compose.runtime.LaunchedEffect(hardCount) { cap.warned = hardCount > 0 }
            val f1 = s.faces.firstOrNull()
            val score = if (q != null && f1 != null) {
                // Kadraj sapması dereceli: sorun yoksa 0, her ek sorun biraz daha düşürür (önceden herhangi bir uyarı −15 puandı)
                val dev = when (frameIssues) { 0 -> 0.0; 1 -> 0.7; 2 -> 1.3; else -> 2.0 }
                val base = Score.total(
                    dev, q.blurRatio,
                    Math.abs(f1.roll).toDouble(), Math.abs(f1.yaw).toDouble(), q.brightness, q.contrast, -1.0
                )
                com.eokul.studio.core.Burst.total(base, true, -1.0, -1.0, lightCode)
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
                cap.error == null && !cap.saving && score >= studio.settings.autoAcceptScore && hardCount == 0
            LaunchedEffect(autoPending) {
                if (autoPending) {
                    delay(700)
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
