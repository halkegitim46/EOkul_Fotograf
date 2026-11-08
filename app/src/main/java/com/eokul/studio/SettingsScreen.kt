package com.eokul.studio

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: AppViewModel, studio: StudioViewModel, showRecrop: Boolean, onClose: () -> Unit) {
    BackHandler { onClose() }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var pinOpen by remember { mutableStateOf(false) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) confirmRestore = uri
    }
    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) studio.setRootFolder(uri)
    }
    val cur = vm.settings
    val def = Settings()

    var topGap by remember { mutableStateOf(cur.topGap.toFloat()) }
    var headFrac by remember { mutableStateOf(cur.headFrac.toFloat()) }
    var quality by remember { mutableStateOf(cur.jpegQuality.toFloat()) }
    var minKb by remember { mutableStateOf(cur.minKb.toString()) }
    var regex by remember { mutableStateOf(cur.nameRegex) }
    var outDir by remember { mutableStateOf(cur.outputDir) }
    var embed by remember { mutableStateOf(cur.embedSrgb) }
    var serial by remember { mutableStateOf(cur.serialMode) }
    var autoTol by remember { mutableStateOf(cur.autoTol.toFloat()) }
    var autoHold by remember { mutableStateOf(cur.autoHoldMs.toFloat()) }
    var smart by remember { mutableStateOf(cur.smartShot) }
    var flipCap by remember { mutableStateOf(cur.flipCapture) }
    var flipFront by remember { mutableStateOf(cur.flipFront) }
    var autoAcc by remember { mutableStateOf(cur.autoAccept) }
    var accScore by remember { mutableStateOf(cur.autoAcceptScore.toFloat()) }
    var keepOrig by remember { mutableStateOf(cur.keepOriginals) }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): Settings? {
        val kb = minKb.trim().toIntOrNull()
        val dir = outDir.trim()
        val rx = regex.trim()
        val problem: String? = when {
            kb == null || kb !in 1..500 -> "Minimum boyut 1–500 KB arasında olmalı"
            dir.isEmpty() -> "Çıktı klasörü adı boş olamaz"
            dir.any { it in "\\/:*?\"<>|" } || dir == "." || dir == ".." -> "Çıktı klasörü adı geçersiz karakter içeriyor"
            else -> try {
                Regex(rx)
                null
            } catch (e: Exception) {
                "Dosya adı kuralı (regex) geçersiz"
            }
        }
        error = problem
        if (problem != null || kb == null) return null
        return vm.settings.copy(
            serialMode = serial,
            autoTol = (autoTol * 100.0).roundToInt() / 100.0,
            autoHoldMs = ((autoHold / 100f).roundToInt() * 100).coerceIn(400, 4000),
            keepOriginals = keepOrig,
            smartShot = smart,
            flipCapture = flipCap,
            flipFront = flipFront,
            autoAccept = autoAcc,
            autoAcceptScore = accScore.roundToInt().coerceIn(60, 95),
            topGap = (topGap * 100.0).roundToInt() / 100.0,
            headFrac = (headFrac * 100.0).roundToInt() / 100.0,
            jpegQuality = quality.roundToInt(),
            minKb = kb,
            embedSrgb = embed,
            nameRegex = rx,
            outputDir = dir
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackBtn(onClick = onClose)
            Text("Ayarlar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(8.dp))

        // ---------------- Kayıt (sınıf akışı)
        SettingsCard("Genel", "Kayıt klasörü ve çekim akışı") {
        Text(
            "Kayıt klasörü: ${studio.rootName ?: "seçilmedi"}",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.size(4.dp))
        OutlinedButton(onClick = { rootPicker.launch(null) }) { Text("Klasör seç / değiştir") }
        Text(
            "Yapı: Sınıf/12345-Ad Soyad.jpg · Sınıf/Orijinaller/12345-Ad Soyad.jpg",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = serial, onCheckedChange = { serial = it })
            Text("  Seri çekim: kayıttan sonra sıradaki öğrenciye geç", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = keepOrig, onCheckedChange = { keepOrig = it })
            Text("  Tam çözünürlüklü orijinali sakla", style = MaterialTheme.typography.bodyMedium)
        }

        }
        SettingsCard("Çekim", "Akıllı çekim, otomatik çekim ve hızlı onay") {
        val tolText = when {
            autoTol < 0.85f -> "sıkı"
            autoTol > 1.2f -> "gevşek"
            else -> "normal"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = smart, onCheckedChange = { smart = it })
            Text("  Akıllı çekim", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Gözler kapalıysa, görüntü net değilse veya telefon sallanıyorsa çekimi bekletir; her çekimde 3 kare alıp en netini seçer (çekim yaklaşık 1 sn sürer).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = flipCap, onCheckedChange = { flipCap = it })
            Text("  Arka kamera: sağ-sol çevir", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = flipFront, onCheckedChange = { flipFront = it })
            Text("  Ön kamera: sağ-sol çevir", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Fotoğraf çekimden sonra ters (aynalı) görünüyorsa, o kamera için açın. Ön ve arka kamera ayrı ayrı ayarlanır. Yalnızca kamerayla çekilenleri etkiler, galeriden seçilenleri değil.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoAcc, onCheckedChange = { autoAcc = it })
            Text("  Hızlı onay: iyi çekimi otomatik kaydet", style = MaterialTheme.typography.bodyMedium)
        }
        if (autoAcc) {
            Text("Otomatik kayıt için en düşük kalite puanı: ${accScore.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = accScore, onValueChange = { accScore = it }, valueRange = 60f..95f)
        }
        Text(
            "Uyarısı olmayan ve puanı eşiği geçen fotoğraf, 1 sn içinde onay beklemeden kaydedilir ve sıradaki öğrenciye geçilir (\"Durdur\" ile engellenebilir). Uyarılı veya düşük puanlı fotoğrafta onay ekranı açık kalır.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Text("Yüz konumu toleransı: $tolText (${(autoTol * 100).roundToInt()}%)", style = MaterialTheme.typography.bodyMedium)
        Slider(value = autoTol, onValueChange = { autoTol = it }, valueRange = 0.6f..1.6f)
        Text(
            "Sıkı: yüzün kılavuza çok iyi oturması gerekir. Gevşek: biraz sapmada bile çeker. Öğrenciler çok kıpır kıpırsa gevşetin.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Text("Sabit bekleme süresi: ${"%.1f".format(autoHold / 1000f)} sn", style = MaterialTheme.typography.bodyMedium)
        Slider(value = autoHold, onValueChange = { autoHold = it }, valueRange = 500f..3000f)
        Text(
            "Yüz hazır olduktan sonra çekimden önce beklenen süre (halka dolarken).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        }
        SettingsCard("Kırpma", "Baş konumu ve boyutu (e-Okul 133×171)") {
        Text("Saç üstü boşluk: %${(topGap * 100).roundToInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = topGap, onValueChange = { topGap = it }, valueRange = 0.01f..0.10f, steps = 8)
        Text(
            "Saçın tepesi ile fotoğrafın üst kenarı arasındaki sabit boşluk (kırpma yüksekliğinin oranı). " +
                "Tüm öğrencilerde aynı oranda uygulanır.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        Text("Baş boyutu: %${(headFrac * 100).roundToInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = headFrac, onValueChange = { headFrac = it }, valueRange = 0.50f..0.75f, steps = 24)
        Text(
            "Saç tepesi ile çene arasındaki mesafenin kırpmaya oranı. Yüz çok büyük çıkıyorsa azaltın, " +
                "küçük kalıyorsa artırın.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(6.dp))
        OutlinedButton(onClick = {
            topGap = def.topGap.toFloat()
            headFrac = def.headFrac.toFloat()
        }) { Text("Kırpma varsayılanları") }

        }
        SettingsCard("Çıktı ve dışa aktarma", "JPEG kalitesi, dosya adı denetimi, renk profili") {
        Text("JPEG kalitesi: ${quality.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = quality, onValueChange = { quality = it }, valueRange = 70f..100f, steps = 29)
        Text(
            "JPEG kalitesi sıkıştırma düzeyidir (100 = en az kayıp, dosya biraz büyür). Renk alt örneklemesi (4:4:4) bundan bağımsızdır: uygulamada her zaman tam çözünürlükte yazılır. 300 DPI de her zaman yazılır.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "▾ Gelişmiş çıktı ayarlarını gizle" else "▸ Gelişmiş çıktı ayarları (boyut, klasör adı, dosya adı kuralı)") }
        if (advanced) {
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = minKb, onValueChange = { minKb = it },
            label = { Text("Minimum dosya boyutu (KB)") }, singleLine = true,
            supportingText = { Text("Küçük dosyaları reddeden sistemler için: dosya bu boyuta kadar JPEG yorum bölümüyle doldurulur (görüntü ayrıntısı artmaz). Gerekmiyorsa 1 yapın.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = outDir, onValueChange = { outDir = it },
            label = { Text("Çıktı klasörü adı") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = regex, onValueChange = { regex = it },
            label = { Text("e-Okul dosya adı kuralı (regex)") }, singleLine = true,
            supportingText = { Text("Örn: ^\\d{5,11}$  (5–11 haneli sayı) — kaydetmeden önce denetlenir") },
            modifier = Modifier.fillMaxWidth()
        )
        }
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = embed, onCheckedChange = { embed = it })
            Text(
                "  🎨  Çıktıya sRGB renk profili göm (önerilir)",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        }
        SettingsCard("Güvenlik ve yedek", "PIN kilidi ve yedekleme") {
        OutlinedButton(onClick = { pinOpen = true }) {
            Text(if (vm.settings.pinHash != null) "🔒 PIN: açık (değiştir / kaldır)" else "🔓 PIN kilidi belirle")
        }
        Text(
            "PIN açıkken uygulama başlarken ve 30 sn’den uzun arka planda kalınca PIN sorulur. Son uygulamalar ekranında içerik gizlenir ve ekran görüntüsü alınamaz.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { studio.backup() }) { Text("💾 Yedekle") }
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                Text("♻ Geri yükle")
            }
        }
        Text(
            "Yedek: sınıf listeleri ve küçük fotoğraflar kayıt klasörüne Yedek-tarih.zip olarak yazılır ve yazıldıktan sonra doğrulanır. Tam çözünürlüklü orijinaller yedeğe girmez.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        }

        error?.let {
            Spacer(Modifier.size(10.dp))
            Text("⚠ $it", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.size(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    build()?.let {
                        vm.updateSettings(it)
                        onClose()
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Kaydet") }
            Button(
                onClick = {
                    build()?.let {
                        vm.updateSettings(it)
                        onClose()
                        vm.recropUnedited()
                    }
                },
                enabled = showRecrop && vm.items.isNotEmpty() && !vm.busy(),
                modifier = Modifier.weight(1.4f)
            ) { Text("Kaydet ve yeniden kırp") }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "“Yeniden kırp” yalnızca elle düzenlemediğiniz fotoğrafları günceller; düzenledikleriniz korunur.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(20.dp))
        val appCtx = androidx.compose.ui.platform.LocalContext.current
        val version = remember {
            try { appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        }
        Text(
            "E-Okul Foto Studio" + if (version.isNotEmpty()) " · Sürüm $version" else "",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            "Copyright © 2026 Hacimd. All rights reserved.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.size(24.dp))
    }

    if (pinOpen) PinDialog(studio, vm.settings.pinHash != null) { pinOpen = false }

    val rs = confirmRestore
    if (rs != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Yedekten geri yüklensin mi?") },
            text = { Text("Uygulamadaki tüm sınıf listeleri ve küçük fotoğraflar yedekteki ile değiştirilir.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    studio.restore(rs)
                }) { Text("Geri yükle") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Vazgeç") } }
        )
    }
}

/** Ayarlar bölümü: başlık, kısa açıklama ve içerik tek, sade kartta. */
@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(10.dp))
            content()
        }
    }
}
