package com.eokul.studio

import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Kamera önizlemesindeki anlık yüz (0..1 normalleştirilmiş, dik görüntü koordinatları, ön kamerada aynalanmış).
 * [roll]: göz çizgisinden (yoksa ML Kit baş açısından) hesaplanan yan eğiklik, derece.
 * [eyeOpen]: iki gözün açıklık olasılığının küçüğü; negatif = bilinmiyor.
 * [sharp] (bulanıklık oranı), [bright], [contrast]: yüz bölgesi ölçümleri; NaN = henüz ölçülmedi.
 */
class LiveFace(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val roll: Float, val yaw: Float, val ts: Long,
    val eyeOpen: Float = -1f,
    val sharp: Float = Float.NaN,
    val bright: Float = Float.NaN,
    val contrast: Float = Float.NaN
) {
    val cx: Float get() = (x1 + x2) / 2f
    val cy: Float get() = (y1 + y2) / 2f
    val h: Float get() = y2 - y1
}

/** Telefonun kendi hareketi (jiroskop, rad/sn). Yüz sabit görünürken el titremesini ayrıca yakalar. */
class MotionMonitor(ctx: android.content.Context) : android.hardware.SensorEventListener {
    private val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    private val gyro = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)

    @Volatile
    var level = 0f
        private set

    /** Jiroskop yoksa engelleme yapılmaz. */
    val steady: Boolean get() = gyro == null || level < STEADY_LIMIT

    fun start() {
        if (gyro != null) sm?.registerListener(this, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        val v = e.values
        val m = Math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
        level = level * 0.8f + m * 0.2f   // yumuşatma
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    companion object {
        const val STEADY_LIMIT = 0.22f
    }
}

/** Yüz bölgesinin netlik / parlaklık / kontrast ölçümü (çerçeve dönüşü uygulanır). Başarısızsa null. */
fun measureFace(bmp: android.graphics.Bitmap, rot: Int, r: android.graphics.Rect): com.eokul.studio.core.Quality.Report? {
    var upright: android.graphics.Bitmap? = null
    var crop: android.graphics.Bitmap? = null
    return try {
        upright = if (rot == 0) bmp else {
            val m = android.graphics.Matrix()
            m.postRotate(rot.toFloat())
            android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val x = Math.max(0, r.left)
        val y = Math.max(0, r.top)
        val w = Math.min(upright.width, r.right) - x
        val h = Math.min(upright.height, r.bottom) - y
        if (w < 48 || h < 48) {
            null
        } else {
            crop = android.graphics.Bitmap.createBitmap(upright, x, y, w, h)
            val px = IntArray(w * h)
            crop.getPixels(px, 0, w, 0, 0, w, h)
            com.eokul.studio.core.Quality.analyze(px, w, h, doubleArrayOf(0.0, 0.0, w.toDouble(), h.toDouble()))
        }
    } catch (e: Throwable) {
        null
    } finally {
        try { if (crop != null) crop.recycle() } catch (_: Exception) { }
        try { if (upright != null && upright !== bmp) upright.recycle() } catch (_: Exception) { }
    }
}

/** Önizleme alanı 3:4 olduğundan birimler: genişlik = 1, yükseklik = 4/3. */
const val PREVIEW_H = 4f / 3f

/** Çekim kılavuzunun geometrisi (genişlik w, yükseklik h piksel / birim). */
class Guide(val left: Float, val top: Float, val width: Float, val height: Float, val hairY: Float, val chinY: Float)

fun guideFor(w: Float, h: Float, s: Settings): Guide {
    val cw0 = w * 0.88f
    val ch = (cw0 * TARGET_H / TARGET_W).coerceAtMost(h * 0.96f)
    val cw = ch * TARGET_W / TARGET_H
    val left = (w - cw) / 2f
    val top = (h - ch) / 2f
    val hair = top + ch * s.topGap.toFloat()
    val chin = hair + ch * s.headFrac.toFloat()
    return Guide(left, top, cw, ch, hair, chin)
}

/** [dev]: konum sapması (0 = tam yerinde, 1 = tolerans sınırı). [sharpOnly]: yalnızca netlik engeli var. */
class LiveEval(val ready: Boolean, val hint: String, val dev: Float = 0f, val sharpOnly: Boolean = false)

/** Canlı çekimde kabul edilen en yüksek bulanıklık oranı (son kontrolden biraz esnek). */
const val LIVE_BLUR_LIMIT = 0.30f

/**
 * Yüzün kılavuza uyup uymadığını değerlendirir (otomatik çekim ve yönlendirme için). Tolerans: [Settings.autoTol].
 * [smart]: akıllı çekim açıksa göz açıklığı, netlik ve telefon hareketi de koşuldur.
 */
fun evaluateLive(
    l: LiveFace, s: Settings,
    motionOk: Boolean = true, smart: Boolean = false, ignoreSharp: Boolean = false
): LiveEval {
    val t = s.autoTol.toFloat()
    val g = guideFor(1f, PREVIEW_H, s)
    val headH = g.chinY - g.hairY
    val faceBottom = l.y2 * PREVIEW_H
    val faceH = l.h * PREVIEW_H
    val ratio = faceH / headH
    val dev = maxOf(
        Math.abs(l.cx - 0.5f) / (0.10f * t),
        Math.abs(ratio - 0.78f) / (0.20f * t),
        Math.abs(faceBottom - g.chinY) / (headH * 0.14f * t)
    )
    fun no(msg: String) = LiveEval(false, msg, dev)
    return when {
        Math.abs(l.cx - 0.5f) > 0.10f * t -> no(if (l.cx < 0.5f) "Sağa kayın" else "Sola kayın")
        ratio < 0.78f - 0.20f * t -> no("Biraz yaklaşın")
        ratio > 0.78f + 0.20f * t -> no("Biraz uzaklaşın")
        faceBottom < g.chinY - headH * 0.14f * t -> no("Yüz çok yukarıda")
        faceBottom > g.chinY + headH * 0.14f * t -> no("Yüz çok aşağıda")
        Math.abs(l.roll) > 8f * t -> no("Başı dik tutun")
        Math.abs(l.yaw) > 15f * t -> no("Karşıya bakın")
        smart && l.eyeOpen in 0f..0.3f -> no("Gözler açık olsun")
        smart && !l.bright.isNaN() && l.bright < com.eokul.studio.core.Quality.DARK_LIMIT.toFloat() -> no("Ortam çok karanlık")
        smart && !motionOk -> no("Telefonu sabit tutun")
        smart && !ignoreSharp && !l.sharp.isNaN() && l.sharp > LIVE_BLUR_LIMIT ->
            LiveEval(false, "Netleniyor — sabit durun", dev, sharpOnly = true)
        else -> LiveEval(true, "Hazır — sabit durun", dev)
    }
}

object LiveFaces {
    val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )
    }
}
