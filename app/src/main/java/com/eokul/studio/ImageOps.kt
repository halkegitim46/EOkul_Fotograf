package com.eokul.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.eokul.studio.core.Crop
import com.eokul.studio.core.Finish
import com.eokul.studio.core.HeadTop
import com.eokul.studio.core.JpegEncoder
import com.eokul.studio.core.Lanczos
import com.eokul.studio.core.Quality
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ML Kit yüz algılayıcı (model uygulamanın içinde gelir). */
object Faces {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)   // göz noktaları: kadraj ve eğiklik kontrolü
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    private val segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    /** Kişi maskesi (verilen bitmap boyutunda). Hata olursa null döner. */
    suspend fun segment(bmp: Bitmap): PersonMask? = suspendCancellableCoroutine { cont ->
        try {
            segmenter.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { mask ->
                    try {
                        val buf = mask.buffer
                        buf.rewind()
                        val n = mask.width * mask.height
                        val arr = FloatArray(n)
                        for (i in 0 until n) arr[i] = buf.float
                        cont.resume(PersonMask(mask.width, mask.height, arr))
                    } catch (e: Exception) {
                        cont.resume(null)
                    }
                }
                .addOnFailureListener { cont.resume(null) }
        } catch (e: Exception) {
            cont.resume(null)
        }
    }

    /** Yüz kutuları (verilen bitmap koordinatlarında). Hata olursa boş liste döner. */
    suspend fun detect(bmp: Bitmap): List<RawFace> = suspendCancellableCoroutine { cont ->
        try {
            detector.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { faces ->
                    val boxes = faces.map { f ->
                        val r = f.boundingBox
                        RawFace(
                            Box(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble()),
                            f.headEulerAngleZ, f.headEulerAngleY,
                            run {
                                val l = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position
                                val r2 = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position
                                if (l != null && r2 != null) doubleArrayOf(l.x.toDouble(), l.y.toDouble(), r2.x.toDouble(), r2.y.toDouble()) else null
                            }
                        )
                    }
                    cont.resume(boxes)
                }
                .addOnFailureListener {
                    cont.resume(emptyList())
                }
        } catch (e: Exception) {
            cont.resume(emptyList())
        }
    }
}

/** ML Kit yüzü: kutu + baş eğikliği (roll) ve dönüklüğü (yaw), derece. */
class RawFace(val box: Box, val roll: Float, val yaw: Float, val eyes: DoubleArray? = null)

/** Kişi/arka plan maskesi (0..1 güven değerleri). */
class PersonMask(val w: Int, val h: Int, val data: FloatArray)

object ImageOps {
    /** Çalışma görüntüsünün en uzun kenarı (bellek için); çıktı yalnızca 133x171 olduğundan yeterlidir. */
    const val MAX_SIDE = 2700
    const val THUMB_W = 160
    val THUMB_H: Int get() = (THUMB_W * TARGET_H.toDouble() / TARGET_W).roundToInt()
    private const val DETECT_SIDE = 1280

    @Volatile
    private var srgbIcc: ByteArray? = null

    fun srgbProfile(ctx: Context): ByteArray {
        srgbIcc?.let { return it }
        val bytes = ctx.assets.open("srgb.icc").use { it.readBytes() }
        srgbIcc = bytes
        return bytes
    }

    /**
     * Görüntüyü EXIF yönüne göre döndürülmüş, sRGB renk uzayına dönüştürülmüş (Display P3 vb. profiller
     * dahil) ve alfa kanalı beyaz üzerine birleştirilmiş opak ARGB bitmap olarak çözer.
     */
    fun decode(ctx: Context, uri: Uri): Bitmap {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: throw IOException("Dosya açılamadı")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }   // inJustDecodeBounds: sonuç null olur
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Resim çözülemedi")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2

