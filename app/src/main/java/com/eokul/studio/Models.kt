package com.eokul.studio

import android.graphics.Bitmap
import android.net.Uri
import com.eokul.studio.core.Crop
import kotlin.math.abs

/** Hedef fotoğraf boyutu (e-Okul vesikalık). */
/** Ayarlardan okunur (varsayılan 133x171); standart değişirse tüm ekranlar yeni değeri kullanır. */
val TARGET_W: Int get() = SettingsState.current.photoW
val TARGET_H: Int get() = SettingsState.current.photoH
val RATIO: Double get() = TARGET_W.toDouble() / TARGET_H

/** Kırpma kutusu — çalışma görüntüsünün piksel koordinatlarında. */
data class Box(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val width: Double get() = x2 - x1
    val height: Double get() = y2 - y1
    val cx: Double get() = (x1 + x2) / 2.0
    val cy: Double get() = (y1 + y2) / 2.0

    fun toArray(): DoubleArray = doubleArrayOf(x1, y1, x2, y2)

    fun sameAs(o: Box?): Boolean =
        o != null && abs(x1 - o.x1) < 0.05 && abs(y1 - o.y1) < 0.05 &&
            abs(x2 - o.x2) < 0.05 && abs(y2 - o.y2) < 0.05

    fun fitInside(iw: Int, ih: Int): Box = of(Crop.fitInside(toArray(), iw, ih, RATIO))

    companion object {
        fun of(a: DoubleArray): Box = Box(a[0], a[1], a[2], a[3])
    }
}

/** Algılanan yüz. [headTop] saç tepesinin y koordinatıdır; bulunamazsa NaN. */
data class FaceInfo(val box: Box, val headTop: Double, val trusted: Boolean = false, val roll: Float = 0f, val yaw: Float = 0f,
    /** {solX, solY, sağX, sağY} göz noktaları (çalışma görüntüsü koordinatları); algılanamadıysa null. */
    val eyes: DoubleArray? = null
)

data class PhotoItem(
    val name: String,
    val uri: Uri,
    val width: Int = 0,
    val height: Int = 0,
    val box: Box? = null,
    val faceFound: Boolean = false,
    val faceCount: Int = 0,
    val edited: Boolean = false,
    val skipped: Boolean = false,
    val thumb: Bitmap? = null,
    val error: String? = null,
    val ready: Boolean = false
)

private val CHUNK = Regex("\\d+|\\D+")

/** "a2" < "a10" doğal sıralaması. */
fun compareNatural(a: String, b: String): Int {
    val ra = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
    val rb = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
    val n = minOf(ra.size, rb.size)
    for (i in 0 until n) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val tx = x.trimStart('0')
            val ty = y.trimStart('0')
            if (tx.length != ty.length) tx.length.compareTo(ty.length) else tx.compareTo(ty)
        } else {
            x.compareTo(y)
        }
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "bmp", "webp", "heic", "heif")

fun isImageName(name: String): Boolean =
    !name.startsWith(".") && name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** Çıktı dosya adları: uzantı .jpg olur, çakışmada _2, _3 eklenir. */
fun planOutputNames(names: List<String>): Map<String, String> {
    val used = HashSet<String>()
    val out = LinkedHashMap<String, String>()
    for (n in names) {
        val stem = n.substringBeforeLast('.', n)
        var cand = "$stem.jpg"
        var k = 2
        while (cand.lowercase() in used) {
            cand = "${stem}_$k.jpg"
            k++
        }
        used.add(cand.lowercase())
        out[n] = cand
    }
    return out
}

/** Aynı ada sahip kaynaklara _2, _3 ekler (farklı klasörlerden çoklu seçimde). */
fun uniqueNames(sources: List<Pair<String, Uri>>): List<Pair<String, Uri>> {
    val used = HashSet<String>()
    return sources.map { (name, uri) ->
        var cand = name
        var k = 2
        while (cand.lowercase() in used) {
            val stem = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            cand = "${stem}_$k$ext"
            k++
        }
        used.add(cand.lowercase())
        cand to uri
    }
}

/** e-Okul dosya adı kuralına (regex) uymayan çıktı adlarını döndürür. */
fun invalidOutputNames(outNames: List<String>, regex: String): List<String> {
    val pat = try {
        Regex(regex)
    } catch (e: Exception) {
        return emptyList()
    }
    return outNames.filter { n ->
        val stem = n.substringBeforeLast('.', n)
        val m = pat.find(stem)
        !(m != null && m.range.first == 0)
    }
}
