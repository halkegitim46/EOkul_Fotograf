package com.eokul.studio

import android.content.Context
import com.eokul.studio.core.RosterParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val ORIGINALS_DIR = "Orijinaller"
const val EOKUL_DIR = "E-Okul"

data class Student(
    val no: String,
    val name: String,
    val captured: Boolean = false,
    val updatedAt: Long = 0L,
    /** Son kırpma kutusu (çalışma görüntüsü koordinatlarında); orijinalden yeniden kırpmak için. */
    val box: Box? = null,
    /** [box]'ın tanımlandığı çalışma görüntüsünün boyutu; 0 = bilinmiyor (eski kayıt). Orijinal küçültülmüşse kutu ölçeklenir. */
    val boxW: Int = 0,
    val boxH: Int = 0,
    /** Orijinal fotoğrafın SAF URI'si (Orijinaller klasöründe). */
    val origUri: String? = null,
    /** Son kayıtta kullanılan dosya adı (uzantısız), örn. "12345-Ayşe Yılmaz". */
    val savedStem: String? = null,
    val faceCount: Int = 0,
    /** Kalite uyarısı aldığı veya elle işaretlendiği için yeniden çekilmesi gereken fotoğraf. */
    val retake: Boolean = false,
    /** Son kayıttaki kalite puanı (0-100); -1 = bilinmiyor (eski kayıt veya galeriden). */
    val score: Int = -1
)

data class SchoolClass(
    val id: String,
    val name: String,
    val students: List<Student> = emptyList()
) {
    val capturedCount: Int get() = students.count { it.captured }
}

enum class MergeMode { ADD, UPSERT, REPLACE }

data class MergeResult(val students: List<Student>, val added: Int, val updated: Int, val removed: Int)

/** Yapıştırılan satırları mevcut öğrenci listesiyle birleştirir; sonuç numaraya göre sıralanır. */
fun mergeStudents(existing: List<Student>, rows: List<RosterParser.Row>, mode: MergeMode): MergeResult {
    val byNo = LinkedHashMap<String, Student>()
    for (s in existing) byNo[s.no] = s
    var added = 0
    var updated = 0
    var removed = 0

    when (mode) {
        MergeMode.ADD -> {
            for (r in rows) {
                if (!byNo.containsKey(r.no)) {
                    byNo[r.no] = Student(r.no, r.name)
                    added++
                }
            }
        }
        MergeMode.UPSERT -> {
            for (r in rows) {
                val cur = byNo[r.no]
                if (cur == null) {
                    byNo[r.no] = Student(r.no, r.name)
                    added++
                } else if (cur.name != r.name) {
                    byNo[r.no] = cur.copy(name = r.name)
                    updated++
                }
            }
        }
        MergeMode.REPLACE -> {
            val keep = rows.map { it.no }.toHashSet()
            val replaced = LinkedHashMap<String, Student>()
            for (r in rows) {
                val cur = byNo[r.no]
                if (cur == null) {
                    replaced[r.no] = Student(r.no, r.name)
                    added++
                } else {
                    replaced[r.no] = cur.copy(name = r.name)
                    if (cur.name != r.name) updated++
                }
            }
            removed = byNo.keys.count { it !in keep }
            byNo.clear()
            byNo.putAll(replaced)
        }
    }
    val sorted = byNo.values.sortedWith { a, b -> RosterParser.compareNo(a.no, b.no) }
    return MergeResult(sorted, added, updated, removed)
}

/** Sıradaki fotoğrafı çekilmemiş veya tekrar çekilmesi işaretli öğrenci: önce [afterNo]'dan sonrakiler, yoksa baştan. */
fun nextUncaptured(cls: SchoolClass, afterNo: String?): String? {
    val list = cls.students
    val start = if (afterNo == null) 0 else list.indexOfFirst { it.no == afterNo } + 1
    for (i in start until list.size) if (!list[i].captured || list[i].retake) return list[i].no
    for (i in 0 until minOf(start, list.size)) {
        if ((!list[i].captured || list[i].retake) && list[i].no != afterNo) return list[i].no
    }
    return null
}

