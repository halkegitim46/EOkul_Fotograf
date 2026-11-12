package com.eokul.studio

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

/**
 * Çıktı yazıcı.
 *  - Sınıf akışı: ana klasör (SAF ağacı) altında iç içe klasörler (Sınıf/, Sınıf/Orijinaller/, E-Okul/Sınıf/).
 *  - Toplu kırpma akışı: seçilen klasörün alt klasörüne veya Pictures/ altına (MediaStore).
 * Aynı adlı dosya varsa üzerine yazılır; böylece dosya adları (öğrenci numarası) bozulmaz.
 */
object Output {

    // ------------------------------------------------------------------ iç içe klasör yolu (sınıf akışı)

    private fun dirFor(ctx: Context, tree: Uri, segments: List<String>, create: Boolean): DocumentFile? {
        var cur = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("Ana klasöre erişilemiyor")
        for (seg in segments) {
            val found = cur.findFile(seg)
            cur = if (found != null && found.isDirectory) {
                found
            } else if (create) {
                cur.createDirectory(seg) ?: throw IOException("Klasör oluşturulamadı: $seg")
            } else {
                return null
            }
        }
        return cur
    }

    fun writePath(
        ctx: Context, tree: Uri, segments: List<String>, name: String, mime: String, bytes: ByteArray
    ): Uri = writeStream(ctx, tree, segments, name, mime) { it.write(bytes) }

