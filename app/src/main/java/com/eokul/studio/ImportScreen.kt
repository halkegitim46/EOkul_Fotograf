package com.eokul.studio

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eokul.studio.core.RosterParser
import com.eokul.studio.core.RosterParser.CaseMode
import com.eokul.studio.core.RosterParser.Delimiter

private fun delimLabel(d: Delimiter): String = when (d) {
    Delimiter.AUTO -> "Otomatik algıla"
    Delimiter.TAB -> "Sekme"
    Delimiter.SEMICOLON -> "Noktalı virgül ( ; )"
    Delimiter.COMMA -> "Virgül ( , )"
    Delimiter.PIPE -> "Dikey çizgi ( | )"
    Delimiter.DASH -> "Tire ( - )"
    Delimiter.SPACE -> "Boşluk"
}

@Composable
fun ImportScreen(studio: StudioViewModel, classId: String?) {
    val ctx = LocalContext.current
    val cls = studio.classById(classId)
    var text by remember { mutableStateOf("") }
    var delim by remember { mutableStateOf(Delimiter.AUTO) }
    var surnameFirst by remember { mutableStateOf(false) }
    var caseMode by remember { mutableStateOf(CaseMode.KEEP) }
    var mode by remember { mutableStateOf(MergeMode.UPSERT) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var optionsOpen by remember { mutableStateOf(false) }
    var delimMenu by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    error = "Dosya boş"
                } else {
                    if (com.eokul.studio.core.SheetReader.looksLikeExcel(bytes)) {
                        text = com.eokul.studio.core.SheetReader.readEokulList(bytes) ?: com.eokul.studio.core.SheetReader.readText(bytes)
                        if (text.isBlank()) error = "Excel sayfasında liste bulunamadı"
                        else error = null
                    } else {
                        val t = decodeText(bytes)
                        text = if (com.eokul.studio.core.SheetReader.looksLikeHtml(t)) com.eokul.studio.core.SheetReader.htmlToText(t) else t
                        error = if (text.isBlank()) "Dosyada liste bulunamadı" else null
                    }
                }
            } catch (e: Throwable) {
                error = ("Dosya okunamadı: ${e.message ?: e.javaClass.simpleName}. " +
                    "Çözüm: dosyayı Excel'de açıp CSV olarak kaydedin veya hücreleri kopyalayıp \"Panodan yapıştır\" kullanın.").take(420)
            }
        }
    }

    val result = remember(text, delim, surnameFirst, caseMode) {
        if (text.isBlank()) null else RosterParser.parse(text, delim, surnameFirst, caseMode)
    }
    val valid = result?.valid() ?: emptyList()

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackBtn(onClick = { studio.back() }, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    if (cls != null) "${cls.name} · Liste ekle" else "Yeni sınıf · Liste ekle",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (cls == null) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it; error = null },
                    label = { Text("Sınıf adı") }, placeholder = { Text("örn. 9-A") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 1. Kaynak
            Section("1  Listeyi alın") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("Excel / CSV dosyası", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (t.isNullOrBlank()) error = "Panoda metin yok" else {
                                text = t
                                error = null
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("Panodan yapıştır", maxLines = 1) }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null },
                    label = { Text("Liste (isterseniz elle düzenleyin)") },
                    placeholder = { Text("12345\tAyşe Yılmaz\n12346\tMehmet Kaya") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 160.dp)
                )
                if (text.isNotEmpty()) {
                    TextButton(onClick = { text = "" }) { Text("Temizle") }
                }
            }

            // ---- 2. Seçenekler (özet + açılır)
            Section("2  Biçim") {
                val summary = buildString {
                    append("Ayraç: ")
                    append(if (delim == Delimiter.AUTO && result != null) "${delimLabel(result.detected)} (otomatik)" else delimLabel(delim))
                    if (surnameFirst) append(" · Soyad önce")
                    append(" · ")
                    append(when (caseMode) { CaseMode.KEEP -> "Harfler olduğu gibi"; CaseMode.TITLE -> "Ad Soyad"; CaseMode.UPPER -> "AD SOYAD" })
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { optionsOpen = !optionsOpen }.heightIn(min = 56.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(if (optionsOpen) "Gizle ▴" else "Değiştir ▾", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                        if (optionsOpen) {
                            HorizontalDivider()
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Sütun ayracı", style = MaterialTheme.typography.labelLarge)
                                Box {
                                    OutlinedButton(onClick = { delimMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(delimLabel(delim), modifier = Modifier.weight(1f))
                                        Text("▾")
                                    }
                                    DropdownMenu(expanded = delimMenu, onDismissRequest = { delimMenu = false }) {
                                        for (d in Delimiter.values()) {
                                            DropdownMenuItem(text = { Text(delimLabel(d)) }, onClick = { delim = d; delimMenu = false })
                                        }
                                    }
                                }
                                Text("Harf biçimi", style = MaterialTheme.typography.labelLarge)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((m, label) in listOf(CaseMode.KEEP to "Olduğu gibi", CaseMode.TITLE to "Ad Soyad", CaseMode.UPPER to "AD SOYAD")) {
                                        FilterChip(
                                            selected = caseMode == m, onClick = { caseMode = m },
                                            label = { Text(label, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().clickable { surnameFirst = !surnameFirst }.heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = surnameFirst, onCheckedChange = { surnameFirst = it })
                                    Column(Modifier.padding(start = 4.dp)) {
                                        Text("Soyad önce yazılmış", style = MaterialTheme.typography.bodyMedium)
                                        Text("Yılmaz Ayşe → Ayşe Yılmaz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 3. Mevcut listeyle birleştirme
            if (cls != null && cls.students.isNotEmpty()) {
                Section("3  Mevcut listeyle") {
                    val modes = listOf(
                        Triple(MergeMode.UPSERT, "Ekle ve adları güncelle", "Yeni öğrenciler eklenir, numarası aynı olanların adı güncellenir (önerilen)"),
                        Triple(MergeMode.ADD, "Yalnızca yeni ekle", "Mevcut öğrencilere dokunulmaz"),
                        Triple(MergeMode.REPLACE, "Listeyi değiştir", "Yeni listede olmayan öğrenciler bu sınıftan çıkarılır")
                    )
                    for ((m, title, desc) in modes) {
                        Row(
                            Modifier.fillMaxWidth().clickable { mode = m }.heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = mode == m, onClick = { mode = m })
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    desc, style = MaterialTheme.typography.bodySmall,
                                    color = if (m == MergeMode.REPLACE && mode == m) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ---- Önizleme
            if (result != null) {
                val title = if (cls != null && cls.students.isNotEmpty()) "4  Önizleme" else "3  Önizleme"
                Section(title) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${valid.size} öğrenci hazır", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (result.errorCount() > 0) {
                            Text("· ${result.errorCount()} satır okunamadı", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val errs = result.rows.filter { !it.ok() }
                    if (errs.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (r in errs.take(5)) {
                                    Text("Satır ${r.line}: ${r.error}", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                                }
                                if (errs.size > 5) Text("… ve ${errs.size - 5} hatalı satır daha", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (valid.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text("Okul no", modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Ad Soyad", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                                val limit = if (showAll) valid.size.coerceAtMost(300) else 6
                                for ((i, r) in valid.take(limit).withIndex()) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(r.no, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    if (i < limit - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                if (valid.size > 6) {
                                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                        Text(if (showAll) "Daha az göster" else "Tümünü göster (${valid.size})")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { studio.back() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Vazgeç") }
                Button(
                    enabled = valid.isNotEmpty(),
                    modifier = Modifier.weight(2f).heightIn(min = 52.dp),
                    onClick = {
                        val id = studio.importRoster(classId, newName, valid, mode)
                        if (id == null) {
                            error = if (cls == null) "Sınıf adı boş veya bu adla bir sınıf var" else "İçe aktarılamadı"
                        } else if (classId == null) {
                            studio.replace(Screen.ClassDetail(id))
                        } else {
                            studio.back()
                        }
                    }
                ) { Text(if (valid.isEmpty()) "İçe aktar" else "${valid.size} öğrenciyi içe aktar") }
            }
        }
    }
}

/** Numaralı, başlıklı bölüm: başlık + içerik, aralıklar tutarlı. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/** UTF-8 (BOM'lu olabilir); geçersizse Türkçe Excel CSV'lerinin kullandığı Windows-1254. */
private fun decodeText(bytes: ByteArray): String {
    return try {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        String(bytes, java.nio.charset.Charset.forName("windows-1254"))
    }
}