object Naming {
    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Uygulama içi 133x171 kopya: liste küçük resimleri ve yedek için. */
    fun privatePhoto(ctx: Context, classId: String, no: String): File =
        File(ctx.filesDir, "photos/${safe(classId)}/${safe(no)}.jpg")

    fun privateDir(ctx: Context, classId: String): File = File(ctx.filesDir, "photos/${safe(classId)}")
}

/** Sınıf ve öğrenci verisi (JSON, atomik yazım). */
class RosterStore(private val file: File) {

    private val bak: File get() = File(file.parentFile, file.name + ".bak")

    /**
     * Önce ana dosya, bozuksa son sağlam yedek (.bak) okunur. İkisi de okunamazsa bozuk dosya silinmez,
     * ".bozuk-<zaman>" adıyla saklanır; böylece sonraki kayıt veriyi sessizce boş listeyle ezmez.
     */
    /** Açılışta bir sorun olduysa kullanıcıya gösterilecek açıklama (kurtarma bilgisi); sorun yoksa null. */
    var loadNote: String? = null
        private set

    fun load(): List<SchoolClass> {
        loadNote = null
        if (!file.exists() && !bak.exists()) return emptyList()
        for ((i, f) in listOf(file, bak).withIndex()) {
            if (!f.exists()) continue
            try {
                val r = parseStrict(f.readText())
                if (i == 1) loadNote = "Ana liste dosyası okunamadı; son sağlam yedekten ${r.size} sınıf yüklendi. Bozuk dosya cihazda saklandı."
                return r
            } catch (e: Exception) {
                // sıradaki adayı dene
            }
        }
        try {
            if (file.exists()) file.copyTo(File(file.parentFile, file.name + ".bozuk-" + System.currentTimeMillis()), overwrite = true)
        } catch (e: Exception) {
            // yoksay
        }
        loadNote = "Liste dosyası bozuk olduğu için okunamadı ve yedek de yok. Bozuk dosya silinmeden cihazda saklandı. " +
            "Elinizde Ayarlar'dan aldığınız bir yedek varsa 'Geri yükle' ile döndürebilirsiniz."
        return emptyList()
    }

    /** Yedek geri yükleme gibi yerlerde: bozuk metinde boş liste döner. */
    fun parse(text: String): List<SchoolClass> = try {
        parseStrict(text)
    } catch (e: Exception) {
        emptyList()
    }

    fun parseStrict(text: String): List<SchoolClass> {
        return run {
            val root = JSONObject(text)
            val arr = root.getJSONArray("classes")
            val out = ArrayList<SchoolClass>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val sa = c.optJSONArray("students") ?: JSONArray()
                val students = ArrayList<Student>()
                for (j in 0 until sa.length()) {
                    val s = sa.optJSONObject(j) ?: continue
                    val b = s.optJSONArray("box")
                    students.add(
                        Student(
                            no = s.optString("no"),
                            name = s.optString("name"),
                            captured = s.optBoolean("captured"),
                            updatedAt = s.optLong("updatedAt"),
                            box = if (b != null && b.length() == 4) {
                                Box(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
                            } else {
                                null
                            },
                            boxW = s.optInt("boxW", 0),
                            boxH = s.optInt("boxH", 0),
                            origUri = if (s.has("origUri") && !s.isNull("origUri")) s.getString("origUri") else null,
                            savedStem = if (s.has("savedStem") && !s.isNull("savedStem")) s.getString("savedStem") else null,
                            faceCount = s.optInt("faceCount"),
                            retake = s.optBoolean("retake", false),
                            score = s.optInt("score", -1)
                        )
                    )
                }
                out.add(SchoolClass(c.optString("id"), c.optString("name"), students))
            }
            out
        }
    }