        // Bellek yetmezse (büyük çekim + eşzamanlı işler) bir kademe daha küçük örnekleyerek yeniden dener:
        // kullanıcı hata yerine biraz düşük çözünürlüklü ama çalışan bir görüntü alır (çıktı zaten 133x171).
        var bmp: Bitmap? = null
        var attempt = 0
        while (bmp == null) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            try {
                bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: throw IOException("Resim çözülemedi")
            } catch (e: OutOfMemoryError) {
                if (++attempt > 2) throw e
                sample *= 2
                System.gc()
            }
        }

        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        var cur: Bitmap = bmp ?: throw IOException("Resim çözülemedi")
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(-90f)
                m.postScale(-1f, 1f)
            }
        }
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(cur, 0, 0, cur.width, cur.height, m, true)
            if (rotated !== cur) cur.recycle()
            cur = rotated
        }
        if (cur.hasAlpha()) {
            val flat = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(flat)
            c.drawColor(Color.WHITE)
            c.drawBitmap(cur, 0f, 0f, null)
            cur.recycle()
            cur = flat
        }
        return cur
    }

    /**
     * Yüzün üst kenarından yukarı doğru, kişi maskesi kesilene kadar çıkar; saç (veya şapka) tepesinin y'sini
     * [b] ile aynı koordinat sisteminde döndürür. Güvenilir değilse NaN.
     */
    private fun maskHeadTop(m: PersonMask, b: Box): Double {
        val fw = b.width
        val x1 = (b.cx - 0.35 * fw).toInt().coerceIn(0, m.w - 1)
        val x2 = (b.cx + 0.35 * fw).toInt().coerceIn(x1 + 1, m.w)
        val bandW = (x2 - x1).coerceAtLeast(1)
        fun frac(y: Int): Double {
            var n = 0
            val base = y * m.w
            for (x in x1 until x2) if (m.data[base + x] > 0.5f) n++
            return n.toDouble() / bandW
        }
        val y0 = b.y1.toInt().coerceIn(0, m.h - 1)
        // Yüzün üst kenarında kişi maskesi yoksa maske bu yüzde güvenilmezdir
        if (frac(minOf(m.h - 1, y0 + (b.height * 0.1).toInt())) < 0.6) return Double.NaN
        var top = y0
        var misses = 0
        var y = y0
        while (y > 0) {
            y--
            if (frac(y) >= 0.12) {
                top = y
                misses = 0
            } else {
                misses++
                if (misses >= 3) break
            }
        }
        val unit = Crop.faceUnit(b.toArray())
        val rise = b.y1 - top
        // Saç yüz üstünden en fazla ~1.1 yüz birimi yukarıda olabilir; aksi halde maske taşmıştır
        return if (rise > unit * 1.1) Double.NaN else top.toDouble()
    }

    fun pixels(bmp: Bitmap): IntArray {
        val a = IntArray(bmp.width * bmp.height)
        bmp.getPixels(a, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return a
    }

    /** Yüzleri bulur; her biri için saç tepesini tahmin eder. En büyük yüz başta. */
    suspend fun detectFaces(bmp: Bitmap, pixels: IntArray): List<FaceInfo> {
        val w = bmp.width
        val h = bmp.height
        val k = min(1.0, DETECT_SIDE.toDouble() / max(w, h))
        val det = if (k < 1.0) {
            Bitmap.createScaledBitmap(bmp, (w * k).roundToInt(), (h * k).roundToInt(), true)
        } else {
            bmp
        }
        val boxes = Faces.detect(det)
        val mask = if (boxes.isEmpty()) null else Faces.segment(det)
        if (det !== bmp) det.recycle()
        return boxes.map { rf ->
            val b = rf.box
            val box = Box(
                (b.x1 / k).coerceIn(0.0, w.toDouble()),
                (b.y1 / k).coerceIn(0.0, h.toDouble()),
                (b.x2 / k).coerceIn(0.0, w.toDouble()),
                (b.y2 / k).coerceIn(0.0, h.toDouble())
            )
            // 1) kişi maskesinden ölçülen saç tepesi (en güvenilir); 2) renk/arka plan analizi; 3) yüz oranı
            val fromMask = if (mask != null) maskHeadTop(mask, b) / k else Double.NaN
            val eyes = rf.eyes?.let { e -> doubleArrayOf(e[0] / k, e[1] / k, e[2] / k, e[3] / k) }
            if (!fromMask.isNaN()) {
                FaceInfo(box, fromMask, true, rf.roll, rf.yaw, eyes)
            } else {
                FaceInfo(box, HeadTop.estimate(pixels, w, h, box.toArray()), false, rf.roll, rf.yaw, eyes)
            }
        }.filter { it.box.width >= 8 && it.box.height >= 8 }
            .sortedByDescending { it.box.width * it.box.height }
    }

    fun defaultBox(w: Int, h: Int, face: FaceInfo?, s: Settings): Box {
        val p = s.cropParams()
        if (face != null && face.trusted) p.hairMin = 0.01   // ölçülen saç tepesi olduğu gibi kullanılır
        return Box.of(Crop.defaultCropBox(w, h, face?.box?.toArray(), face?.headTop ?: Double.NaN, p))
    }

    fun thumb(pixels: IntArray, w: Int, h: Int, box: Box): Bitmap {
        val px = Lanczos.cropResize(pixels, w, h, box.x1, box.y1, box.x2, box.y2, THUMB_W, THUMB_H)
        return Bitmap.createBitmap(px, THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
    }

    fun quality(pixels: IntArray, w: Int, h: Int, box: Box): Quality.Report =
        Quality.analyze(pixels, w, h, box.toArray())

    fun finalPixels(pixels: IntArray, w: Int, h: Int, box: Box): IntArray =
        Finish.render(pixels, w, h, box.toArray(), TARGET_W, TARGET_H, Finish.Opts())

    /** Standart boyutta (varsayılan 133x171) JPEG: 4:4:4, 300 DPI, isteğe bağlı sRGB profili, [Settings.minKb]–[Settings.maxKb] KB. */
    fun encode(ctx: Context, px: IntArray, s: Settings): ByteArray {
        val icc = if (s.embedSrgb) srgbProfile(ctx) else null
        return JpegEncoder.encodeFit(px, TARGET_W, TARGET_H, s.jpegQuality, 300, icc, s.minKb * 1024 + 200, s.maxKb * 1024)
    }

    fun render(ctx: Context, pixels: IntArray, w: Int, h: Int, box: Box, s: Settings): ByteArray =
        encode(ctx, finalPixels(pixels, w, h, box), s)

    /** Düzenleyici önizlemesi: gerçek çıktı bitmap'i ve JPEG boyutu (bayt). */
    fun preview(ctx: Context, pixels: IntArray, w: Int, h: Int, box: Box, s: Settings): Pair<Bitmap, Int> {
        val px = finalPixels(pixels, w, h, box)
        val bytes = encode(ctx, px, s)
        return Bitmap.createBitmap(px, TARGET_W, TARGET_H, Bitmap.Config.ARGB_8888) to bytes.size
    }
}
