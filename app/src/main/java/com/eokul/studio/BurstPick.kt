package com.eokul.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.eokul.studio.core.Burst
import com.eokul.studio.core.Lighting
import com.eokul.studio.core.Quality
import com.eokul.studio.core.Score
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Aynı öğrenci için art arda çekilen karelerden en iyisini seçer. Her kare ayrı ayrı çözümlenir: yüz bulunur,
 * gözlerin açıklığı ve gülümseme o karenin kendisinden okunur (önceki sürümde canlı önizlemedeki tek değer
 * tüm karelere yazılıyordu), netlik/ışık gerçek yüz bölgesinde ölçülür, arka ışık ve parlama cezalandırılır.
 */
object BurstPick {
    /** [eyeOpen]: çekim anındaki canlı önizleme değeri; yalnızca karede yüz çözümlenemezse yedek olarak kullanılır. */
    class Shot(val file: File, val eyeOpen: Float)

    /** İlk kare bu puanı geçerse kalan kareler çekilmez: çoğu öğrenci için çekim süresi ~3 kat kısalır. */
    const val GOOD_ENOUGH = 86

    /** Yalnızca seri çekimde kullanılır; çağrı arka plan iş parçacığından yapılır (Tasks.await bekler). */
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)   // göz/gülümseme sınıflandırması bu modda da çalışır; ~2x hızlı
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)   // göz açıklığı + gülümseme
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    /** Kare puanı (0..100). Okunamayan kare -1 alır. */
    fun score(shot: Shot): Int {
        var bmp: Bitmap? = null
        var rotated: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(shot.file.absolutePath, bounds)
            var sample = 1
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
            bmp = BitmapFactory.decodeFile(
                shot.file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return -1
            val deg = when (ExifInterface(shot.file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            rotated = if (deg == 0) bmp else Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true
            )
            val w = rotated.width
            val h = rotated.height
            // Yüz kılavuza oturduğu için kadrajın orta bölgesi yüzü içerir
            // Bu karedeki yüz (en büyük). Bulunamazsa kılavuzdaki orta bölge ve canlı göz değeri kullanılır.
            val face = try {
                com.google.android.gms.tasks.Tasks.await(detector.process(InputImage.fromBitmap(rotated, 0)), 4, TimeUnit.SECONDS)
                    .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            } catch (e: Throwable) {
                null
            }
            val box = if (face != null) {
                val r = face.boundingBox
                doubleArrayOf(
                    r.left.coerceIn(0, w - 1).toDouble(), r.top.coerceIn(0, h - 1).toDouble(),
                    r.right.coerceIn(1, w).toDouble(), r.bottom.coerceIn(1, h).toDouble()
                )
            } else doubleArrayOf(w * 0.22, h * 0.15, w * 0.78, h * 0.70)
            val px = IntArray(w * h)
            rotated.getPixels(px, 0, w, 0, 0, w, h)
            val q = Quality.analyze(px, w, h, box)
            val lo = face?.leftEyeOpenProbability
            val ro = face?.rightEyeOpenProbability
            val eye = if (lo != null && ro != null) Math.min(lo, ro) else shot.eyeOpen
            val smile = face?.smilingProbability ?: -1f
            val light = if (face != null) {
                try { Lighting.analyzeScaled(px, w, h, box[0], box[1], box[2], box[3])?.code ?: Lighting.OK } catch (e: Throwable) { Lighting.OK }
            } else Lighting.OK
            val base = Score.total(
                0.0, q.blurRatio, face?.headEulerAngleZ?.toDouble() ?: 0.0, face?.headEulerAngleY?.toDouble() ?: 0.0,
                q.brightness, q.contrast, eye.toDouble()
            )
            Burst.total(base, face != null, smile.toDouble(), eye.toDouble(), light)
        } catch (e: Throwable) {
            -1
        } finally {
            try { if (rotated != null && rotated !== bmp) rotated.recycle() } catch (_: Exception) { }
            try { bmp?.recycle() } catch (_: Exception) { }
        }
    }

    /**
     * Önceden hesaplanmış puanlarla en iyi kareyi döndürür; diğerlerini siler (yeniden çözümleme yok).
     * [scores] kareyle aynı sırada olmalı; eksikse ilk kare seçilir. Eşitlikte ilk kare.
     */
    fun bestOf(shots: List<Shot>, scores: List<Int>): File {
        if (shots.size == 1 || scores.size != shots.size) {
            for (i in 1 until shots.size) shots[i].file.delete()
            return shots[0].file
        }
        var best = 0
        for (i in shots.indices) if (scores[i] > scores[best]) best = i
        for (i in shots.indices) if (i != best) shots[i].file.delete()
        return shots[best].file
    }

    /** En yüksek puanlı kareyi döndürür; diğerlerini siler. Eşitlikte ilk kare. Hiçbiri okunamazsa ilk kare. */
    fun pickBest(shots: List<Shot>): File {
        if (shots.size == 1) return shots[0].file
        var best = 0
        var bestScore = -2
        for (i in shots.indices) {
            val sc = score(shots[i])
            if (sc > bestScore) {
                bestScore = sc
                best = i
            }
        }
        for (i in shots.indices) if (i != best) shots[i].file.delete()
        return shots[best].file
    }
}
