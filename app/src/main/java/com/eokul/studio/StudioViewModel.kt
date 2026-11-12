package com.eokul.studio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eokul.studio.core.Crop
import com.eokul.studio.core.RosterParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface Screen {
    data object Home : Screen
    data class ClassDetail(val classId: String) : Screen
    data class Import(val classId: String?) : Screen
    data object MultiImport : Screen
    data class Sheet(val classId: String) : Screen
    data class Camera(val classId: String, val no: String) : Screen
    data object Review : Screen
    data object CaptureEditor : Screen
    data object Settings : Screen
    data object Batch : Screen
}

/** Çekilen (veya seçilen) tek bir fotoğrafın inceleme durumu. */
class CaptureState(
    val classId: String,
    val no: String,
    val source: Uri,
    val temp: File?,
    val fromSaved: Boolean
) {
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var session by mutableStateOf<EditSession?>(null)
    var saving by mutableStateOf(false)
    /** İnceleme ekranında kalite/yüz uyarısı çıktı mı (kayıtta "tekrar çek" işareti olur). */
    var warned by mutableStateOf(false)
    /** İnceleme ekranında hesaplanan kalite puanı; -1 = hesaplanmadı. */
    var score by mutableStateOf(-1)
    /** Kayıtlı kırpmayı düzenleme: inceleme ekranı atlanır, düzenleyici doğrudan açılır; Uygula = kaydet. */
    var directEdit by mutableStateOf(false)
    /** Günlük için: uyarı kodları (virgüllü), otomatik kabul mi, inceleme ekranının açıldığı an. */
    var hardCodes: String = ""
    var softCodes: String = ""
    var auto: Boolean = false
    var shownAt: Long = 0L
}

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val store = RosterStore(File(ctx.filesDir, "roster.json"))
    private val storeMutex = Mutex()
    private val rosterVersion = AtomicLong(0L)
    /** Diske henüz doğrulanmamış fotoğraf kayıtları eski öğrenci durumuyla temsil edilir. */
    private val pendingCaptureOriginals = HashMap<String, Student>()
    private var recoveryBlocked = false
    var isRestoring by mutableStateOf(false)
        private set
    /** Arka plandaki fotoğraf yazmaları sırayla çalışır (bellek ve klasör çakışması olmasın). */
    private val saveMutex = Mutex()

    val classes = mutableStateListOf<SchoolClass>()
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    var capture by mutableStateOf<CaptureState?>(null)
        private set
    var rootName by mutableStateOf<String?>(null)
        private set

    val settings: Settings get() = SettingsState.current
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private var captureJob: Job? = null

    /** Açılışta liste dosyası bozuk çıktıysa gösterilen kurtarma açıklaması. */
    var startupNote by mutableStateOf<String?>(null)
        private set

    fun addStartupNote(msg: String) {
        startupNote = (startupNote?.let { it + "\n\n" } ?: "") + msg
    }

    fun dismissStartupNote() {
        startupNote = null
    }

    init {
        SettingsState.init(ctx)
        var recoveryError: String? = null
        try {
            RestoreTransaction.recover(ctx)
        } catch (e: Exception) {
            recoveryBlocked = true
            recoveryError = "Önceki geri yükleme işlemi güvenli biçimde tamamlanamadı. Veri koruması için otomatik kayıt durduruldu. " +
                "Kurtarma dosyalarını silmeyin. Ayrıntı: ${e.message ?: e.javaClass.simpleName}"
            Ops.error("restore_recovery", e)
        }
        classes.addAll(store.load())
        startupNote = listOfNotNull(store.loadNote, recoveryError).joinToString("\n\n").ifBlank { null }
        refreshRootName()
    }

    fun updateSettings(s: Settings) {
        SettingsState.update(s)
    }

    // ------------------------------------------------------------------ PIN kilidi
    var locked by mutableStateOf(SettingsState.current.pinHash != null)
        private set
    private var backgroundedAt = 0L

    fun lockIfNeeded() {
        if (settings.pinHash != null) locked = true
    }

    fun onBackgrounded() {
        backgroundedAt = System.currentTimeMillis()
    }

    /** Uygulama arka plandan dönerse ve 30 sn'den uzun kaldıysa kilitler. */
    fun onForegrounded() {
        if (settings.pinHash != null && backgroundedAt != 0L &&
            System.currentTimeMillis() - backgroundedAt > 30_000L
        ) locked = true
    }

    /** Eski (SHA-256 + tuz) biçim; yalnızca eski kayıtları doğrulamak için tutulur. */
    private fun legacyHash(pin: String, salt: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
        return d.joinToString("") { b -> "%02x".format(b) }
    }

    /** PBKDF2-HMAC-SHA256 (Android'in yerleşik API'si): kaba kuvvetle PIN tahminini yavaşlatır. Biçim: "pbkdf2$tur$özet". */
    private fun pbkdf2(pin: String, salt: String, iterations: Int): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(), iterations, 256)
        val d = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return "pbkdf2$$iterations$" + d.joinToString("") { b -> "%02x".format(b) }
    }

    private fun hashPin(pin: String, salt: String): String = pbkdf2(pin, salt, PIN_ITERATIONS)

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun checkPin(pin: String): Boolean {
        val h = settings.pinHash ?: return true
        val salt = settings.pinSalt ?: return false
        if (h.startsWith("pbkdf2$")) {
            val iters = h.split("$").getOrNull(1)?.toIntOrNull() ?: return false
            return constantTimeEquals(pbkdf2(pin, salt, iters), h)
        }
        return constantTimeEquals(legacyHash(pin, salt), h)
    }

    fun unlock(pin: String): Boolean {
        if (!checkPin(pin)) return false
        // Eski biçimle kaydedilmiş PIN doğru girildiğinde yeni biçime sessizce yükseltilir.
        val h = settings.pinHash
        val salt = settings.pinSalt
        if (h != null && salt != null && !h.startsWith("pbkdf2$")) {
            SettingsState.update(settings.copy(pinHash = hashPin(pin, salt)))
        }
        locked = false
        return true
    }

    fun setPin(pin: String?) {
        if (pin == null) {
            SettingsState.update(settings.copy(pinHash = null, pinSalt = null))
            locked = false
        } else {
            val salt = java.util.UUID.randomUUID().toString()
            SettingsState.update(settings.copy(pinHash = hashPin(pin, salt), pinSalt = salt))
        }
    }

    fun toast(msg: String) {
        say(msg)
    }

    private fun say(msg: String) {
        messages.tryEmit(msg)
    }

    // ------------------------------------------------------------------ gezinme
    fun navigate(s: Screen) {
        if (isRestoring) return
        stack.add(s)
    }

    fun back() {
        if (isRestoring) return
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun replace(s: Screen) {
        if (isRestoring) return
        stack[stack.lastIndex] = s
    }

    fun handleBack() {
        if (isRestoring) return
        when (screen) {
            Screen.Review -> discardCapture()
            Screen.CaptureEditor -> cancelCaptureEditor()
            else -> back()
        }
    }

    // ------------------------------------------------------------------ kalıcılık
    private fun captureKey(classId: String, no: String) = "$classId|$no"

    /** Henüz fotoğraf dosyası doğrulanmamış öğrencileri diskte eski durumuyla temsil eder. */
    private fun stableRosterSnapshot(): List<SchoolClass> = classes.map { cls ->
        cls.copy(students = cls.students.map { student ->
            pendingCaptureOriginals[captureKey(cls.id, student.no)] ?: student
        })
    }

    /** Her değişiklik sürümlenir; yalnızca en güncel snapshot diske yazılabilir. */
    private fun persist() {
        if (recoveryBlocked) {
            say("⚠ Kurtarma işlemi tamamlanmadığı için otomatik kayıt durduruldu. Uygulamayı kapatmayın.")
            return
        }
        if (isRestoring) return
        val version = rosterVersion.incrementAndGet()
        val snap = stableRosterSnapshot()
        viewModelScope.launch(Dispatchers.IO) {
            val err = storeMutex.withLock {
                if (version != rosterVersion.get()) null else store.save(snap)
            }
            if (err != null) {
                Ops.log("roster_save", "ok", false, "version", version, "err", err.take(180))
                say("⚠ Öğrenci listesi kaydedilemedi: $err")
            } else if (version == rosterVersion.get()) {
                Ops.log("roster_save", "ok", true, "version", version, "students", snap.sumOf { it.students.size })
            }
        }
    }

    fun persistNow() {
        persist()
    }

    /** Activity arka plana geçerken çağrılır; son snapshot diske yazılana kadar bekler. */
    suspend fun persistAndWait() {
        if (recoveryBlocked || isRestoring) return
        while (true) {
            val version = rosterVersion.get()
            val snap = stableRosterSnapshot()
            val result = withContext(Dispatchers.IO) {
                storeMutex.withLock {
                    if (version != rosterVersion.get()) null else store.save(snap)
                }
            }
            if (version == rosterVersion.get()) {
                if (result != null) {
                    Ops.log("roster_save", "ok", false, "version", version, "err", result.take(180))
                    say("⚠ Son öğrenci listesi kaydedilemedi: $result")
                }
                return
            }
        }
    }

    override fun onCleared() {
        // onCleared ana thread'de bloklayıcı dosya yazımı yapmamalı. MainActivity onStop'ta persistAndWait çağırır.
        super.onCleared()
    }

    fun classById(id: String?): SchoolClass? = classes.firstOrNull { it.id == id }

    private fun updateClass(id: String, fn: (SchoolClass) -> SchoolClass) {
        val i = classes.indexOfFirst { it.id == id }
        if (i < 0) return
        classes[i] = fn(classes[i])
        persist()
    }

    /** Sınıftaki tüm "tekrar çekilmeli" işaretlerini kaldırır (eski sürümün hatalı işaretleri için). */
    fun clearRetakeFlags(classId: String) {
        updateClass(classId) { c -> c.copy(students = c.students.map { if (it.retake) it.copy(retake = false) else it }) }
    }

    fun toggleRetake(classId: String, no: String) {
        updateStudent(classId, no) { it.copy(retake = !it.retake) }
    }

    private fun updateStudent(classId: String, no: String, fn: (Student) -> Student) {
        updateClass(classId) { c ->
            c.copy(students = c.students.map { if (it.no == no) fn(it) else it })
        }
    }

    /** Fotoğraf dosyası diske yazılmadan geçici UI durumu günceller; kalıcı kayıt yapılmaz. */
    private fun updateStudentTransient(classId: String, no: String, fn: (Student) -> Student) {
        val index = classes.indexOfFirst { it.id == classId }
        if (index < 0) return
        val cls = classes[index]
        classes[index] = cls.copy(students = cls.students.map { if (it.no == no) fn(it) else it })
    }

    // ------------------------------------------------------------------ kayıt klasörü
    private fun refreshRootName() {
        val u = settings.rootUri?.let { Uri.parse(it) }
        rootName = if (u == null) null else try {
            DocumentFile.fromTreeUri(ctx, u)?.name
        } catch (e: Exception) {
            null
        }
    }

    fun hasRootAccess(): Boolean {
        val u = settings.rootUri?.let { Uri.parse(it) } ?: return false
        return ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission }
    }

    fun setRootFolder(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            say("Klasör izni alınamadı")
            return
        }
        SettingsState.update(settings.copy(rootUri = uri.toString()))
        refreshRootName()
        say("✓ Kayıt klasörü: ${rootName ?: "seçildi"}")
    }

    // ------------------------------------------------------------------ sınıflar
    private fun newId(): String =
        System.currentTimeMillis().toString(36) + (0..999).random().toString(36)

    private fun folderKey(name: String): String = RosterParser.sanitizeFileName(name).lowercase()

    /** Yeni sınıf ekler; ad boşsa veya aynı adlı sınıf varsa null döner. */
    fun addClass(name: String): String? {
        val n = RosterParser.normalizeSpaces(name)
        if (n.isEmpty()) return null
        if (classes.any { folderKey(it.name) == folderKey(n) }) return null
        val c = SchoolClass(newId(), n)
        val sorted = (classes.toList() + c).sortedWith { a, b -> compareNatural(a.name, b.name) }
        classes.clear()
        classes.addAll(sorted)
        persist()
        return c.id
    }

    /** Sınıfı yeniden adlandırır; kayıt klasöründeki klasörleri de (mümkünse) yeniden adlandırır. */
    fun renameClass(id: String, name: String): Boolean {
        val c = classById(id) ?: return false
        val n = RosterParser.normalizeSpaces(name)
        if (n.isEmpty()) return false
        if (classes.any { it.id != id && folderKey(it.name) == folderKey(n) }) {
            say("Bu adla başka bir sınıf var")
            return false
        }
        val oldDir = RosterParser.sanitizeFileName(c.name)
        val newDir = RosterParser.sanitizeFileName(n)
        val root = settings.rootUri?.let { Uri.parse(it) }
        if (root != null && oldDir != newDir) {
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val a = Output.renameDir(ctx, root, emptyList(), oldDir, newDir)
                    val b = Output.renameDir(ctx, root, listOf(EOKUL_DIR), oldDir, newDir)
                    a && b
                }
                if (!ok) say("⚠ Sınıf adı değişti ama kayıt klasörü '$oldDir' yeniden adlandırılamadı (hedef ad dolu olabilir). Dosyalar eski klasörde kaldı.")
            }
        }
        val sorted = classes.map { if (it.id == id) it.copy(name = n) else it }
            .sortedWith { a, b -> compareNatural(a.name, b.name) }
        classes.clear()
        classes.addAll(sorted)
        persist()
        return true
    }

    /** [alsoFiles] true ise kayıt klasöründeki sınıf klasörü (çıktılar ve Orijinaller) de içindekilerle silinir. */
    fun deleteClass(id: String, alsoFiles: Boolean = false) {
        val c = classById(id) ?: return
        val root = settings.rootUri?.let { Uri.parse(it) }
        classes.removeAll { it.id == id }
        persist()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                Naming.privateDir(ctx, id).deleteRecursively()
                if (alsoFiles && root != null) {
                    val dir = RosterParser.sanitizeFileName(c.name)
                    Output.deleteDir(ctx, root, emptyList(), dir) && Output.deleteDir(ctx, root, listOf(EOKUL_DIR), dir)
                } else true
            }
            say(
                when {
                    !alsoFiles -> "“${c.name}” silindi (kayıt klasöründeki dosyalar duruyor)"
                    ok -> "“${c.name}” ve kayıt klasöründeki dosyaları silindi"
                    else -> "⚠ “${c.name}” silindi ama kayıt klasörü tamamen silinemedi"
                }
            )
        }
    }

    // ------------------------------------------------------------------ liste içe aktarma
    /** Sınıfa öğrenci ekler. [classId] null ise [newName] ile yeni sınıf açılır. Sonuç mesajı döner (hata: null). */
    fun importRoster(classId: String?, newName: String, rows: List<RosterParser.Row>, mode: MergeMode): String? {
        if (rows.isEmpty()) return null
        val id = classId ?: addClass(newName) ?: return null
        val cls = classById(id) ?: return null
        val r = mergeStudents(cls.students, rows, mode)
        updateClass(id) { it.copy(students = r.students) }
        val parts = ArrayList<String>()
        if (r.added > 0) parts.add("${r.added} eklendi")
        if (r.updated > 0) parts.add("${r.updated} güncellendi")
        if (r.removed > 0) parts.add("${r.removed} çıkarıldı")
        val msg = if (parts.isEmpty()) "Değişiklik yok" else parts.joinToString(", ")
        say("✓ $msg")
        return id
    }

    /** Hazırlanmış şubeleri toplu ekler: aynı adlı sınıf varsa öğrencileri güncellenir, yoksa yeni sınıf açılır. */
    class BranchPlan(val name: String, val rows: List<RosterParser.Row>)

    fun importBranches(plans: List<BranchPlan>): String {
        var created = 0
        var merged = 0
        var students = 0
        for (p in plans) {
            if (p.rows.isEmpty()) continue
            val n = RosterParser.normalizeSpaces(p.name)
            if (n.isEmpty()) continue
            val existing = classes.firstOrNull { folderKey(it.name) == folderKey(n) }
            if (existing != null) {
                val cls = classById(existing.id) ?: continue
                val r = mergeStudents(cls.students, p.rows, MergeMode.UPSERT)
                updateClass(existing.id) { it.copy(students = r.students) }
                merged++
            } else {
                val id = addClass(n) ?: continue
                val cls = classById(id) ?: continue
                val r = mergeStudents(cls.students, p.rows, MergeMode.ADD)
                updateClass(id) { it.copy(students = r.students) }
                created++
            }
            students += p.rows.size
        }
        val parts = ArrayList<String>()
        if (created > 0) parts.add("$created sınıf eklendi")
        if (merged > 0) parts.add("$merged sınıf güncellendi")
        val msg = if (parts.isEmpty()) "Değişiklik yok" else parts.joinToString(", ") + " · $students öğrenci"
        say("✓ $msg")
        return msg
    }

    fun addStudent(classId: String, no: String, name: String): String? {
        val n = no.trim()
        val nm = RosterParser.normalizeSpaces(name)
        if (!n.matches(Regex("\\d{1,11}"))) return "Numara 1–11 haneli sayı olmalı"
        if (nm.length < 2) return "Ad soyad girin"
        val c = classById(classId) ?: return "Sınıf bulunamadı"
        if (c.students.any { it.no == n }) return "Bu numara zaten var"
        val list = (c.students + Student(n, nm)).sortedWith { a, b -> RosterParser.compareNo(a.no, b.no) }
        updateClass(classId) { it.copy(students = list) }
        return null
    }

    fun renameStudent(classId: String, no: String, name: String): String? {
        val nm = RosterParser.normalizeSpaces(name)
        if (nm.length < 2) return "Ad soyad girin"
        updateStudent(classId, no) { it.copy(name = nm) }
        return null
    }

    /** [alsoFiles] true ise kayıt klasöründeki ilişkili dosyalar (çıktı, Orijinaller) da silinir. */
    fun deleteStudent(classId: String, no: String, alsoFiles: Boolean = false) {
        val cls = classById(classId)
        val st = cls?.students?.firstOrNull { it.no == no }
        val root = settings.rootUri?.let { Uri.parse(it) }
        updateClass(classId) { c -> c.copy(students = c.students.filter { it.no != no }) }
        viewModelScope.launch(Dispatchers.IO) {
            Naming.privatePhoto(ctx, classId, no).delete()
            if (alsoFiles && cls != null && st != null && root != null) {
                val dir = RosterParser.sanitizeFileName(cls.name)
                st.savedStem?.let { Output.deletePath(ctx, root, listOf(dir), "$it.jpg") }
                Output.deletePath(ctx, root, listOf(EOKUL_DIR, dir), "$no.jpg")
                st.origUri?.let { Output.deleteUri(ctx, Uri.parse(it)) }
            }
        }
        say(if (alsoFiles) "Öğrenci ve kayıt klasöründeki dosyaları silindi" else "Öğrenci listeden çıkarıldı (kayıt klasöründeki dosyalar duruyor)")
    }

    /** Öğrencinin fotoğrafını (kayıt klasöründeki dosyalar dahil) siler; öğrenci listede kalır. */
    fun clearPhoto(classId: String, no: String) {
        val cls = classById(classId) ?: return
        val st = cls.students.firstOrNull { it.no == no } ?: return
        val root = settings.rootUri?.let { Uri.parse(it) }
        updateStudent(classId, no) {
            it.copy(captured = false, box = null, origUri = null, savedStem = null, faceCount = 0, updatedAt = 0L, retake = false, score = -1)
        }
        viewModelScope.launch(Dispatchers.IO) {
            Naming.privatePhoto(ctx, classId, no).delete()
            if (root != null) {
                val dir = RosterParser.sanitizeFileName(cls.name)
                val stem = st.savedStem
                if (stem != null) Output.deletePath(ctx, root, listOf(dir), "$stem.jpg")
                Output.deletePath(ctx, root, listOf(EOKUL_DIR, dir), "$no.jpg")
            }
            st.origUri?.let { Output.deleteUri(ctx, Uri.parse(it)) }
        }
        say("Fotoğraf silindi")
    }

    // ------------------------------------------------------------------ çekim akışı
    fun startSerial(classId: String) {
        val cls = classById(classId) ?: return
        val next = nextUncaptured(cls, null)
        if (next == null) {
            say("Tüm öğrencilerin fotoğrafı çekilmiş")
            return
        }
        openCamera(classId, next)
    }

    fun openCamera(classId: String, no: String) {
        if (settings.rootUri == null) {
            say("Önce kayıt klasörünü seçin")
            return
        }
        navigate(Screen.Camera(classId, no))
    }

    /** Kamerada önceki / sonraki öğrenciye geç. */
    fun cameraMove(classId: String, no: String, delta: Int) {
        val list = classById(classId)?.students ?: return
        val i = list.indexOfFirst { it.no == no }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        replace(Screen.Camera(classId, list[j].no))
    }

    fun editSavedCrop(classId: String, no: String) {
        val cls = classById(classId) ?: return
        val st = cls.students.firstOrNull { it.no == no } ?: return
        val rootStr = settings.rootUri
        if (rootStr == null) {
            say("Önce kayıt klasörünü seçin")
            return
        }
        viewModelScope.launch {
            // Kayıtlı orijinal gerçekten var mı? Taşınmış/yeniden adlandırılmışsa klasörde numaraya göre ara.
            val found: Uri? = withContext(Dispatchers.IO) {
                val u = st.origUri?.let { Uri.parse(it) }
                if (u != null && Output.exists(ctx, u)) u
                else Output.findFile(
                    ctx, Uri.parse(rootStr),
                    listOf(RosterParser.sanitizeFileName(cls.name), ORIGINALS_DIR),
                    st.savedStem ?: RosterParser.studentStem(st.no, st.name), st.no + "-"
                )
            }
            if (found == null) {
                if (st.origUri != null) updateStudent(classId, no) { it.copy(origUri = null) }
                say("Orijinal fotoğraf bulunamadı (silinmiş veya taşınmış olabilir). Fotoğrafı yeniden çekin.")
                return@launch
            }
            if (found.toString() != st.origUri) updateStudent(classId, no) { it.copy(origUri = found.toString()) }
            onPhotoReady(classId, no, found, null, true, st.box, directEdit = true, savedBoxW = st.boxW, savedBoxH = st.boxH)
        }
    }

    /** Tam çözünürlüklü çalışma görüntüsü + yüzler (inceleme için yalnızca piksel dizisi tutulur). */
    private class Decoded(val w: Int, val h: Int, val px: IntArray, val faces: List<FaceInfo>)

    private var prefetch: Pair<File, kotlinx.coroutines.Deferred<Decoded>>? = null

    private suspend fun decodeFull(uri: Uri): Decoded {
        val bmp = ImageOps.decode(ctx, uri)
        val px = ImageOps.pixels(bmp)
        val faces = ImageOps.detectFaces(bmp, px)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()   // inceleme için yalnızca piksel dizisi gerekir (≈17 MB tasarruf); düzenleyici açılınca yeniden üretilir
        return Decoded(w, h, px, faces)
    }

    /** Çekilen kareyi arka planda önceden çözer; en fazla bir iş bekler (öncekiler iptal edilir). */
    fun prefetchPhoto(file: File) {
        dropPrefetch()
        prefetch = file to viewModelScope.async(Dispatchers.Default) { decodeFull(Uri.fromFile(file)) }
    }

    fun dropPrefetch() {
        prefetch?.second?.cancel()
        prefetch = null
    }

    fun onPhotoReady(
        classId: String, no: String, source: Uri, temp: File?,
        fromSaved: Boolean = false, savedBox: Box? = null, directEdit: Boolean = false,
        savedBoxW: Int = 0, savedBoxH: Int = 0
    ) {
        captureJob?.cancel()
        capture?.temp?.delete()
        val pf = prefetch
        prefetch = null
        val tReady = android.os.SystemClock.elapsedRealtime()
        val pfState = if (pf == null || temp == null) "none" else if (pf.first == temp && !pf.second.isCancelled) "hit" else "miss"
        val cap = CaptureState(classId, no, source, temp, fromSaved)
        cap.directEdit = directEdit
        capture = cap
        if (screen != Screen.Review) navigate(Screen.Review)
        val set = settings
        captureJob = viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.Default) {
                    // Kamera, kare kaydedilir kaydedilmez çözmeyi önden başlatmış olabilir (prefetchPhoto): hazırsa kullan.
                    val dec: Decoded = if (pf != null && temp != null && pf.first == temp && !pf.second.isCancelled) {
                        try {
                            pf.second.await()
                        } catch (e: CancellationException) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()   // kendi iptalimizse çık
                            decodeFull(source)
                        } catch (e: Throwable) {
                            decodeFull(source)
                        }
                    } else {
                        pf?.second?.cancel()
                        decodeFull(source)
                    }
                    Triple(Pair(dec.w, dec.h), dec.px, dec.faces)
                }
                val dims = r.first
                val px = r.second
                val faces = r.third
                val s = EditSession(no)
                s.pixels = px
                s.iw = dims.first
                s.ih = dims.second
                val box = if (savedBox != null) {
                    // Kayıtlı kutu çekim anındaki görüntü boyutundadır; açılan orijinal küçültülmüş olabilir → ölçekle
                    Box.of(Crop.scaleBox(savedBox.toArray(), savedBoxW, savedBoxH, dims.first, dims.second))
                        .fitInside(dims.first, dims.second)
                } else {
                    ImageOps.defaultBox(dims.first, dims.second, faces.firstOrNull(), set)
                }
                s.box = box
                s.startBox = box
                s.faces = faces
                s.faceFound = faces.isNotEmpty()
                s.loading = false
                cap.session = s
                cap.loading = false
                cap.shownAt = android.os.SystemClock.elapsedRealtime()
                Ops.log(
                    "review_ready", "id", Ops.id(no), "ms", cap.shownAt - tReady, "prefetch", pfState,
                    "w", dims.first, "h", dims.second, "faces", faces.size, "saved", fromSaved
                )
                if (directEdit && screen == Screen.Review) openCaptureEditor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Ops.error("review", e)
                cap.error = (e.message ?: e.javaClass.simpleName) +
                    if (e is OutOfMemoryError) " · " + Diag.memInfo() else ""
                cap.loading = false
            }
        }
    }

    fun discardCapture() {
        capture?.let { Ops.log("discard", "id", Ops.id(it.no)) }
        captureJob?.cancel()
        capture?.temp?.delete()
        capture = null
        if (screen == Screen.Review) back()
    }

    fun openCaptureEditor() {
        val s = capture?.session ?: return
        s.startBox = s.box
        if (s.bitmap == null) {
            val px = s.pixels
            if (px != null) {
                try {
                    s.bitmap = android.graphics.Bitmap.createBitmap(px, s.iw, s.ih, android.graphics.Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    say("Düzenleyici için bellek yetersiz: " + Diag.memInfo())
                    return
                }
            }
        }
        navigate(Screen.CaptureEditor)
    }

    fun cancelCaptureEditor() {
        capture?.session?.let { it.box = it.startBox }
        if (screen == Screen.CaptureEditor) back()
        capture?.session?.bitmap = null   // düzenleyiciden çıkınca büyük bitmap serbest kalır
        if (capture?.directEdit == true) discardCapture()   // doğrudan düzenlemede iptal = hiçbir şey değişmez
    }

    fun applyCaptureEditor() {
        if (screen == Screen.CaptureEditor) back()
        capture?.session?.bitmap = null
        if (capture?.directEdit == true) saveCapture()      // doğrudan düzenlemede Uygula = hemen kaydet
    }

    fun captureEditorPickFace(index: Int) {
        val s = capture?.session ?: return
        val f = s.faces.getOrNull(index) ?: return
        s.faceFound = true
        s.moveBox(ImageOps.defaultBox(s.iw, s.ih, f, settings))
    }

    fun captureEditorResetToFace() {
        val s = capture?.session ?: return
        val f = s.faces.firstOrNull()
        s.moveBox(ImageOps.defaultBox(s.iw, s.ih, f, settings))
    }

    // ------------------------------------------------------------------ araçlar (dışa aktarma, yedek, adlar)
    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())

    /** Çekilmiş öğrencilerin fotoğraflarını `12345.jpg` adlarıyla tek bir ZIP'e yazar (e-Okul yüklemesi için). */
    fun exportEokulZip(classId: String) {
        val cls = classById(classId) ?: return
        exportZip(listOf(cls), sanitizedZipName(cls.name), "${cls.name}")
    }

    /**
     * Tüm sınıflar tek ZIP'te, kaydedilen düzenle aynı klasör yapısıyla: `6A/12345-Ad Soyad.jpg`, ayrıca `rapor.csv`
     * (sınıf, numara, ad, durum, kalite puanı). Arşivleme / başka sisteme taşıma ve kontrol için.
     */
    fun exportAllFoldersZip() {
        exportZip(classes.toList(), "Tum-siniflar-klasorlu-${stamp()}.zip", "sınıf klasörleriyle", folders = true)
    }

    private fun csvCell(v: String): String {
        val t = v.replace("\"", "\"\"")
        return if (t.contains(';') || t.contains('"') || t.contains('\n')) "\"$t\"" else t
    }

    private fun sanitizedZipName(className: String) = RosterParser.sanitizeFileName(className) + "-eokul.zip"

    private fun exportZip(snap: List<SchoolClass>, zipName: String, label: String, folders: Boolean = false) {
        val rootStr = settings.rootUri
        if (rootStr == null) {
            say("Önce kayıt klasörünü seçin")
            return
        }
        val root = Uri.parse(rootStr)
        viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    // Yinelenen numaralar yazmadan önce belirlenir.
                    val seen = HashSet<String>()
                    val dups = ArrayList<String>()
                    val items = ArrayList<Pair<String, File>>()
                    for (cls in snap) for (st in cls.students) {
                        if (!st.captured) continue
                        val f = Naming.privatePhoto(ctx, cls.id, st.no)
                        if (!f.exists()) continue
                        // Düz düzende numara tüm okulda tek olmalı; klasörlü düzende yalnızca sınıf içinde.
                        val key = if (folders) cls.id + "|" + st.no else st.no
                        if (!seen.add(key)) {
                            dups.add("${st.no} (${cls.name})")
                            continue
                        }
                        val entry = if (folders)
                            RosterParser.sanitizeFileName(cls.name) + "/" + RosterParser.studentStem(st.no, st.name) + ".jpg"
                        else st.no + ".jpg"
                        items.add(entry to f)
                    }
                    if (items.isNotEmpty()) {
                        Output.writeStream(ctx, root, emptyList(), zipName, "application/zip") { out ->
                            java.util.zip.ZipOutputStream(out).use { zip ->
                                if (folders) {
                                    // Excel'in Türkçe karakterleri doğru açması için UTF-8 BOM + noktalı virgül
                                    zip.putNextEntry(java.util.zip.ZipEntry("rapor.csv"))
                                    val sb = StringBuilder("\uFEFFSınıf;No;Ad Soyad;Durum;Kalite puanı;Tekrar çekilmeli\r\n")
                                    for (cls in snap) for (st in cls.students) {
                                        sb.append(csvCell(cls.name)).append(';').append(csvCell(st.no)).append(';')
                                            .append(csvCell(st.name)).append(';')
                                            .append(if (st.captured) "Çekildi" else "Bekliyor").append(';')
                                            .append(if (st.captured && st.score >= 0) st.score.toString() else "").append(';')
                                            .append(if (st.retake) "Evet" else "").append("\r\n")
                                    }
                                    zip.write(sb.toString().toByteArray(Charsets.UTF_8))
                                    zip.closeEntry()
                                }
                                for ((entry, f) in items) {
                                    zip.putNextEntry(java.util.zip.ZipEntry(entry))
                                    f.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                    items.size to dups
                }
                val (n, dups) = res
                say(
                    if (n == 0) "Dışa aktarılacak fotoğraf yok"
                    else "✓ $n fotoğraf → $zipName ($label)" +
                        if (dups.isNotEmpty()) "\n⚠ Aynı numara tekrar ettiği için atlandı: ${dups.take(3).joinToString(", ")}" +
                            if (dups.size > 3) " …(${dups.size})" else "" else ""
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                say("ZIP oluşturulamadı: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Uygulamanın kayıt klasörüne yazdığı dosyalar tek bir önekle ayırt edilir (başka programların yedekleriyle karışmasın):
     * EOkulStudio_Yedek_2026-11-12_06-39-10.zip. Eski "Yedek-….zip" dosyaları geri yüklenebilir (içerik denetimi addan bağımsızdır).
     */
    private fun artifactName(kind: String, ext: String): String =
        "EOkulStudio_${kind}_" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date()) + "." + ext

    /** Yedek ZIP'inin özeti (geri yüklemeden önce kullanıcıya gösterilir). */
    data class BackupInfo(val classes: Int, val students: Int, val captured: Int, val photos: Int, val createdMs: Long?)

    /** Yedeği geri yüklemeden önce içeriğini okur; geçerli bir yedek değilse null (mevcut veriye dokunulmaz). */
    suspend fun inspectBackup(uri: Uri): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            var json: String? = null
            var manifest: String? = null
            var photos = 0
            var count = 0
            var totalBytes = 0L
            val seenEntries = HashSet<String>()
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                java.util.zip.ZipInputStream(ins).use { zip ->
                    val sink = ByteArray(16 * 1024)
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.isDirectory) continue
                        if (++count > RESTORE_MAX_ENTRIES) throw IOException("Çok fazla dosya")
                        if (!seenEntries.add(e.name)) throw IOException("Yinelenen dosya yolu")
                        val isRoster = e.name == "roster.json"
                        val isManifest = e.name == "yedek.json"
                        val isPhoto = e.name.startsWith("photos/")
                        if (isPhoto) {
                            val rel = e.name.removePrefix("photos/")
                            if (rel.isBlank() || rel.startsWith('/') || rel.contains('\\') ||
                                rel.split('/').any { it == ".." || it == "." || it.isBlank() }
                            ) throw IOException("Geçersiz fotoğraf yolu")
                            photos++
                        }
                        val bos = if (isRoster || isManifest) java.io.ByteArrayOutputStream() else null
                        var size = 0L
                        while (true) {
                            val n = zip.read(sink)
                            if (n < 0) break
                            size += n
                            totalBytes += n
                            if (totalBytes > RESTORE_MAX_TOTAL) throw IOException("Yedek toplam boyut sınırını aşıyor")
                            if ((isRoster || isManifest) && size > RESTORE_MAX_ROSTER) throw IOException("Liste veya manifest dosyası beklenenden büyük")
                            if (isPhoto && size > RESTORE_MAX_PHOTO) throw IOException("Fotoğraf dosyası beklenenden büyük")
                            bos?.write(sink, 0, n)
                        }
                        if (isRoster) json = bos?.toString("UTF-8")
                        else if (isManifest) manifest = bos?.toString("UTF-8")
                    }
                }
            } ?: throw IOException("Dosya açılamadı")
            val parsed = store.parseStrict(json ?: throw IOException("roster.json yok"))
            if (parsed.isEmpty()) return@withContext null
            validateRestoredRoster(parsed)
            val manifestObject = try {
                org.json.JSONObject(manifest ?: throw IOException("yedek.json yok"))
            } catch (_: Exception) {
                return@withContext null
            }
            if (manifestObject.optString("app") != "E-Okul Foto Studio") return@withContext null
            if (manifestObject.optInt("classes", -1) != parsed.size ||
                manifestObject.optInt("students", -1) != parsed.sumOf { it.students.size } ||
                manifestObject.optInt("photos", -1) != photos
            ) return@withContext null
            val created = manifestObject.optLong("created", 0L).takeIf { it > 0 }
            BackupInfo(parsed.size, parsed.sumOf { it.students.size }, parsed.sumOf { c -> c.students.count { it.captured } }, photos, created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Yedek ZIP'ini kayıt klasörüne yazar ve geri okuyup doğrular: roster.json + photos klasörü + yedek.json (özet).
     * Üçlü döner: dosya adı, fotoğraf sayısı, çekilmiş öğrenci sayısı. Hata olursa IOException.
     */
    private suspend fun writeBackup(root: Uri, snap: List<SchoolClass>, prefix: String): Triple<String, Int, Int> =
        withContext(Dispatchers.IO) {
            val rosterBytes = store.encode(snap).toByteArray(Charsets.UTF_8)
            store.parseStrict(String(rosterBytes, Charsets.UTF_8))
            val photosDir = File(ctx.filesDir, "photos").canonicalFile
            val photos = if (photosDir.exists()) photosDir.walkTopDown().filter { f ->
                f.isFile && !f.name.startsWith(".") && !f.name.endsWith(".tmp") && !f.name.endsWith(".old") &&
                    f.canonicalFile.path.startsWith(photosDir.path + File.separator)
            }.toList() else emptyList()
            val captured = snap.sumOf { c -> c.students.count { it.captured } }
            val manifest = org.json.JSONObject()
                .put("app", "E-Okul Foto Studio")
                .put("format", 1)
                .put("appVersion", try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" })
                .put("created", System.currentTimeMillis())
                .put("classes", snap.size)
                .put("students", snap.sumOf { it.students.size })
                .put("captured", captured)
                .put("photos", photos.size)
                .toString()
            val n = artifactName(prefix, "zip")
            val uri = Output.writeStream(ctx, root, emptyList(), n, "application/zip") { out ->
                java.util.zip.ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("yedek.json"))
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.putNextEntry(java.util.zip.ZipEntry("roster.json"))
                    zip.write(rosterBytes)
                    zip.closeEntry()
                    for (f in photos) {
                        val rel = f.relativeTo(ctx.filesDir).path.replace('\\', '/')
                        zip.putNextEntry(java.util.zip.ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            // Yazılan ZIP yeniden açılır; tüm girdiler, manifest ve roster snapshot'ı doğrulanır.
            try {
                var entries = 0
                var rosterText: String? = null
                var manifestText: String? = null
                val actualNames = HashSet<String>()
                val expectedNames = HashSet<String>().apply {
                    add("yedek.json")
                    add("roster.json")
                    photos.forEach { add(it.relativeTo(ctx.filesDir).path.replace('\\', '/')) }
                }
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    java.util.zip.ZipInputStream(ins).use { z ->
                        val sink = ByteArray(16 * 1024)
                        while (true) {
                            val entry = z.nextEntry ?: break
                            entries++
                            if (!actualNames.add(entry.name)) throw IOException("Yedekte yinelenen ZIP girdisi")
                            if (entry.name == "roster.json" || entry.name == "yedek.json") {
                                val bos = java.io.ByteArrayOutputStream()
                                while (true) { val read = z.read(sink); if (read < 0) break; bos.write(sink, 0, read) }
                                if (entry.name == "roster.json") rosterText = bos.toString("UTF-8")
                                else manifestText = bos.toString("UTF-8")
                            } else {
                                while (z.read(sink) >= 0) { /* CRC kontrolü için girdinin sonuna kadar oku */ }
                            }
                        }
                    }
                } ?: throw IOException("Yedek dosyası geri okunamadı")
                if (entries != expectedNames.size || actualNames != expectedNames) {
                    throw IOException("Yedek girdileri doğrulanamadı ($entries/${expectedNames.size})")
                }
                val restoredSnapshot = store.parseStrict(rosterText ?: throw IOException("Yedek roster dosyası okunamadı"))
                if (store.encode(restoredSnapshot) != store.encode(snap)) throw IOException("Yedek öğrenci listesi snapshot ile uyuşmuyor")
                val m = org.json.JSONObject(manifestText ?: throw IOException("Yedek manifest okunamadı"))
                if (m.optInt("classes", -1) != snap.size || m.optInt("students", -1) != snap.sumOf { it.students.size } ||
                    m.optInt("photos", -1) != photos.size
                ) throw IOException("Yedek manifest doğrulaması başarısız")
            } catch (e: Exception) {
                Output.deletePath(ctx, root, emptyList(), n)
                throw e
            }
            Triple(n, photos.size, captured)
        }

    /** Tanılama raporu için önerilen dosya adı. */
    fun reportFileName(): String = artifactName("Rapor", "txt")

    /** Tanılama raporunu kullanıcının seçtiği konuma (Dosya seçici: İndirilenler vb.) yazar; öğrenci adı içermez. */
    fun saveReportTo(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = Ops.reportText()
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Dosya açılamadı")
                }
                say("✓ Rapor kaydedildi. Dosyayı geliştiriciye iletin; öğrenci adı içermez.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Ops.error("report", e)
                say("Rapor yazılamadı: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Rapor metni (paylaşma için); en fazla ~120 bin karakter. */
    suspend fun reportTextForShare(): String = withContext(Dispatchers.IO) { Ops.reportText().take(120_000) }

    fun clearOpsLog() {
        Ops.clear()
        say("İşlem kaydı temizlendi")
    }

    /** Sınıf listesi + küçük fotoğraflar: kayıt klasörüne EOkulStudio_Yedek_tarih.zip (akış tabanlı, doğrulamalı). */
    fun backup() {
        val rootStr = settings.rootUri
        if (rootStr == null) {
            say("Önce kayıt klasörünü seçin")
            return
        }
        val root = Uri.parse(rootStr)
        viewModelScope.launch {
            try {
                saveMutex.withLock {
                val tb = android.os.SystemClock.elapsedRealtime()
                val snap = stableRosterSnapshot()
                val name = writeBackup(root, snap, "Yedek")
                Ops.log("op", "name", "backup", "ok", true, "ms", android.os.SystemClock.elapsedRealtime() - tb, "n", name.second)
                SettingsState.update(settings.copy(lastBackupMs = System.currentTimeMillis()))
                say("✓ Yedek alındı ve doğrulandı: ${name.first}\n${name.second} fotoğraf · ${name.third} çekilmiş öğrenci.\n" +
                    "Dosya kayıt klasöründe durur; uygulamayı silseniz de kalır. Tam çözünürlüklü orijinaller yedeğe girmez, kayıt klasöründe kalır.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Ops.log("op", "name", "backup", "ok", false, "err", (e.message ?: e.javaClass.simpleName).take(200))
                say("Yedek alınamadı: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Yedek ZIP'inden sınıfları ve küçük fotoğrafları geçici alana çıkarır.
     * Öğrenci listesi ve fotoğraf klasörü işlem günlüğüyle birlikte devreye alınır.
     */
    fun restore(uri: Uri) {
        if (pendingCaptureOriginals.isNotEmpty()) {
            say("Fotoğraf kaydı devam ediyor. Kayıt tamamlandıktan sonra geri yüklemeyi yeniden başlatın.")
            return
        }
        if (recoveryBlocked) {
            say("Önceki kurtarma işlemi tamamlanmadığı için geri yükleme başlatılamıyor. Kurtarma dosyalarını koruyun.")
            return
        }
        if (isRestoring) return
        isRestoring = true
        // Başlamış eski kayıt görevlerinin geri yüklenen listeyi sonradan ezmesini engelle.
        rosterVersion.incrementAndGet()
        viewModelScope.launch {
            try {
                saveMutex.withLock {
                var safety: String? = null
                if (classes.isNotEmpty()) {
                    val rootStr = settings.rootUri ?: throw IOException(
                        "Önce kayıt klasörünü seçin (geri yükleme öncesinde mevcut verinin güvenlik yedeği alınır)"
                    )
                    safety = withContext(Dispatchers.IO) {
                        writeBackup(Uri.parse(rootStr), classes.toList(), "GeriYuklemeOncesi").first
                    }
                }

                val restored = withContext(Dispatchers.IO) {
                    storeMutex.withLock {
                        val base = ctx.filesDir
                        val newDir = File(base, "photos.new")
                        val preparedRoster = File(base, "roster.json.prepared")
                        newDir.deleteRecursively()
                        preparedRoster.delete()
                        if (!newDir.mkdirs() && !newDir.isDirectory) throw IOException("Geçici fotoğraf klasörü oluşturulamadı")

                        var json: String? = null
                        var manifestText: String? = null
                        var photoEntries = 0
                        var count = 0
                        var skipped = 0
                        var total = 0L
                        val seen = HashSet<String>()
                        try {
                            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Yedek dosyası açılamadı")
                            java.util.zip.ZipInputStream(input).use { zip ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    if (entry.isDirectory) continue
                                    if (++count > RESTORE_MAX_ENTRIES) throw IOException("Yedekte çok fazla dosya var")
                                    if (!seen.add(entry.name)) throw IOException("Yedekte yinelenen dosya yolu: ${entry.name}")

                                    val isRoster = entry.name == "roster.json"
                                    val isManifest = entry.name == "yedek.json"
                                    val isPhoto = entry.name.startsWith("photos/")
                                    if (!isRoster && !isManifest && !isPhoto) {
                                        // Tanınmayan içerik de toplam boyut sınırına dahildir.
                                        while (true) {
                                            val n = zip.read(buf)
                                            if (n < 0) break
                                            total += n
                                            if (total > RESTORE_MAX_TOTAL) throw IOException("Yedek toplam boyut sınırını aşıyor")
                                        }
                                        continue
                                    }
                                    if (isPhoto) photoEntries++

                                    val limit = if (isRoster || isManifest) RESTORE_MAX_ROSTER else RESTORE_MAX_PHOTO
                                    val target = if (isPhoto) {
                                        val relative = entry.name.removePrefix("photos/")
                                        if (relative.isBlank() || relative.startsWith('/') || relative.contains('\\') ||
                                            relative.split('/').any { it.isBlank() || it == "." || it == ".." }
                                        ) throw IOException("Yedekte geçersiz fotoğraf yolu")
                                        File(newDir, relative).canonicalFile.also {
                                            val root = newDir.canonicalFile
                                            if (!it.path.startsWith(root.path + File.separator)) {
                                                throw IOException("Yedekte geçersiz dosya yolu: ${entry.name}")
                                            }
                                        }
                                    } else null
                                    target?.parentFile?.mkdirs()
                                    val sink: java.io.OutputStream = target?.outputStream()
                                        ?: java.io.ByteArrayOutputStream()
                                    var size = 0L
                                    sink.use { output ->
                                        while (true) {
                                            val n = zip.read(buf)
                                            if (n < 0) break
                                            size += n
                                            total += n
                                            if (size > limit || total > RESTORE_MAX_TOTAL) {
                                                throw IOException("Yedek boyut sınırını aşıyor; geri yükleme durduruldu")
                                            }
                                            output.write(buf, 0, n)
                                        }
                                        if (isRoster) json = (output as java.io.ByteArrayOutputStream).toString("UTF-8")
                                        if (isManifest) manifestText = (output as java.io.ByteArrayOutputStream).toString("UTF-8")
                                    }

                                    if (target != null) {
                                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        android.graphics.BitmapFactory.decodeFile(target.path, options)
                                        if (options.outWidth <= 0 || options.outHeight <= 0) {
                                            if (!target.delete()) throw IOException("Bozuk fotoğraf temizlenemedi: ${entry.name}")
                                            skipped++
                                        }
                                    }
                                }
                            }

                            val text = json ?: throw IOException("Yedekte roster.json bulunamadı")
                            val parsed = store.parseStrict(text)
                            if (parsed.isEmpty()) throw IOException("Yedekte sınıf bulunamadı")
                            validateRestoredRoster(parsed)
                            val manifest = org.json.JSONObject(manifestText ?: throw IOException("Yedekte yedek.json bulunamadı"))
                            if (manifest.optString("app") != "E-Okul Foto Studio" ||
                                manifest.optInt("classes", -1) != parsed.size ||
                                manifest.optInt("students", -1) != parsed.sumOf { it.students.size } ||
                                manifest.optInt("photos", -1) != photoEntries
                            ) throw IOException("Yedek özeti ile dosya içeriği uyuşmuyor")
                            val stagedRoster = store.prepare(parsed)
                            RestoreTransaction.commit(ctx, store, newDir, stagedRoster)
                            Triple(parsed, skipped, count)
                        } catch (e: Throwable) {
                            newDir.deleteRecursively()
                            preparedRoster.delete()
                            throw e
                        }
                    }
                }

                classes.clear()
                classes.addAll(restored.first)
                rosterVersion.incrementAndGet()
                say(
                    "✓ ${restored.first.size} sınıf geri yüklendi" +
                        (if (restored.second > 0) " (${restored.second} bozuk fotoğraf atlandı)" else "") +
                        (if (safety != null) "\nÖnceki verinizin güvenlik kopyası: $safety" else "")
                )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (File(ctx.filesDir, "restore.transaction").exists()) recoveryBlocked = true
                Ops.error("restore", e)
                say("Geri yüklenemedi: ${e.message ?: e.javaClass.simpleName}. Mevcut verinin korunması için işlem geri alındı.")
            } finally {
                isRestoring = false
            }
        }
    }

    private fun validateRestoredRoster(list: List<SchoolClass>) {
        val classIds = HashSet<String>()
        for (cls in list) {
            if (cls.id.isBlank() || cls.name.isBlank()) throw IOException("Yedekte sınıf kimliği veya adı boş")
            if (!classIds.add(cls.id)) throw IOException("Yedekte yinelenen sınıf kimliği: ${cls.id}")
            val numbers = HashSet<String>()
            for (student in cls.students) {
                if (student.no.isBlank() || student.name.isBlank()) throw IOException("Yedekte öğrenci numarası veya adı boş")
                if (!numbers.add(student.no)) throw IOException("${cls.name} sınıfında yinelenen öğrenci numarası: ${student.no}")
            }
        }
    }

    // ------------------------------------------------------------------ veri tutarlılığı denetimi
    var auditReport by mutableStateOf<String?>(null)

    fun dismissAudit() {
        auditReport = null
    }

    /** Öğrenci kayıtları ile uygulama içi fotoğraf dosyaları arasındaki tutarsızlıkları raporlar (hiçbir şeyi değiştirmez). */
    fun auditData() {
        val snap = classes.toList()
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val missing = ArrayList<String>()
                val broken = ArrayList<String>()
                val orphans = ArrayList<String>()
                val dupNo = HashMap<String, MutableList<String>>()
                var checked = 0
                for (c in snap) {
                    val expected = HashSet<String>()
                    for (s in c.students) {
                        dupNo.getOrPut(s.no) { ArrayList() }.add(c.name)
                        val f = Naming.privatePhoto(ctx, c.id, s.no)
                        expected.add(f.name)
                        if (!s.captured) continue
                        checked++
                        if (!f.exists()) {
                            missing.add("${c.name} · ${s.no} ${s.name}")
                            continue
                        }
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(f.path, o)
                        if (o.outWidth != TARGET_W || o.outHeight != TARGET_H) broken.add("${c.name} · ${s.no} ${s.name}")
                    }
                    Naming.privateDir(ctx, c.id).listFiles()?.forEach { f ->
                        if (f.name !in expected) orphans.add("${c.name} · ${f.name}")
                    }
                }
                val dups = dupNo.filter { it.value.size > 1 }.map { "${it.key} → ${it.value.joinToString(", ")}" }
                fun block(title: String, items: List<String>): String =
                    if (items.isEmpty()) "✓ $title: yok"
                    else "⚠ $title: ${items.size}\n" + items.take(6).joinToString("\n") { "   • $it" } +
                        if (items.size > 6) "\n   … ve ${items.size - 6} tane daha" else ""
                val folder = try { folderAudit(snap) } catch (e: Throwable) { "Kayıt klasörü okunamadı: ${e.message ?: e.javaClass.simpleName}" }
                listOf(
                    "$checked fotoğraflı öğrenci kontrol edildi.",
                    folder,
                    block("Kaydı var, fotoğrafı yok", missing),
                    block("Bozuk veya yanlış boyutlu fotoğraf", broken),
                    block("Öğrencisi olmayan fotoğraf dosyası", orphans),
                    block("Birden çok sınıfta geçen numara", dups)
                ).joinToString("\n\n")
            }
            auditReport = report
        }
    }

    /**
     * Kayıt klasörü kontrolü (yalnızca okur): her sınıf için klasör var mı, çekilmiş öğrencilerin fotoğrafı ve orijinali klasörde mi,
     * yarım kalmış gizli (.tmp-/.old-) dosya var mı. Dosyalar beklenen yerde değilse kök klasördeki adlar gösterilir.
     */
    private fun folderAudit(snap: List<SchoolClass>): String {
        val rootStr = settings.rootUri ?: return "⚠ Kayıt klasörü seçilmemiş."
        val root = Uri.parse(rootStr)
        val lines = ArrayList<String>()
        lines.add("Kayıt klasörü: ${rootName ?: "?"}")
        var rootNames: List<String>? = null
        for (c in snap) {
            val captured = c.students.filter { it.captured }
            if (captured.isEmpty()) continue
            val dirName = RosterParser.sanitizeFileName(c.name)
            val main = Output.listNames(ctx, root, listOf(dirName))
            if (main == null) {
                if (rootNames == null) rootNames = Output.listNames(ctx, root, emptyList()) ?: emptyList()
                lines.add("⚠ ${c.name}: '$dirName' klasörü kayıt klasöründe YOK (${captured.size} fotoğraflı öğrenci). Kökte bulunanlar: " +
                    rootNames.take(15).joinToString(", ").ifEmpty { "(boş)" })
                continue
            }
            val origNames = Output.listNames(ctx, root, listOf(dirName, ORIGINALS_DIR))
            val mainSet = main.toHashSet()
            var noMain = 0
            var noOrig = 0
            for (s in captured) {
                val stem = s.savedStem ?: RosterParser.studentStem(s.no, s.name)
                if ("$stem.jpg" !in mainSet) noMain++
                if (origNames == null || origNames.none { it.startsWith("$stem.") }) noOrig++
            }
            val hidden = main.count { it.startsWith(".tmp-") || it.startsWith(".old-") } +
                (origNames?.count { it.startsWith(".tmp-") || it.startsWith(".old-") } ?: 0)
            lines.add(
                (if (noMain == 0 && noOrig == 0) "✓ " else "⚠ ") + "${c.name}: ${captured.size} çekilmiş · klasörde fotoğraf eksik: $noMain · " +
                    (if (origNames == null) "Orijinaller klasörü YOK" else "orijinal eksik: $noOrig") +
                    (if (hidden > 0) " · yarım kalmış gizli dosya: $hidden" else "") +
                    " (klasörde ${main.size} öğe)"
            )
        }
        if (lines.size == 1) lines.add("Çekilmiş öğrenci yok.")
        return lines.joinToString("\n")
    }

    /** Adı değişen öğrencilerin kayıt klasöründeki dosya adlarını güncel ad soyada göre yeniden adlandırır. */
    fun staleNameCount(classId: String): Int {
        val c = classById(classId) ?: return 0
        return c.students.count { it.captured && it.savedStem != null && it.savedStem != RosterParser.studentStem(it.no, it.name) }
    }

    fun syncFileNames(classId: String) {
        val cls = classById(classId) ?: return
        val rootStr = settings.rootUri ?: run {
            say("Önce kayıt klasörünü seçin")
            return
        }
        val root = Uri.parse(rootStr)
        viewModelScope.launch {
            val dir = RosterParser.sanitizeFileName(cls.name)
            var done = 0
            val results = withContext(Dispatchers.IO) {
                val out = ArrayList<Triple<String, String, String?>>()   // no, yeni stem, yeni orijinal uri
                for (st in cls.students) {
                    val old = st.savedStem ?: continue
                    val stem = RosterParser.studentStem(st.no, st.name)
                    if (!st.captured || old == stem) continue
                    if (Output.renameFile(ctx, root, listOf(dir), "$old.jpg", "$stem.jpg")) {
                        val nu = st.origUri?.let { Output.renameUri(ctx, Uri.parse(it), stem) }
                        out.add(Triple(st.no, stem, nu?.toString() ?: st.origUri))
                    }
                }
                out
            }
            for ((no, stem, orig) in results) {
                updateStudent(classId, no) { it.copy(savedStem = stem, origUri = orig) }
                done++
            }
            say(if (done == 0) "Güncellenecek dosya yok" else "✓ $done dosya yeniden adlandırıldı")
        }
    }

    // ------------------------------------------------------------------ kaydetme
    private class Saved(val origUri: String?, val stem: String, val warnings: List<String> = emptyList())

    fun saveCapture() {
        if (isRestoring) {
            say("Geri yükleme sürerken fotoğraf kaydedilemez")
            return
        }
        val cap = capture ?: return
        val s = cap.session ?: return
        if (cap.saving) return
        val px = s.pixels ?: return
        val rootStr = settings.rootUri
        if (rootStr == null) {
            say("Önce kayıt klasörünü seçin")
            return
        }
        val root = Uri.parse(rootStr)
        val cls = classById(cap.classId) ?: return
        val st = cls.students.firstOrNull { it.no == cap.no } ?: return
        val set = settings
        val box = s.box.fitInside(s.iw, s.ih)
        val iw = s.iw
        val ih = s.ih
        val faceCount = s.faces.size
        val warned = cap.warned
        val newScore = cap.score
        cap.saving = true
        val oid = Ops.id(cap.no)
        Ops.log(
            "save_start", "id", oid, "score", newScore, "hard", cap.hardCodes, "soft", cap.softCodes, "auto", cap.auto,
            "reviewMs", if (cap.shownAt > 0) android.os.SystemClock.elapsedRealtime() - cap.shownAt else -1, "retake", warned
        )

        // Hız: öğrenci hemen "çekildi" sayılıp sıradakine geçilir; dosyalar arka planda (sırayla) yazılır.
        // Yazma başarısız olursa öğrenci eski durumuna döner ve kullanıcı açıkça uyarılır.
        val before = st
        pendingCaptureOriginals[captureKey(cap.classId, cap.no)] = before
        updateStudentTransient(cap.classId, cap.no) { student ->
            student.copy(
                captured = true, updatedAt = System.currentTimeMillis(), box = box, boxW = iw, boxH = ih, faceCount = faceCount,
                retake = warned,
                score = if (newScore >= 0) newScore else student.score
            )
        }
        capture = null
        afterSave(cap.classId, cap.no, set)

        viewModelScope.launch {
            saveMutex.withLock {
                val tSave = android.os.SystemClock.elapsedRealtime()
                try {
                    val saved = withContext(Dispatchers.IO) { writeAll(root, cls, st, cap, px, iw, ih, box, set) }
                    Ops.log("save", "id", oid, "ok", true, "ms", android.os.SystemClock.elapsedRealtime() - tSave, "warn", saved.warnings.size)
                    pendingCaptureOriginals.remove(captureKey(cap.classId, cap.no))
                    updateStudent(cap.classId, cap.no) {
                        // updatedAt yenilenir: küçük resim dosya yazıldıktan sonra yeniden okunur
                        it.copy(updatedAt = System.currentTimeMillis(), origUri = saved.origUri, savedStem = saved.stem)
                    }
                    cap.temp?.delete()
                    if (saved.warnings.isNotEmpty()) say("⚠ ${st.name}: fotoğraf kaydedildi ancak " + saved.warnings.joinToString("; "))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Ops.log(
                        "save", "id", oid, "ok", false, "ms", android.os.SystemClock.elapsedRealtime() - tSave,
                        "err", (e.message ?: e.javaClass.simpleName).take(200)
                    )
                    pendingCaptureOriginals.remove(captureKey(cap.classId, cap.no))
                    updateStudent(cap.classId, cap.no) { before }
                    say("⚠ ${st.name} (${st.no}) kaydedilemedi: ${e.message ?: e.javaClass.simpleName}. Bu öğrenciyi yeniden çekin.")
                }
            }
        }
    }

    /** Sınıf bitince kısa özet: kaç fotoğraf, kalite ortalaması, düşük puanlılar. */
    private fun classSummary(cls: SchoolClass?): String {
        if (cls == null) return "🎉 Sınıftaki tüm fotoğraflar çekildi"
        val total = cls.students.size
        val scored = cls.students.filter { it.captured && it.score >= 0 }
        val low = cls.students.count { it.captured && it.score in 0 until 70 }
        val sb = StringBuilder("🎉 ${cls.name}: $total/$total fotoğraf çekildi")
        if (scored.isNotEmpty()) sb.append("\nKalite ortalaması ${scored.map { it.score }.average().toInt()}")
        if (low > 0) sb.append("\n⚠ $low düşük puanlı fotoğraf var; sınıf ekranında \"⚠ Düşük\" çipinden bakıp yeniden çekebilirsiniz.")
        else if (scored.isNotEmpty()) sb.append("\nDüşük puanlı fotoğraf yok.")
        return sb.toString()
    }

    private fun afterSave(classId: String, no: String, set: Settings) {
        if (screen == Screen.Review) back()
        val top = screen
        if (top is Screen.Camera) {
            val cls = classById(classId)
            val next = if (set.serialMode && cls != null) nextUncaptured(cls, no) else null
            if (next != null) {
                replace(Screen.Camera(classId, next))
            } else {
                back()
                say(if (set.serialMode) classSummary(cls) else "✓ Kaydedildi")
            }
        } else {
            say("✓ Kaydedildi")
        }
    }

    private fun writePrivatePhotoAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        val tmp = File(target.parentFile, ".${target.name}.$token.tmp")
        val old = File(target.parentFile, ".${target.name}.$token.old")
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (tmp.length() != bytes.size.toLong()) throw IOException("Geçici fotoğraf eksik yazıldı")
            if (target.exists() && !target.renameTo(old)) {
                throw IOException("Eski uygulama içi fotoğraf korunamadı; mevcut dosya değiştirilmedi")
            }
            try {
                try {
                    java.nio.file.Files.move(
                        tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                if (target.length() != bytes.size.toLong()) throw IOException("Kaydedilen fotoğraf doğrulanamadı")
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(target.path, options)
                if (options.outWidth != TARGET_W || options.outHeight != TARGET_H) {
                    throw IOException("Kaydedilen küçük fotoğrafın boyutu geçersiz")
                }
                old.delete()
            } catch (e: Exception) {
                target.delete()
                if (old.exists() && !old.renameTo(target)) {
                    throw IOException("Yeni fotoğraf kaydedilemedi; eski fotoğraf .old dosyasında korundu", e)
                }
                throw e
            }
        } finally {
            tmp.delete()
        }
    }

    private fun compactOriginal(px: IntArray, w: Int, h: Int): ByteArray {
        val k = minOf(1.0, ORIG_MAX_SIDE.toDouble() / maxOf(w, h))
        val ow = maxOf(1, Math.round(w * k).toInt())
        val oh = maxOf(1, Math.round(h * k).toInt())
        // Arşiv kopyası: yerel (native) ölçekleme + JPEG, Java Lanczos'tan çok daha hızlı
        val full = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val bmp = if (k < 1.0) android.graphics.Bitmap.createScaledBitmap(full, ow, oh, true) else full
        if (bmp !== full) full.recycle()
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    private fun writeAll(
        root: Uri, cls: SchoolClass, st: Student, cap: CaptureState, px: IntArray,
        iw: Int, ih: Int, box: Box, set: Settings
    ): Saved {
        val stem = RosterParser.studentStem(st.no, st.name)
        val clsDir = RosterParser.sanitizeFileName(cls.name)
        val jpeg = ImageOps.render(ctx, px, iw, ih, box, set)
        run {   // Kaydetmeden önce çıktıyı doğrula: gerçekten 133x171 JPEG olarak açılıyor mu?
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            if (o.outWidth != TARGET_W || o.outHeight != TARGET_H) throw IOException("Çıktı doğrulanamadı (${o.outWidth}x${o.outHeight})")
        }

        Output.writePath(ctx, root, listOf(clsDir), "$stem.jpg", "image/jpeg", jpeg)

        // Ana çıktı (kayıt klasöründeki 133x171 JPEG) güvenle yazıldı. Bundan sonraki adımlar başarısız olursa
        // kayıt iptal edilmez; kullanıcıya açık bir uyarı verilir (ana fotoğraf zaten teslim edilmiş durumda).
        val warnings = ArrayList<String>()
        var origUri = st.origUri
        if (cap.fromSaved || !set.keepOriginals) Ops.log("orig", "ok", false, "err", if (cap.fromSaved) "skipped:fromSaved" else "skipped:keepOriginalsOff")
        if (!cap.fromSaved && set.keepOriginals) try {
            var bytes = ctx.contentResolver.openInputStream(cap.source)?.use { it.readBytes() }
                ?: throw IOException("Orijinal okunamadı")
            var mime = ctx.contentResolver.getType(cap.source) ?: "image/jpeg"
            var ext = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic", "image/heif" -> "heic"
                else -> "jpg"
            }
            // Orijinal kopya kompakt tutulur: uzun kenar en fazla 1600 px, JPEG %85 (~300-500 KB).
            if (bytes.size > ORIG_MAX_BYTES) {
                bytes = compactOriginal(px, iw, ih)
                mime = "image/jpeg"
                ext = "jpg"
            }
            val newUri = Output.writePath(ctx, root, listOf(clsDir, ORIGINALS_DIR), "$stem.$ext", mime, bytes)
            val old = st.origUri
            if (old != null && old != newUri.toString()) Output.deleteUri(ctx, Uri.parse(old))
            origUri = newUri.toString()
            Ops.log("orig", "ok", true, "bytes", bytes.size, "ext", ext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ana fotoğraf zaten yazıldı; orijinal başarısızsa kayıt geri alınmaz, uyarı verilir ve günlüğe yazılır.
            Ops.log("orig", "ok", false, "err", (e.toString()).take(200))
            warnings.add("orijinal fotoğraf saklanamadı (${e.message ?: e.javaClass.simpleName})")
        }

        val oldStem = st.savedStem
        if (oldStem != null && oldStem != stem) {
            Output.deletePath(ctx, root, listOf(clsDir), "$oldStem.jpg")
        }

        try {
            val priv = Naming.privatePhoto(ctx, cls.id, st.no)
            writePrivatePhotoAtomically(priv, jpeg)
        } catch (e: Exception) {
            warnings.add("uygulama içi kopya güvenli biçimde yazılamadı; küçük resim ve ZIP için yeniden çekin (${e.message ?: "hata"})")
        }
        return Saved(origUri, stem, warnings)
    }
}

private const val ORIG_MAX_SIDE = 1600
private const val ORIG_MAX_BYTES = 900_000

private const val PIN_ITERATIONS = 120_000

private const val RESTORE_MAX_ENTRIES = 50_000
private const val RESTORE_MAX_PHOTO = 5L * 1024 * 1024
private const val RESTORE_MAX_ROSTER = 30L * 1024 * 1024
private const val RESTORE_MAX_TOTAL = 800L * 1024 * 1024
