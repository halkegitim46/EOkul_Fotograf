package com.eokul.studio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WarnColor = Color(0xFFE0A030)
private val EditedColor = Color(0xFF4F86E8)
private val DimColor = Color(0xFF8A8F9C)

@Composable
fun GalleryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) vm.openFolder(uri)
    }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.openPhotos(uris)
    }
    val pickFolder = { folderPicker.launch(null) }
    val pickPhotos = { photoPicker.launch(arrayOf("image/*")) }

    Column(Modifier.fillMaxSize()) {
        GalleryHeader(vm, onBack, pickFolder, pickPhotos)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.items.isEmpty()) {
                EmptyState(pickFolder, pickPhotos)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vm.items, key = { it.name }) { item ->
                        PhotoCard(item, vm)
                    }
                }
            }
        }
        BottomBar(vm)
    }
}

@Composable
private fun GalleryHeader(vm: AppViewModel, onBack: () -> Unit, pickFolder: () -> Unit, pickPhotos: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackBtn(onClick = onBack, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(Modifier.weight(1f)) {
                Text(
                    "E-Okul Foto Studio",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (vm.sourceLabel.isNotEmpty()) {
                    Text(
                        vm.sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            TextButton(onClick = pickFolder, enabled = !vm.saving) { Text("📁 Klasör") }
            TextButton(onClick = pickPhotos, enabled = !vm.saving) { Text("🖼 Fotoğraf") }
            Box {
                MoreBtn(onClick = { menuOpen = true }, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("⚙ Ayarlar ve kırpma") },
                        onClick = {
                            menuOpen = false
                            vm.openSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("↺ Tüm kırpmaları sıfırla") },
                        enabled = vm.items.isNotEmpty() && !vm.busy(),
                        onClick = {
                            menuOpen = false
                            vm.confirmResetAll = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(pickFolder: () -> Unit, pickPhotos: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎓", fontSize = 56.sp)
        Spacer(Modifier.size(12.dp))
        Text("Başlamak için fotoğraf seçin", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(6.dp))
        Text(
            "Fotoğraflar otomatik kırpılır (${TARGET_W}×${TARGET_H}). Düzenlemek için bir fotoğrafa dokunun.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = pickFolder) { Text("📁  Klasör Seç") }
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = pickPhotos) { Text("🖼  Fotoğraf Seç") }
    }
}

@Composable
private fun PhotoCard(item: PhotoItem, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    val clickable = item.ready && item.error == null && !vm.busy()
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = clickable) { vm.openEditor(item.name) }) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(TARGET_W.toFloat() / TARGET_H)) {
                val thumb = item.thumb
                val img = remember(thumb) { thumb?.asImageBitmap() }
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(if (item.skipped) 0.35f else 1f)
                    )
                } else if (item.error != null) {
                    Text("⚠", Modifier.align(Alignment.Center), fontSize = 28.sp)
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
                }
                if (item.ready && item.error == null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
                        Text(
                            "⋮",
                            color = Color.White,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable(enabled = !vm.busy()) { menu = true }
                                .padding(horizontal = 9.dp, vertical = 2.dp)
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("✎ Düzenle") },
                                onClick = {
                                    menu = false
                                    vm.openEditor(item.name)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🎯 Yüzü sıfırla") },
                                onClick = {
                                    menu = false
                                    vm.resetItem(item.name)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (item.skipped) "↺ Atlamayı geri al" else "⏭ Atla") },
                                onClick = {
                                    menu = false
                                    vm.toggleSkip(item.name)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("💾 Bu fotoğrafı kaydet") },
                                onClick = {
                                    menu = false
                                    vm.requestSave(listOf(item.name))
                                }
                            )
                        }
                    }
                }
            }
            Text(
                item.name,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val (badge, color) = badgeFor(item)
            Text(
                badge,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1
            )
        }
    }
}

private fun badgeFor(item: PhotoItem): Pair<String, Color> = when {
    item.error != null -> "⚠ Okunamadı" to WarnColor
    !item.ready -> "…" to DimColor
    item.skipped -> "⏭ Atlandı" to DimColor
    item.edited -> "✎ Düzenlendi" to EditedColor
    item.faceCount > 1 -> "⚠ ${item.faceCount} yüz" to WarnColor
    !item.faceFound -> "⚠ Yüz yok" to WarnColor
    else -> " " to DimColor
}

@Composable
private fun BottomBar(vm: AppViewModel) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (vm.loading && vm.loadTotal > 0) {
                LinearProgressIndicator(
                    progress = { vm.loadDone.toFloat() / vm.loadTotal },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (vm.saving && vm.saveTotal > 0) {
                LinearProgressIndicator(
                    progress = { vm.saveDone.toFloat() / vm.saveTotal },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(vm.status, style = MaterialTheme.typography.bodySmall, maxLines = 2)

            val ready = vm.items.filter { it.ready && it.error == null }
            if (ready.isNotEmpty()) {
                val edited = ready.count { it.edited }
                val noFace = ready.count { !it.faceFound && !it.edited && !it.skipped }
                val multi = ready.count { it.faceCount > 1 && !it.edited && !it.skipped }
                val skipped = ready.count { it.skipped }
                val parts = ArrayList<String>()
                parts.add("${ready.size} fotoğraf")
                parts.add("$edited düzenlendi")
                if (multi > 0) parts.add("⚠ $multi çoklu yüz")
                if (noFace > 0) parts.add("⚠ $noFace yüz yok")
                if (skipped > 0) parts.add("⏭ $skipped atlandı")
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = DimColor
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val n = vm.undo.size
                OutlinedButton(
                    onClick = { vm.undoLast() },
                    enabled = n > 0 && !vm.busy(),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text(if (n > 0) "↶ Geri Al ($n)" else "↶ Geri Al") }
                if (vm.saving) {
                    OutlinedButton(
                        onClick = { vm.cancelSave() },
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { Text("⏹ İptal") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.requestSaveAll() },
                    enabled = vm.items.isNotEmpty() && !vm.busy()
                ) { Text("💾 Tümünü Kaydet") }
            }
        }
    }
}
