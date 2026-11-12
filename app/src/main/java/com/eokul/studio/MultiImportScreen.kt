package com.eokul.studio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eokul.studio.core.FlatList
import com.eokul.studio.core.RosterParser
import com.eokul.studio.core.SheetReader

private val OkGreen = androidx.compose.ui.graphics.Color(0xFF3C9A5F)
private val WarnAmber = androidx.compose.ui.graphics.Color(0xFFE08A00)

/**
 * Çok şubeli e-Okul dosyasından (6A, 6B, 6C… alt alta) tek seferde birden çok sınıf ekler.
 * Şubeler S.No'nun yeniden 1'e dönmesinden ve "Öğrenci Sayısı" alt satırlarından tanınır; adları sınıf düzeyi + harf olarak verilir.
 */
@Composable
fun MultiImportScreen(studio: StudioViewModel) {
    BackHandler { studio.back() }
    val ctx = LocalContext.current
    var fileName by remember { mutableStateOf<String?>(null) }
    var blocks by remember { mutableStateOf<List<SheetReader.Block>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var grade by remember { mutableStateOf("") }
    var sep by remember { mutableStateOf("") }
    var startLetter by remember { mutableStateOf("A") }
    var titleCase by remember { mutableStateOf(false) }
    // Dosyada "6. Sınıf A Şubesi" başlığı varsa sınıf adı oradan alınabilir; yoksa elle (düzey + harf).
    var useTitles by remember { mutableStateOf(false) }
    val overrides = remember { mutableStateMapOf<Int, String>() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    error = "Dosya boş"
                } else if (!SheetReader.looksLikeExcel(bytes)) {
                    error = "Bu ekran Excel (.xls / .xlsx) dosyası ister. Tek sınıf için sınıfın içindeki “Listeyi yapıştır” ekranını kullanın."
                } else {
                    val out = FlatList.detect(bytes)
                    if (out == null) {
                        error = "Dosyada öğrenci numarası ve ad soyad sütunları bulunamadı. Beklenen: No, Ad Soyad, Sınıf (başlıklı veya başlıksız) ya da e-Okul sınıf listesi."
                    } else {
                        val b = out.blocks
                        blocks = b
                        info = out.info
                        notes = out.notes
                        overrides.clear()
                        // Şube başlığı ("6. Sınıf A Şubesi") veya düz listedeki sınıf adı varsa oradan alınır.
                        val t0 = b.indexOfFirst { it.titleGrade != null }
                        useTitles = b.any { it.titleGrade != null || it.label != null }
                        if (t0 >= 0) {
                            grade = b[t0].titleGrade ?: grade
                            val ch = b[t0].titleBranch?.firstOrNull()
                            if (ch != null && ch in 'A'..'Z' && ch - t0 >= 'A') startLetter = (ch - t0).toString()
                        }
                        error = null
                        fileName = uri.lastPathSegment?.substringAfterLast('/')
                    }
                }
            } catch (e: Throwable) {
                error = ("Dosya okunamadı: ${e.message ?: e.javaClass.simpleName}").take(300)
            }
        }
    }

    fun autoName(i: Int): String {
        val c0 = startLetter.trim().firstOrNull()?.uppercaseChar() ?: 'A'
        val blk = blocks?.getOrNull(i)
        // Düz listelerde sınıf adı dosyadan gelir (düzey+şube ayrışmadıysa olduğu gibi)
        if (blk != null && useTitles && blk.label != null && (blk.titleGrade == null || blk.titleBranch == null)) return blk.label
        val fromTitle = if (useTitles) blk?.titleBranch else null
        val letter = fromTitle ?: if (c0 in 'A'..'Z' && c0 + i <= 'Z') (c0 + i).toString() else (i + 1).toString()
        val g = if (useTitles) (blk?.titleGrade ?: grade.trim()) else grade.trim()
        return g + sep + letter
    }

    val list = blocks
    val plans: List<List<RosterParser.Row>> = remember(list, titleCase) {
        list?.map { b ->
            val sb = StringBuilder()
            for (s in b.students) sb.append(s[0]).append('\t').append(s[1]).append('\n')
            RosterParser.parse(
                sb.toString(), RosterParser.Delimiter.TAB, false,
                if (titleCase) RosterParser.CaseMode.TITLE else RosterParser.CaseMode.KEEP
            ).valid()
        } ?: emptyList()
    }
    val names = list?.indices?.map { (overrides[it] ?: autoName(it)).trim() } ?: emptyList()

    // Denetimler
    val keys = names.map { RosterParser.sanitizeFileName(it).lowercase() }
    val emptyName = names.any { it.isEmpty() }
    val dupName = keys.size != keys.toSet().size
    val numberCount = HashMap<String, Int>()
    for (rows in plans) for (r in rows) numberCount[r.no] = (numberCount[r.no] ?: 0) + 1
    val dupNumbers = numberCount.filter { it.value > 1 }.keys.sorted()
    val total = plans.sumOf { it.size }
    val canImport = list != null && !emptyName && !dupName && total > 0

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackBtn(onClick = { studio.back() }, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f)) {
                    Text(
                        "Çoklu sınıf listesi", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Tek Excel dosyasından tüm sınıfları ve öğrencileri ekleyin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1 — Dosya
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1 · Dosya", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Desteklenen Excel (.xls / .xlsx) biçimleri:\n" +
                            "• Tüm okul tek listede: No · Ad Soyad · Sınıf (başlıklı veya başlıksız; sınıf “6A”, “6/A”, “6. Sınıf A Şubesi” olabilir, sınıf ve şube ayrı sütunda da olur)\n" +
                            "• Her sınıf ayrı sayfada (sekme adı 6A, 6B…)\n" +
                            "• e-Okul çok şubeli liste (S.No yeniden 1’den başlar / “Öğrenci Sayısı” alt satırı)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(if (list == null) "Excel dosyası seç" else "Başka dosya seç") }
                    if (list != null) {
                        Text(
                            "✓ ${fileName ?: "Dosya"} · ${list.size} şube, $total öğrenci bulundu",
                            color = OkGreen, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (list != null) info?.let {
                        Text("Biçim: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (list != null) notes.forEach { Text("ℹ $it", color = WarnAmber, style = MaterialTheme.typography.bodySmall) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            if (list != null) {
                // 2 — Adlandırma
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("2 · Sınıf adları", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        if (list.any { it.titleGrade != null || it.label != null }) {
                            Text("Ad kaynağı", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = useTitles, onClick = { useTitles = true }, label = { Text("Dosyadan") })
                                FilterChip(selected = !useTitles, onClick = { useTitles = false }, label = { Text("Elle gir") })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = grade, onValueChange = { grade = it.take(6) },
                                label = { Text("Sınıf düzeyi") }, placeholder = { Text("6") },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = startLetter, onValueChange = { startLetter = it.take(1).uppercase() },
                                label = { Text("İlk şube") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                        }
                        Text("Ayraç", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = sep == "", onClick = { sep = "" }, label = { Text("6A") })
                            FilterChip(selected = sep == "-", onClick = { sep = "-" }, label = { Text("6-A") })
                            FilterChip(selected = sep == " ", onClick = { sep = " " }, label = { Text("6 A") })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !titleCase, onClick = { titleCase = false }, label = { Text("Adlar aynen") })
                            FilterChip(selected = titleCase, onClick = { titleCase = true }, label = { Text("Ad Soyad (baş harf büyük)") })
                        }
                        Text(
                            if (list.any { it.titleGrade != null || it.label != null } && useTitles)
                                "Sınıf adları dosyadan alındı. Aşağıdan her adı elle değiştirebilirsiniz."
                            else if (grade.isBlank())
                                "Sınıf düzeyini yazın (ör. 6); şubeler ilk şubeden başlayarak sırayla A, B, C… adlandırılır. Düzey boş kalırsa adlar yalnızca harf olur."
                            else
                                "Şubeler ilk şubeden başlayarak sırayla adlandırılır. Aşağıdan her adı elle değiştirebilirsiniz.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 3 — Önizleme
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("3 · Önizleme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        list.forEachIndexed { i, b ->
                            val n = plans[i].size
                            val bad = b.students.size - n
                            val mm = b.mismatch()
                            val exists = studio.classes.any {
                                RosterParser.sanitizeFileName(it.name).lowercase() == keys[i]
                            }
                            val first = plans[i].firstOrNull()
                            val last = plans[i].lastOrNull()
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = overrides[i] ?: autoName(i),
                                        onValueChange = { overrides[i] = it.take(20) },
                                        singleLine = true, label = { Text("${i + 1}. şube") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("$n", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text("öğrenci", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (first != null && last != null) {
                                    Text(
                                        "No ${first.no} (${first.name}) → ${last.no} (${last.name})",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (exists) Text("Bu adla sınıf var: öğrenciler mevcut sınıfa eklenir / güncellenir.", color = WarnAmber, style = MaterialTheme.typography.bodySmall)
                                if (mm != null) Text("⚠ $mm", color = WarnAmber, style = MaterialTheme.typography.bodySmall)
                                else if (b.verified()) Text("✓ Öğrenci sayısı dosyadaki toplamla uyuşuyor.", color = OkGreen, style = MaterialTheme.typography.bodySmall)
                                if (bad > 0) Text("⚠ $bad satır geçersiz olduğu için atlandı.", color = WarnAmber, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (emptyName) Text("Boş sınıf adı olamaz.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (dupName) Text("İki şubeye aynı ad verilmiş.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        if (dupNumbers.isNotEmpty()) {
                            Text(
                                "⚠ Aynı öğrenci numarası birden çok şubede var: " + dupNumbers.take(5).joinToString(", ") +
                                    (if (dupNumbers.size > 5) " …" else "") + ". Dosyayı kontrol edin.",
                                color = WarnAmber, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
        }

        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { studio.back() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Vazgeç") }
                Button(
                    enabled = canImport,
                    modifier = Modifier.weight(2f).heightIn(min = 52.dp),
                    onClick = {
                        studio.importBranches(names.mapIndexed { i, n -> StudioViewModel.BranchPlan(n, plans[i]) })
                        studio.back()
                    }
                ) { Text(if (list == null) "Sınıfları ekle" else "${list.size} sınıfı ekle · $total öğrenci") }
            }
        }
    }
}
