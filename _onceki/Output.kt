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
     * Akış tabanlı güvenli yazma: içerik önce geçici dosyaya yazılır (büyük ZIP'ler belleğe alınmaz), boyutu doğrulanır;
     * eski dosya ancak yeni içerik güvenle yazıldıktan sonra değiştirilir.
     */
    fun writeStream(
        ctx: Context, tree: Uri, segments: List<String>, name: String, mime: String,
        writer: (java.io.OutputStream) -> Unit
    ): Uri {
        val dir = dirFor(ctx, tree, segments, true) ?: throw IOException("Klasör bulunamadı")
        val tmpName = "tmp-$name"
        dir.findFile(tmpName)?.delete()
        val tmp = dir.createFile(mime, tmpName) ?: throw IOException("Dosya oluşturulamadı: $name")
        var written = 0L
        try {
            val raw = ctx.contentResolver.openOutputStream(tmp.uri, "wt") ?: throw IOException("Dosya yazılamadı: $name")
            raw.use { o ->
                val counting = object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        o.write(b)
                        written++
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        o.write(b, off, len)
                        written += len
                    }
                }
                writer(counting)
                counting.flush()
            }
            if (written <= 0L || tmp.length() != written) throw IOException("Yazılan dosya eksik: $name")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        dir.findFile(name)?.delete()
        if (tmp.renameTo(name)) return dir.findFile(name)?.uri ?: tmp.uri
        // Sağlayıcı yeniden adlandırmayı desteklemiyorsa: geçici dosyanın içeriğini asıl dosyaya kopyala.
        val dst = dir.createFile(mime, name) ?: throw IOException("Dosya son adına taşınamadı: $name (geçici dosya: $tmpName)")
        val src = ctx.contentResolver.openInputStream(tmp.uri) ?: throw IOException("Geçici dosya okunamadı: $tmpName")
        src.use { i ->
            (ctx.contentResolver.openOutputStream(dst.uri, "wt") ?: throw IOException("Dosya yazılamadı: $name")).use { o -> i.copyTo(o) }
        }
        tmp.delete()
        return dst.uri
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
        val relPath = "${Environment.DIRECTORY_PICTURES}/$dirName"

        var existing: Uri? = null
        resolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$relPath%"),
            null
        )?.use { c ->
            existing = if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
        }

        val ex = existing
        if (ex != null) {
            try {
                val os = resolver.openOutputStream(ex, "wt")
                if (os != null) {
                    os.use { it.write(bytes) }
                    return
                }
            } catch (e: Exception) {
                // başka uygulamaya ait dosya olabilir: yeni dosya olarak eklenir
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Dosya oluşturulamadı: $name")
        try {
            val os = resolver.openOutputStream(uri, "wt") ?: throw IOException("Dosya yazılamadı: $name")
            os.use { it.write(bytes) }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
