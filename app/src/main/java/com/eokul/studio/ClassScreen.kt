package com.eokul.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Filter { ALL, MISSING, DONE, RETAKE, LOW }

/** Bu puanın altındaki kayıtlar "düşük kalite" sayılır. */
private const val LOW_SCORE = 70

private fun Student.isLow() = captured && score in 0 until LOW_SCORE

@Composable
fun ClassScreen(studio: StudioViewModel, classId: String) {
    val cls = studio.classById(classId)
    if (cls == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var actionFor by remember { mutableStateOf<Student?>(null) }      // çekilmiş öğrenciye dokunma
    var renameFor by remember { mutableStateOf<Student?>(null) }
    var deleteFor by remember { mutableStateOf<Student?>(null) }
    var clearFor by remember { mutableStateOf<Student?>(null) }
    var alsoFiles by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val q = query.trim().lowercase()
    val shown = cls.students.filter { s ->
        (filter == Filter.ALL || (filter == Filter.MISSING && !s.captured) || (filter == Filter.DONE && s.captured) || (filter == Filter.RETAKE && s.retake) || (filter == Filter.LOW && s.isLow())) &&
            (q.isEmpty() || s.no.contains(q) || s.name.lowercase().contains(q))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp) {
                Column(
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        .fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BackBtn(onClick = { studio.back() }, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text(
                                cls.name, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "${cls.capturedCount}/${cls.students.size} fotoğraf çekildi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        TextButton(onClick = { addOpen = true }) { Text("＋ Öğrenci") }
                        TextButton(onClick = { studio.navigate(Screen.Import(cls.id)) }) { Text("📋 Liste") }
                        Box {
                            MoreBtn(onClick = { toolsOpen = true }, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                                DropdownMenuItem(text = { Text("Kontrol sayfası (ızgara)") }, onClick = {
                                    toolsOpen = false
                                    studio.navigate(Screen.Sheet(cls.id))
                                })
                                DropdownMenuItem(text = { Text("Eksik listesini paylaş") }, onClick = {
                                    toolsOpen = false
                                    val missing = cls.students.filter { !it.captured }
                                    if (missing.isEmpty()) {
                                        studio.toast("Eksik öğrenci yok")
                                    } else {
                                        val body = cls.name + " — fotoğrafı eksik öğrenciler (" + missing.size + ")\n" +
                                            missing.joinToString("\n") { it.no + "  " + it.name }
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, body)
                                        ctx.startActivity(Intent.createChooser(send, "Eksik listesi"))
                                    }
                                })
                                DropdownMenuItem(text = { Text("e-Okul ZIP oluştur (numara adlı)") }, onClick = {
                                    toolsOpen = false
                                    studio.exportEokulZip(cls.id)
                                })
                                val stale = studio.staleNameCount(cls.id)
                                if (stale > 0) {
                                    DropdownMenuItem(text = { Text("Dosya adlarını güncelle ($stale)") }, onClick = {
                                        toolsOpen = false
                                        studio.syncFileNames(cls.id)
                                    })
                                }
                            }
                        }
                    }
                    val total = cls.students.size
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else cls.capturedCount.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    )
                    val scored = cls.students.filter { it.captured && it.score >= 0 }
                    if (scored.isNotEmpty()) {
                        val avg = scored.map { it.score }.average().toInt()
                        val low = cls.students.count { it.isLow() }
                        Text(
                            "Kalite ortalaması $avg" + if (low > 0) " · $low düşük puanlı" else " · düşük puanlı yok",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (low > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("Ara (numara veya ad)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == Filter.ALL, onClick = { filter = Filter.ALL },
                    label = { Text("Hepsi ${cls.students.size}") }
                )
                FilterChip(
                    selected = filter == Filter.MISSING, onClick = { filter = Filter.MISSING },
                    label = { Text("Bekleyen ${cls.students.size - cls.capturedCount}") }
                )
                FilterChip(
                    selected = filter == Filter.DONE, onClick = { filter = Filter.DONE },
                    label = { Text("Çekilen ${cls.capturedCount}") }
                )
                val retakeCount = cls.students.count { it.retake }
                if (retakeCount > 0) FilterChip(
                    selected = filter == Filter.RETAKE, onClick = { filter = Filter.RETAKE },
                    label = { Text("🔁 Tekrar $retakeCount") }
                )
                if (retakeCount > 0 && filter == Filter.RETAKE) FilterChip(
                    selected = false, onClick = { studio.clearRetakeFlags(cls.id); filter = Filter.ALL },
                    label = { Text("✕ Tüm işaretleri temizle") }
                )
                val lowCount = cls.students.count { it.isLow() }
                if (lowCount > 0) FilterChip(
                    selected = filter == Filter.LOW, onClick = { filter = Filter.LOW },
                    label = { Text("⚠ Düşük $lowCount") }
                )
            }

            if (cls.students.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Bu sınıfta öğrenci yok", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { studio.navigate(Screen.Import(cls.id)) }) { Text("📋 Listeyi yapıştır") }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(shown, key = { it.no }) { s ->
                        StudentRow(
                            cls = cls, s = s,
                            onClick = {
                                if (s.captured) actionFor = s else studio.openCamera(cls.id, s.no)
                            },
                            onCamera = { studio.openCamera(cls.id, s.no) },
                            onRename = { renameFor = s },
                            onDelete = { deleteFor = s },
                            onClear = { clearFor = s },
                            onToggleRetake = { studio.toggleRetake(cls.id, s.no) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        if (cls.students.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { studio.startSerial(cls.id) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Text("📷 Seri çekim") }
        }
    }

    val act = actionFor
    if (act != null) {
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text("${act.no} · ${act.name}") },
            text = { Text("Bu öğrencinin fotoğrafı zaten çekilmiş. Ne yapmak istersiniz?") },
            confirmButton = {
                androidx.compose.foundation.layout.Column(
                    Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppButton(
                        "Kırpmayı düzenle", onClick = { actionFor = null; studio.editSavedCrop(cls.id, act.no) },
                        style = BtnStyle.Primary, icon = Icons.Filled.Edit,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppButton(
                        "Yeniden çek", onClick = { actionFor = null; studio.openCamera(cls.id, act.no) },
                        style = BtnStyle.Secondary, icon = Icons.Filled.Refresh,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppButton("Vazgeç", onClick = { actionFor = null }, style = BtnStyle.Tertiary, modifier = Modifier.fillMaxWidth())
                }
            }
        )
    }

    val rn = renameFor
    if (rn != null) {
        AddStudentDialog(
            title = "Öğrenciyi düzenle", confirmText = "Kaydet", initialNo = rn.no, initialName = rn.name,
            note = if (rn.captured) "Numara değişirse fotoğraf dosyaları da yeni numaraya göre yeniden adlandırılır." else null,
            onDismiss = { renameFor = null },
            onConfirm = { no, name ->
                val err = studio.editStudent(cls.id, rn.no, no, name)
                if (err == null) renameFor = null
                err
            }
        )
    }
    if (addOpen) {
        AddStudentDialog(
            onDismiss = { addOpen = false },
            onConfirm = { no, name ->
                val err = studio.addStudent(cls.id, no, name)
                if (err == null) addOpen = false
                err
            }
        )
    }
    val del = deleteFor
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Öğrenci listeden çıkarılsın mı?") },
            text = {
                Column {
                    Text("${del.no} · ${del.name}\nUygulamadaki kayıt ve küçük fotoğraf silinir.")
                    Row(
                        Modifier.fillMaxWidth().clickable { alsoFiles = !alsoFiles }.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(checked = alsoFiles, onCheckedChange = { alsoFiles = it })
                        Text("Kayıt klasöründeki fotoğraf dosyalarını da sil", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    studio.deleteStudent(cls.id, del.no, alsoFiles)
                    deleteFor = null
                    alsoFiles = false
                }) { Text("Çıkar") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Vazgeç") } }
        )
    }
    val clr = clearFor
    if (clr != null) {
        AlertDialog(
            onDismissRequest = { clearFor = null },
            title = { Text("Fotoğraf silinsin mi?") },
            text = { Text("${clr.no} · ${clr.name}\nSınıf klasöründeki ve Orijinaller’deki dosyalar silinir.") },
            confirmButton = {
                TextButton(onClick = {
                    studio.clearPhoto(cls.id, clr.no)
                    clearFor = null
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { clearFor = null }) { Text("Vazgeç") } }
        )
    }
}

@Composable
private fun StudentRow(
    cls: SchoolClass,
    s: Student,
    onClick: () -> Unit,
    onCamera: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onToggleRetake: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, cls.id, s.no, s.updatedAt, s.captured) {
        value = if (!s.captured) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(Naming.privatePhoto(ctx, cls.id, s.no).absolutePath)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    val okColor = Color(0xFF3C9A5F)
    val warnColor = Color(0xFFE08A00)
    val stateColor = when {
        s.retake || s.isLow() -> warnColor
        s.captured -> okColor
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.height(46.dp).aspectRatio(TARGET_W.toFloat() / TARGET_H)) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.5.dp, stateColor.copy(alpha = if (s.captured) 1f else 0.35f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                val t = thumb
                if (t != null) {
                    Image(
                        bitmap = t.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(if (s.captured) "…" else "—", color = MaterialTheme.colorScheme.outline)
                }
            }
            if (s.captured) {
                // Küçük durum rozeti: yeşil onay / turuncu tekrar
                Box(
                    Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(stateColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (s.retake) Icons.Filled.Refresh else Icons.Filled.Check,
                        contentDescription = if (s.retake) "Tekrar çekilmeli" else "Çekildi",
                        tint = Color.White, modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                s.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.no, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "  ·  " + if (s.retake) "tekrar çekilmeli" else if (s.captured) "çekildi" + (if (s.score >= 0) " · ${s.score}" else "") else "bekliyor",
                    style = MaterialTheme.typography.bodySmall, color = stateColor
                )
            }
        }
        Box {
            MoreBtn(onClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (s.captured) "Yeniden çek" else "Fotoğraf çek") }, onClick = {
                    menu = false
                    onCamera()
                })
                DropdownMenuItem(text = { Text("Ad soyadı düzenle") }, onClick = {
                    menu = false
                    onRename()
                })
                if (s.captured) {
                    DropdownMenuItem(text = { Text(if (s.retake) "Tekrar işaretini kaldır" else "Tekrar çekime işaretle") }, onClick = {
                        menu = false
                        onToggleRetake()
                    })
                    DropdownMenuItem(text = { Text("Fotoğrafı sil") }, onClick = {
                        menu = false
                        onClear()
                    })
                }
                DropdownMenuItem(text = { Text("Listeden çıkar") }, onClick = {
                    menu = false
                    onDelete()
                })
            }
        }
    }
}

@Composable
private fun AddStudentDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> String?,
    title: String = "Öğrenci ekle",
    confirmText: String = "Ekle",
    initialNo: String = "",
    initialName: String = "",
    note: String? = null
) {
    var no by remember { mutableStateOf(initialNo) }
    var name by remember { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = no, onValueChange = { no = it; error = null },
                    label = { Text("Öğrenci numarası") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("Ad soyad") }, singleLine = true
                )
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onConfirm(no, name) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
