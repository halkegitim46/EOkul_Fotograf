package com.eokul.studio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.security.MessageDigest

enum class Route { Gallery, Editor, Settings }

/** Dosya adı e-Okul kuralına uymuyorsa kaydetmeden önce gösterilen uyarı. */
data class NameWarning(val bad: List<String>, val names: List<String>)

class UndoEntry(val label: String, val name: String, val old: PhotoItem)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val appCtx: Context = app.applicationContext

    init {
        SettingsState.init(appCtx)
    }

    val settings: Settings get() = SettingsState.current

    val items = mutableStateListOf<PhotoItem>()
    val undo = mutableStateListOf<UndoEntry>()

    var route by mutableStateOf(Route.Gallery)
        private set
    var session by mutableStateOf<EditSession?>(null)
        private set

    var loading by mutableStateOf(false)
        private set
    var loadDone by mutableIntStateOf(0)
        private set
    var loadTotal by mutableIntStateOf(0)
        private set
    var saving by mutableStateOf(false)
        private set
    var saveDone by mutableIntStateOf(0)
        private set
    var saveTotal by mutableIntStateOf(0)
        private set

    var status by mutableStateOf("Başlamak için bir klasör veya fotoğraf seçin")
        private set
    var sourceLabel by mutableStateOf("")
        private set

    var nameWarning by mutableStateOf<NameWarning?>(null)
        private set
    var failures by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var confirmResetAll by mutableStateOf(false)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private var treeUri: Uri? = null
    private var store: CropStore? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var sessionJob: Job? = null
    private var persistJob: Job? = null
    private val storeMutex = Mutex()

    fun busy(): Boolean = loading || saving

    private fun say(msg: String) {
        messages.tryEmit(msg)
    }

    // ------------------------------------------------------------------ ayarlar
    fun updateSettings(s: Settings) {
        SettingsState.update(s)
    }

    // ------------------------------------------------------------------ kaynak seçimi
    fun openFolder(uri: Uri) {
        if (busy()) return
        try {
            appCtx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // kalıcı izin verilemese de bu oturumda çalışır
        }
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { listFolder(uri) }
            if (files.isEmpty()) {
                say("Klasörde resim bulunamadı")
                return@launch
            }
            persistNow()
            treeUri = uri
            sourceLabel = DocumentFile.fromTreeUri(appCtx, uri)?.name ?: "Klasör"
            startLoad(files, "tree:$uri", true)
        }
    }

    fun openPhotos(uris: List<Uri>) {
        if (busy() || uris.isEmpty()) return
        for (u in uris) {
            try {
                appCtx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // sistem seçicisi geçici izin verir; yeterlidir
            }
        }
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.map { u -> displayName(u) to u }
                    .sortedWith { a, b -> compareNatural(a.first, b.first) }
            }
            persistNow()
            treeUri = null
            sourceLabel = "Seçilen fotoğraflar"
            startLoad(files, "pick:" + files.joinToString("|") { it.second.toString() }, true)
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            appCtx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                name = if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            name = null
        }
        return name?.takeIf { it.isNotBlank() } ?: ((uri.lastPathSegment ?: "foto") + ".jpg")
    }

    private fun listFolder(uri: Uri): List<Pair<String, Uri>> {
        val root = DocumentFile.fromTreeUri(appCtx, uri) ?: return emptyList()
        return root.listFiles()
            .filter { it.isFile && isImageName(it.name ?: "") }
            .map { (it.name ?: "") to it.uri }
            .sortedWith { a, b -> compareNatural(a.first, b.first) }
    }

    private fun storeKey(key: String): CropStore {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return CropStore.forKey(appCtx, digest.joinToString("") { b -> "%02x".format(b) })
    }

    // ------------------------------------------------------------------ yükleme
    private fun startLoad(sources: List<Pair<String, Uri>>, key: String, useSaved: Boolean) {
        loadJob?.cancel()
        val st = storeKey(key)
        store = st
        undo.clear()
        items.clear()
        items.addAll(uniqueNames(sources).map { (n, u) -> PhotoItem(n, u) })
        loadDone = 0
        loadTotal = items.size
        loading = true
        status = "⏳ Yükleniyor… 0/${items.size}"
        val names = items.map { it.name }
        val s = settings

        loadJob = viewModelScope.launch {
            val saved = if (useSaved) withContext(Dispatchers.IO) { st.load() } else emptyMap()
            val sem = Semaphore(2)
            coroutineScope {
                for (n in names) {
                    launch(Dispatchers.Default) {
                        sem.withPermit { processOne(n, saved[n], s) }
                    }
                }
            }
            loading = false
            val failed = items.count { it.error != null }
            status = "✅ ${items.size - failed} fotoğraf hazır" + if (failed > 0) "  ·  ⚠ $failed okunamadı" else ""
            persistNow()
        }
    }

    private suspend fun processOne(name: String, rec: CropStore.Rec?, s: Settings) {
        val item = withContext(Dispatchers.Main) { items.firstOrNull { it.name == name } } ?: return
        val result: PhotoItem = try {
            val bmp = ImageOps.decode(appCtx, item.uri)
            val w = bmp.width
            val h = bmp.height
            val px = ImageOps.pixels(bmp)
            val box: Box
            val faceFound: Boolean
            val faceCount: Int
            val edited: Boolean
            val skipped: Boolean
            if (rec != null && rec.w == w && rec.h == h) {
                box = rec.box.fitInside(w, h)
                faceFound = rec.face
                faceCount = rec.faceCount
                edited = rec.edited
                skipped = rec.skipped
            } else {
                val faces = ImageOps.detectFaces(bmp, px)
                box = ImageOps.defaultBox(w, h, faces.firstOrNull(), s)
                faceFound = faces.isNotEmpty()
                faceCount = faces.size
                edited = false
                skipped = false
            }
            bmp.recycle()
            item.copy(
                width = w, height = h, box = box, faceFound = faceFound, faceCount = faceCount,
                edited = edited, skipped = skipped, thumb = ImageOps.thumb(px, w, h, box),
                error = null, ready = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            item.copy(error = e.message ?: e.javaClass.simpleName, ready = true)
        }
        withContext(Dispatchers.Main) {
            replaceItem(result)
            loadDone++
            status = "⏳ Yükleniyor… $loadDone/$loadTotal"
        }
    }

    private fun replaceItem(item: PhotoItem) {
        val i = items.indexOfFirst { it.name == item.name }
        if (i >= 0) items[i] = item
    }

    /** Elle düzenlenmemiş fotoğrafları güncel kırpma ayarlarıyla yeniden hesaplar. */
    fun recropUnedited() {
        if (busy() || items.isEmpty()) return
        val sources = items.map { it.name to it.uri }
        persistNow()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                storeMutex.withLock { store?.dropUnedited() }
            }
            startLoadKeepStore(sources)
        }
    }

    private fun startLoadKeepStore(sources: List<Pair<String, Uri>>) {
        val st = store ?: return
        loadJob?.cancel()
        undo.clear()
        items.clear()
        items.addAll(sources.map { (n, u) -> PhotoItem(n, u) })
        loadDone = 0
        loadTotal = items.size
        loading = true
        status = "⏳ Yeniden kırpılıyor… 0/${items.size}"
        val names = items.map { it.name }
        val s = settings
        loadJob = viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { st.load() }
            val sem = Semaphore(2)
            coroutineScope {
                for (n in names) {
                    launch(Dispatchers.Default) {
                        sem.withPermit { processOne(n, saved[n], s) }
                    }
                }
            }
            loading = false
            status = "✅ ${items.count { it.error == null }} fotoğraf yeniden kırpıldı"
            persistNow()
        }
    }

    fun resetAllCrops() {
        if (busy() || items.isEmpty()) return
        val sources = items.map { it.name to it.uri }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storeMutex.withLock { store?.clear() } }
            startLoadKeepStore(sources)
        }
    }

    // ------------------------------------------------------------------ kalıcılık
    fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(600)
            persistNow()
        }
    }

    fun persistNow() {
        val st = store ?: return
        val snap = items.toList()
        if (snap.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            storeMutex.withLock { st.save(snap) }
        }
    }

    // ------------------------------------------------------------------ geri al
    private fun pushUndo(label: String, old: PhotoItem) {
        undo.add(UndoEntry(label, old.name, old))
        if (undo.size > MAX_UNDO) undo.removeAt(0)
    }

    fun undoLast() {
        if (busy()) {
            say("⏳ İşlem sürerken geri alınamaz")
            return
        }
        if (undo.isEmpty()) {
            say("Geri alınacak işlem yok")
            return
        }
        val e = undo.removeAt(undo.size - 1)
        replaceItem(e.old)
        schedulePersist()
        say("↶ ${e.label} geri alındı")
    }

    // ------------------------------------------------------------------ kart işlemleri
    fun toggleSkip(name: String) {
        if (busy()) return
        val i = items.indexOfFirst { it.name == name }
        if (i < 0) return
        val old = items[i]
        items[i] = old.copy(skipped = !old.skipped)
        pushUndo(if (old.skipped) "$name atlama kaldırma" else "$name atlama", old)
        schedulePersist()
    }

    fun resetItem(name: String) {
        if (busy()) return
        val old = items.firstOrNull { it.name == name } ?: return
        if (old.error != null) return
        viewModelScope.launch {
            try {
                val s = settings
                val updated = withContext(Dispatchers.Default) {
                    val bmp = ImageOps.decode(appCtx, old.uri)
                    val px = ImageOps.pixels(bmp)
                    val faces = ImageOps.detectFaces(bmp, px)
                    val w = bmp.width
                    val h = bmp.height
                    bmp.recycle()
                    val box = ImageOps.defaultBox(w, h, faces.firstOrNull(), s)
                    old.copy(
                        width = w, height = h, box = box, faceFound = faces.isNotEmpty(),
                        faceCount = faces.size, edited = false, skipped = false,
                        thumb = ImageOps.thumb(px, w, h, box)
                    )
                }
                replaceItem(updated)
                pushUndo("$name sıfırlama", old)
                schedulePersist()
                say("✓ $name sıfırlandı")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                say("Sıfırlanamadı: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------ düzenleyici
    fun openEditor(name: String) {
        if (busy()) return
        val item = items.firstOrNull { it.name == name } ?: return
        if (item.error != null || item.box == null) return
        route = Route.Editor
        loadSession(name)
    }

    private fun loadSession(name: String) {
        val item = items.firstOrNull { it.name == name } ?: return
        sessionJob?.cancel()
        val s = EditSession(name)
        session = s
        sessionJob = viewModelScope.launch {
            try {
                val (bmp, px) = withContext(Dispatchers.Default) {
                    val b = ImageOps.decode(appCtx, item.uri)
                    b to ImageOps.pixels(b)
                }
                s.bitmap = bmp
                s.pixels = px
                s.iw = bmp.width
                s.ih = bmp.height
                val start = (item.box ?: ImageOps.defaultBox(bmp.width, bmp.height, null, settings))
                    .fitInside(bmp.width, bmp.height)
                s.box = start
                s.startBox = start
                s.faceFound = item.faceFound
                s.loading = false
                s.faces = withContext(Dispatchers.Default) { ImageOps.detectFaces(bmp, px) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                say("Resim okunamadı: ${e.message ?: e.javaClass.simpleName}")
                if (session === s) closeEditor()
            }
        }
    }

    fun closeEditor() {
        sessionJob?.cancel()
        session = null
        route = Route.Gallery
    }

    fun applyEditor() {
        val s = session ?: return
        commitSession(s)
        closeEditor()
    }

    fun navigateEditor(delta: Int) {
        val s = session ?: return
        if (s.loading) return
        var j = items.indexOfFirst { it.name == s.name } + delta
        while (j in items.indices && items[j].error != null) j += delta
        if (j !in items.indices) return
        commitSession(s)
        loadSession(items[j].name)
    }

    private fun commitSession(s: EditSession) {
        if (!s.changed()) return
        val px = s.pixels ?: return
        val i = items.indexOfFirst { it.name == s.name }
        if (i < 0) return
        val old = items[i]
        val thumb = ImageOps.thumb(px, s.iw, s.ih, s.box)
        items[i] = old.copy(
            width = s.iw, height = s.ih, box = s.box, edited = true,
            faceFound = s.faceFound,
            faceCount = if (s.faces.isNotEmpty() || !s.faceFound) s.faces.size else old.faceCount,
            thumb = thumb
        )
        pushUndo("${s.name} düzenleme", old)
        s.startBox = s.box
        schedulePersist()
    }

    fun editorPickFace(index: Int) {
        val s = session ?: return
        val f = s.faces.getOrNull(index) ?: return
        s.faceFound = true
        s.moveBox(ImageOps.defaultBox(s.iw, s.ih, f, settings))
    }

    fun editorResetToFace() {
        val s = session ?: return
        val bmp = s.bitmap ?: return
        val px = s.pixels ?: return
        viewModelScope.launch {
            val faces = withContext(Dispatchers.Default) { ImageOps.detectFaces(bmp, px) }
            s.faces = faces
            s.faceFound = faces.isNotEmpty()
            s.moveBox(ImageOps.defaultBox(s.iw, s.ih, faces.firstOrNull(), settings))
        }
    }

    fun openSettings() {
        route = Route.Settings
    }

    fun closeSettings() {
        route = Route.Gallery
    }

    // ------------------------------------------------------------------ kaydetme
    fun requestSaveAll() {
        requestSave(items.filter { it.error == null }.map { it.name })
    }

    fun requestSave(names: List<String>) {
        if (busy()) return
        val todo = names.mapNotNull { n -> items.firstOrNull { it.name == n } }
            .filter { it.box != null && it.error == null && !it.skipped }
        if (todo.isEmpty()) {
            say("Kaydedilecek fotoğraf yok (hepsi atlandı)")
            return
        }
        val outNames = planOutputNames(items.filter { it.error == null }.map { it.name })
        val bad = invalidOutputNames(todo.mapNotNull { outNames[it.name] }, settings.nameRegex)
        if (bad.isNotEmpty()) {
            nameWarning = NameWarning(bad, todo.map { it.name })
        } else {
            doSave(todo.map { it.name })
        }
    }

    fun confirmNameWarning() {
        val w = nameWarning ?: return
        nameWarning = null
        doSave(w.names)
    }

    fun dismissNameWarning() {
        nameWarning = null
    }

    fun dismissFailures() {
        failures = emptyList()
    }

    fun cancelSave() {
        if (saving) {
            saveJob?.cancel()
            say("⏹ İptal ediliyor…")
        }
    }

    private fun doSave(names: List<String>) {
        if (busy()) return
        persistNow()
        val s = settings
        val tree = treeUri
        val outNames = planOutputNames(items.filter { it.error == null }.map { it.name })
        val jobs = names.mapNotNull { n ->
            val found = items.firstOrNull { x -> x.name == n } ?: return@mapNotNull null
            val out = outNames[n] ?: return@mapNotNull null
            Triple(found, out, found.box)
        }
        saving = true
        saveDone = 0
        saveTotal = jobs.size
        status = "💾 Kaydediliyor… 0/${jobs.size}"

        saveJob = viewModelScope.launch {
            var ok = 0
            var cancelled = false
            val fails = ArrayList<Pair<String, String>>()
            try {
                val sem = Semaphore(2)
                coroutineScope {
                    val pending = jobs.map { (item, out, box) ->
                        Triple(
                            item, out,
                            async(Dispatchers.Default) {
                                sem.withPermit {
                                    runCatching {
                                        val bmp = ImageOps.decode(appCtx, item.uri)
                                        val px = ImageOps.pixels(bmp)
                                        val w = bmp.width
                                        val h = bmp.height
                                        bmp.recycle()
                                        ImageOps.render(appCtx, px, w, h, box!!.fitInside(w, h), s)
                                    }
                                }
                            }
                        )
                    }
                    for ((item, out, deferred) in pending) {
                        val r = deferred.await()
                        val bytes = r.getOrNull()
                        if (bytes == null) {
                            fails.add(item.name to (r.exceptionOrNull()?.message ?: "Hata"))
                        } else {
                            try {
                                withContext(Dispatchers.IO) { Output.write(appCtx, tree, s.outputDir, out, bytes) }
                                ok++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                fails.add(item.name to (e.message ?: e.javaClass.simpleName))
                            }
                        }
                        saveDone++
                        status = "💾 Kaydediliyor… $saveDone/$saveTotal"
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            }
            saving = false
            val where = Output.locationLabel(tree, sourceLabel, s.outputDir)
            when {
                cancelled -> {
                    status = "⏹ İptal edildi — $ok kaydedildi"
                    say("⏹ İptal edildi — $ok fotoğraf kaydedildi")
                }
                fails.isNotEmpty() -> {
                    status = "⚠ $ok başarılı, ${fails.size} hatalı"
                    failures = fails
                }
                else -> {
                    status = "✅ $ok fotoğraf kaydedildi → $where"
                    say("✓ $ok fotoğraf kaydedildi → $where")
                }
            }
        }
    }

    override fun onCleared() {
        persistNow()
        super.onCleared()
    }

    companion object {
        private const val MAX_UNDO = 20
    }
}