    /** Öğrenci listesini kararlı JSON metnine dönüştürür. Yedekleme de aynı serileştiriciyi kullanır. */
    fun encode(classes: List<SchoolClass>): String {
        val arr = JSONArray()
        for (c in classes) {
            val sa = JSONArray()
            for (s in c.students) {
                val o = JSONObject()
                    .put("no", s.no)
                    .put("name", s.name)
                    .put("captured", s.captured)
                    .put("updatedAt", s.updatedAt)
                    .put("faceCount", s.faceCount)
                    .put("retake", s.retake)
                    .put("score", s.score)
                s.box?.let { o.put("box", JSONArray(listOf(it.x1, it.y1, it.x2, it.y2))) }
                if (s.boxW > 0 && s.boxH > 0) o.put("boxW", s.boxW).put("boxH", s.boxH)
                s.origUri?.let { o.put("origUri", it) }
                s.savedStem?.let { o.put("savedStem", it) }
                sa.put(o)
            }
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("students", sa))
        }
        return JSONObject().put("version", 1).put("classes", arr).toString()
    }

    /** Yeni roster dosyasını mevcut dosyaya dokunmadan hazırlar ve diske senkronize eder. */
    fun prepare(classes: List<SchoolClass>): File {
        val text = encode(classes)
        parseStrict(text)
        file.parentFile?.mkdirs()
        val prepared = File(file.parentFile, file.name + ".prepared")
        writeSynced(prepared, text.toByteArray(Charsets.UTF_8))
        parseStrict(prepared.readText(Charsets.UTF_8))
        return prepared
    }

    /** Hazırlanmış roster dosyasını eski dosyayı koruyarak devreye alır. */
    fun commitPrepared(prepared: File): String? {
        var rollback: File? = null
        var hadOriginal = file.exists()
        return try {
            if (!prepared.exists()) throw java.io.IOException("Hazırlanmış liste dosyası bulunamadı")
            parseStrict(prepared.readText(Charsets.UTF_8))
            file.parentFile?.mkdirs()

            // Önceki sağlam dosyanın .bak kopyası ayrı geçici dosyaya yazılır.
            if (hadOriginal) {
                try {
                    parseStrict(file.readText(Charsets.UTF_8))
                    val bakTmp = File(file.parentFile, bak.name + ".tmp")
                    copySynced(file, bakTmp)
                    replaceFile(bakTmp, bak)
                } catch (_: Exception) {
                    // Bozuk ana dosya sağlam .bak dosyasının üzerine yazılmaz.
                }
                val rollbackTarget = File(file.parentFile, file.name + ".rollback")
                val rollbackTmp = File(file.parentFile, file.name + ".rollback.tmp")
                rollbackTarget.delete()
                rollbackTmp.delete()
                copySynced(file, rollbackTmp)
                replaceFile(rollbackTmp, rollbackTarget)
                rollback = rollbackTarget
            }

            val expected = parseStrict(prepared.readText(Charsets.UTF_8))
            replaceFile(prepared, file)
            val check = parseStrict(file.readText(Charsets.UTF_8))
            if (check.size != expected.size || check.sumOf { it.students.size } != expected.sumOf { it.students.size } || encode(check) != encode(expected)) {
                throw java.io.IOException("Kayıt doğrulaması başarısız")
            }
            rollback?.delete()
            null
        } catch (e: Exception) {
            try {
                if (hadOriginal && rollback?.exists() == true) replaceFile(rollback!!, file)
                else if (!hadOriginal) file.delete()
            } catch (_: Exception) {
                return "${e.message ?: e.javaClass.simpleName}; eski liste otomatik geri getirilemedi, .rollback dosyası korunmuş olabilir"
            }
            e.message ?: e.javaClass.simpleName
        } finally {
            if (prepared.exists()) prepared.delete()
        }
    }

    private fun writeSynced(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        java.io.FileOutputStream(target).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    private fun copySynced(source: File, target: File) {
        target.parentFile?.mkdirs()
        java.io.FileInputStream(source).use { input ->
            java.io.FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun replaceFile(source: File, target: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            // Aynı dosya sistemi içinde normal taşıma; çağıran rollback kopyasını tutar.
            java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Kaydeder; başarılıysa null, değilse hata açıklaması döner. */
    fun save(classes: List<SchoolClass>): String? {
        val prepared = try { prepare(classes) } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        return commitPrepared(prepared)
    }

}