    /**
     * Güvenli akış yazımı.
     * Yeni içerik önce geçici dosyaya yazılır ve geri okunarak doğrulanır.
     * Mevcut hedef varsa önce benzersiz bir yedek adına taşınır; yeni dosya doğrulanmadan
     * yedek silinmez. Sağlayıcı yeniden adlandırmayı desteklemiyorsa eski içerik ayrı bir
     * güvenlik dosyasına kopyalanır ve hedef yerinde güncellenir.
     */
    fun writeStream(
        ctx: Context, tree: Uri, segments: List<String>, name: String, mime: String,
        writer: (java.io.OutputStream) -> Unit
    ): Uri {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\')) { "Geçersiz dosya adı" }
        val dir = dirFor(ctx, tree, segments, true) ?: throw IOException("Klasör bulunamadı")
        cleanupStaleTemps(dir)
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        val tmpName = ".tmp-$token-$name"
        val backupName = ".old-$token-$name"
        val tmp = dir.createFile(mime, tmpName) ?: throw IOException("Geçici dosya oluşturulamadı: $name")
        var backup: DocumentFile? = null
        var destination: DocumentFile? = null
        var committed = false
        // DocumentFile.renameTo başarılı olunca nesnenin URI'si YENİ dosyaya taşınır. Bundan sonra tmp.delete() çağrılırsa
        // yeni yazılan nihai dosya silinir. Bu bayrak, tmp nesnesi artık nihai dosya olduğunda onu silmeyi engeller.
        var tmpRenamed = false
        try {
            var written = 0L
            val raw = ctx.contentResolver.openOutputStream(tmp.uri, "wt")
                ?: throw IOException("Dosya yazılamadı: $name")
            raw.use { output ->
                val counting = object : java.io.OutputStream() {
                    override fun write(b: Int) { output.write(b); written++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { output.write(b, off, len); written += len }
                    override fun flush() = output.flush()
                    override fun close() = output.flush()
                }
                counting.use(writer)
                output.flush()
            }
            if (written <= 0L) throw IOException("Boş dosya yazılamaz: $name")
            verifyDocument(ctx, tmp, written)

            val old = dir.findFile(name)
            if (old != null) {
                if (!old.isFile) throw IOException("Hedef ad bir dosya değil: $name")
                if (!old.renameTo(backupName)) {
                    // Bazı SAF sağlayıcıları rename desteklemez. Bu durumda önce eski dosyanın
                    // doğrulanmış kopyası alınır; hedef yerinde güncellenir ve hata olursa geri yazılır.
                    val backupCopy = dir.createFile(mime, backupName)
                        ?: throw IOException("Mevcut dosya için güvenlik kopyası oluşturulamadı: $name")
                    try {
                        copyDocument(ctx, old, backupCopy)
                        backup = backupCopy
                        copyDocument(ctx, tmp, old)
                        verifyDocument(ctx, old, written)
                        committed = true
                        backupCopy.delete()
                        tmp.delete()
                        return old.uri
                    } catch (writeError: Exception) {
                        try {
                            copyDocument(ctx, backupCopy, old)
                            backupCopy.delete()
                            backup = null
                        } catch (restoreError: Exception) {
                            throw IOException(
                                "'$name' güncellenemedi. Eski içerik güvenlik kopyasında korundu: $backupName. " +
                                    "Geri yükleme hatası: ${restoreError.message}", writeError
                            )
                        }
                        throw writeError
                    }
                }
                backup = dir.findFile(backupName) ?: old
            }

            if (tmp.renameTo(name)) {
                tmpRenamed = true
                // DocumentFile.renameTo başarılıysa nesnenin URI'si yeni konuma güncellenir; klasörü yeniden taramaya gerek yok
                // (SAF'ta findFile her çağrıda klasörü listeler, kalabalık sınıf klasörlerinde yavaştır).
                destination = if (tmp.name == name) tmp else dir.findFile(name)
                    ?: throw IOException("Yeni dosya son konumunda bulunamadı")
            } else {
                // Hedef boşken sağlayıcı rename desteklemiyorsa yeni dosyaya kopyalanır.
                destination = dir.createFile(mime, name)
                    ?: throw IOException("Yeni dosya oluşturulamadı: $name")
                copyDocument(ctx, tmp, destination!!)
            }

            // İçerik geçici dosyada zaten tam okunarak doğrulandı; taşıma sonrası yalnızca varlık ve boyut denetlenir.
            verifyDocument(ctx, destination!!, written, fullRead = false)
            committed = true
            backup?.delete()
            // Kopyalama yolunda geçici dosya artık gereksiz; rename yolunda tmp zaten nihai dosyadır, silinmez.
            if (!tmpRenamed) try { tmp.delete() } catch (_: Exception) { }
            return destination!!.uri
        } catch (e: Exception) {
            if (!committed) {
                try { destination?.delete() } catch (_: Exception) { }
                // Eski hedef varsa yalnızca yeni hedef kaldırıldıktan sonra geri alınır.
                val old = backup ?: dir.findFile(backupName)
                if (old != null) {
                    val current = dir.findFile(name)
                    if (current != null) current.delete()
                    if (!old.renameTo(name)) {
                        throw IOException("'$name' yazılamadı ve eski dosya otomatik geri getirilemedi. Korunan yedek: $backupName", e)
                    }
                }
            }
            throw e
        } finally {
            if (!committed) {
                if (!tmpRenamed) try { tmp.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun copyDocument(ctx: Context, source: DocumentFile, target: DocumentFile) {
        var copied = 0L
        ctx.contentResolver.openInputStream(source.uri)?.use { input ->
            ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    copied += n
                }
                output.flush()
            } ?: throw IOException("Dosya kopyalama hedefi açılamadı: ${target.name}")
        } ?: throw IOException("Dosya kopyalama kaynağı açılamadı: ${source.name}")
        if (copied <= 0L) throw IOException("Boş dosya kopyalanamadı: ${source.name}")
        verifyDocument(ctx, target, copied)
    }

    /** Yarım kalmış yazımlardan artakalan ".tmp-" dosyaları (1 saatten eski) kullanıcının klasöründe birikmesin diye silinir. */
    private val cleanedDirs = HashSet<String>()

    private fun cleanupStaleTemps(dir: DocumentFile) {
        // Klasör başına süreç ömründe bir kez: her yazımda klasör listelemek yavaştır.
        val key = dir.uri.toString()
        synchronized(cleanedDirs) { if (!cleanedDirs.add(key)) return }
        try {
            val limit = System.currentTimeMillis() - 3_600_000L
            for (f in dir.listFiles()) {
                val n = f.name ?: continue
                if (n.startsWith(".tmp-") && f.isFile && f.lastModified() in 1 until limit) f.delete()
            }
        } catch (_: Exception) {
            // temizlik başarısızsa yazma yine de devam eder
        }
    }

    private fun verifyDocument(ctx: Context, file: DocumentFile, expectedLength: Long, fullRead: Boolean = true) {
        if (!file.exists() || file.length() != expectedLength) {
            throw IOException("Dosya boyutu doğrulanamadı: ${file.name}")
        }
        if (!fullRead) return
        var read = 0L
        ctx.contentResolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                read += n
            }
        } ?: throw IOException("Dosya geri okunamadı: ${file.name}")
        if (read != expectedLength) throw IOException("Dosya geri okuma doğrulaması başarısız: ${file.name}")
    }

    /** Klasördeki dosya/klasör adları (gizli olanlar dahil); klasör yoksa null. Yalnızca okur. */
    fun listNames(ctx: Context, tree: Uri, segments: List<String>): List<String>? = try {
        dirFor(ctx, tree, segments, false)?.listFiles()?.mapNotNull { it.name }
    } catch (e: Exception) {
        null
    }

    /** Kayıt klasörü altındaki bir klasörü içindekilerle birlikte siler; başarılıysa (veya zaten yoksa) true. */
    fun deleteDir(ctx: Context, tree: Uri, parent: List<String>, name: String): Boolean {
        return try {
            val d = dirFor(ctx, tree, parent, false)?.findFile(name) ?: return true
            d.delete()
        } catch (e: Exception) {
            false
        }
    }

    /** Varsa siler; yoksa sessizce geçer. */
    fun deletePath(ctx: Context, tree: Uri, segments: List<String>, name: String) {
        try {
            dirFor(ctx, tree, segments, false)?.findFile(name)?.delete()
        } catch (e: Exception) {
            // silinemeyen dosya kritik değil
        }
    }

    /**
     * Klasörü yeniden adlandırır. Eski klasör yoksa yapılacak bir şey yoktur (true). Hedef adla klasör varsa veya
     * sağlayıcı yeniden adlandırmayı başaramazsa false döner; çağıran bunu kullanıcıya bildirmelidir.
     */
    fun renameDir(ctx: Context, tree: Uri, parent: List<String>, oldName: String, newName: String): Boolean {
        return try {
            val dir = dirFor(ctx, tree, parent, false) ?: return true
            val old = dir.findFile(oldName) ?: return true
            if (dir.findFile(newName) != null) return false
            old.renameTo(newName)
        } catch (e: Exception) {
            false
        }
    }

    /** Klasördeki dosyayı yeniden adlandırır; başarılıysa true. */
    fun renameFile(ctx: Context, tree: Uri, segments: List<String>, oldName: String, newName: String): Boolean {
        return try {
            val dir = dirFor(ctx, tree, segments, false) ?: return false
            val f = dir.findFile(oldName) ?: return false
            val existing = dir.findFile(newName)
            if (existing == null) return f.renameTo(newName)
            // Hedefte dosya varsa önce onu geçici adla kenara al; yeni ad verilemezse geri döndür (veri kaybolmaz).
            val parked = "eski-$newName"
            dir.findFile(parked)?.delete()
            if (!existing.renameTo(parked)) return false
            if (f.renameTo(newName)) {
                dir.findFile(parked)?.delete()
                true
            } else {
                dir.findFile(parked)?.renameTo(newName)
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Tek dosyayı yeni uzantısız ad ile yeniden adlandırır (uzantı korunur); yeni URI veya null. */
    fun renameUri(ctx: Context, uri: Uri, newStem: String): Uri? {
        return try {
            val df = DocumentFile.fromSingleUri(ctx, uri) ?: return null
            val ext = (df.name ?: "").substringAfterLast('.', "jpg")
            if (df.renameTo("$newStem.$ext")) df.uri else null
        } catch (e: Exception) {
            null
        }
    }

    /** Uri gerçekten açılabiliyor mu? (dosya taşınmış/silinmişse false) */
    fun exists(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Klasörde `stem.*` adlı dosyayı, yoksa `prefix` ile başlayan ilk dosyayı bulur (yalnızca okur, klasör oluşturmaz). */
    fun findFile(ctx: Context, tree: Uri, segments: List<String>, stem: String, prefix: String): Uri? = try {
        val dir = dirFor(ctx, tree, segments, false)
        val files = dir?.listFiles()?.filter { it.isFile } ?: emptyList()
        (files.firstOrNull { it.name?.substringBeforeLast('.') == stem }
            ?: files.firstOrNull { it.name?.startsWith(prefix) == true })?.uri
    } catch (e: Exception) {
        null
    }

    fun deleteUri(ctx: Context, uri: Uri) {
        try {
            DocumentFile.fromSingleUri(ctx, uri)?.delete()
        } catch (e: Exception) {
            // yoksay
        }
    }

    // ------------------------------------------------------------------ toplu kırpma akışı

    fun write(ctx: Context, tree: Uri?, dirName: String, name: String, bytes: ByteArray) {
        if (tree != null) writePath(ctx, tree, listOf(dirName), name, "image/jpeg", bytes)
        else writeMedia(ctx, dirName, name, bytes)
    }

    fun locationLabel(tree: Uri?, rootName: String?, dirName: String): String =
        if (tree != null) "${rootName ?: "klasör"}/$dirName" else "Pictures/$dirName"

    private fun writeMedia(ctx: Context, dirName: String, name: String, bytes: ByteArray) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relPath = "${Environment.DIRECTORY_PICTURES}/$dirName/"

        var existing: Uri? = null
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.IS_PENDING}=0",
            arrayOf(name, relPath),
            null
        )?.use { c ->
            existing = if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
        }

        // Eski satırın üzerine yazmak yerine önce yeni pending kayıt oluşturulur.
        // Yeni içerik doğrulanmadan eski MediaStore girdisi silinmez.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val fresh = resolver.insert(collection, values) ?: throw IOException("Dosya oluşturulamadı: $name")
        var committed = false
        try {
            resolver.openOutputStream(fresh, "wt")?.use { it.write(bytes); it.flush() }
                ?: throw IOException("Dosya yazılamadı: $name")
            val size = resolver.openInputStream(fresh)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n }
                total
            } ?: throw IOException("Yeni dosya geri okunamadı: $name")
            if (size != bytes.size.toLong()) throw IOException("Yeni dosya eksik yazıldı: $name")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(fresh, done, null, null) != 1) throw IOException("Dosya yayımlanamadı: $name")
            committed = true
            existing?.let { old -> if (old != fresh) resolver.delete(old, null, null) }
        } catch (e: Exception) {
            if (!committed) resolver.delete(fresh, null, null)
            throw e
        }
    }

}
