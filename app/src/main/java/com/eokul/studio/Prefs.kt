package com.eokul.studio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eokul.studio.core.Crop

data class Settings(
    /** Saç üstü boşluğu / kırpma yüksekliği. */
    val topGap: Double = 0.03,
    /** Çene altı (göğüs) boşluğu / kırpma yüksekliği. */
    val bottomGap: Double = 0.35,
    /** Yüzün solundaki ve sağındaki asgari boşluk / kırpma genişliği. */
    val leftGap: Double = 0.16,
    val rightGap: Double = 0.16,
    val jpegQuality: Int = 100,
    val minKb: Int = 20,
    /** Üst dosya boyutu (KB); kalite bu sınıra uyacak şekilde otomatik düşürülür. */
    val maxKb: Int = 150,
    /** Çıktı fotoğrafı boyutu (piksel). Varsayılan e-Okul: 133x171. */
    val photoW: Int = 133,
    val photoH: Int = 171,
    /** Yüksek kaliteli çekim: kameranın en iyi görüntü işlemesi + ~5 MP (biraz yavaş); kapalıysa hızlı ~2 MP. */
    val hqCapture: Boolean = true,
    val embedSrgb: Boolean = true,
    val nameRegex: String = "^\\d{5,11}$",
    val outputDir: String = "E_Okul_Kayitlar",
    /** Sınıf klasörlerinin oluşturulacağı ana klasör (SAF ağaç URI'si). */
    val rootUri: String? = null,
    /** Onaydan sonra sıradaki öğrenciye otomatik geç. */
    val serialMode: Boolean = true,
    /** Tam çözünürlüklü orijinal fotoğrafı "Orijinaller" klasöründe sakla. */
    val keepOriginals: Boolean = true,
    /** Yüz kılavuza oturup sabit durunca otomatik çek. */
    val autoCapture: Boolean = false,
    /** Otomatik çekim toleransı çarpanı: <1 sıkı, >1 gevşek. */
    val autoTol: Double = 1.0,
    /** Yüz hazır olduktan sonra çekime kadar bekleme (ms). */
    val autoHoldMs: Int = 900,
    /** Akıllı çekim: göz açıklığı, netlik, telefon hareketi denetimi ve 3 kareden en iyisini seçme. */
    val smartShot: Boolean = true,
    /** Kamerayla çekilen fotoğrafı sağ-sol çevir (bazı telefonlarda fotoğraf önizlemeye göre ters kaydedilirse). */
    val flipCapture: Boolean = false,
    /** Aynısı, ön kamera için ayrı (ön ve arka kameranın kayıt yönü telefonlarda farklı olabilir). */
    val flipFront: Boolean = false,
    /** Kalite puanı eşiğin üstünde ve uyarı yoksa onay beklemeden kaydet. */
    val autoAccept: Boolean = true,
    val autoAcceptScore: Int = 80,
    /** PIN (SHA-256 + tuz); null ise uygulama kilitsiz. */
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /** Son başarılı yedeğin zamanı (ms); 0 = hiç alınmadı. */
    val lastBackupMs: Long = 0L
) {
    fun cropParams(): Crop.Params {
        val p = Crop.Params()
        p.topGap = topGap
        p.bottomGap = bottomGap
        p.leftGap = leftGap
        p.rightGap = rightGap
        p.targetW = photoW
        p.targetH = photoH
        return p
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("eokul_settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        val regex = sp.getString("nameRegex", null)?.takeIf { r ->
            try {
                Regex(r)
                true
            } catch (e: Exception) {
                false
            }
        } ?: d.nameRegex
        val outDir = sp.getString("outputDir", null)?.trim()?.takeIf { it.isNotEmpty() } ?: d.outputDir
        val version = sp.getInt("ver", 1)
        val savedTop = (if (version < 2) d.topGap else (sp.getString("topGap", null)?.toDoubleOrNull() ?: d.topGap)).coerceIn(0.01, 0.25)
        val legacyHead = (sp.getString("headFrac", null)?.toDoubleOrNull() ?: 0.62).coerceIn(0.45, 0.80)
        val migratedBottom = (1.0 - savedTop - legacyHead).coerceIn(0.08, 0.55)
        return Settings(
            topGap = savedTop,
            bottomGap = (sp.getString("bottomGap", null)?.toDoubleOrNull() ?: if (version < 3) migratedBottom else d.bottomGap).coerceIn(0.05, 0.55),
            leftGap = (sp.getString("leftGap", null)?.toDoubleOrNull() ?: d.leftGap).coerceIn(0.05, 0.40),
            rightGap = (sp.getString("rightGap", null)?.toDoubleOrNull() ?: d.rightGap).coerceIn(0.05, 0.40),
            jpegQuality = sp.getInt("jpegQuality", d.jpegQuality).coerceIn(70, 100),
            minKb = sp.getInt("minKb", d.minKb).coerceIn(1, 500),
            maxKb = sp.getInt("maxKb", d.maxKb).coerceIn(5, 5000),
            photoW = sp.getInt("photoW", d.photoW).coerceIn(40, 1200),
            photoH = sp.getInt("photoH", d.photoH).coerceIn(40, 1600),
            hqCapture = sp.getBoolean("hqCapture", d.hqCapture),
            embedSrgb = sp.getBoolean("embedSrgb", d.embedSrgb),
            nameRegex = regex,
            outputDir = outDir,
            rootUri = sp.getString("rootUri", null)?.takeIf { it.isNotBlank() },
            serialMode = sp.getBoolean("serialMode", d.serialMode),
            keepOriginals = sp.getBoolean("keepOriginals", d.keepOriginals),
            autoCapture = sp.getBoolean("autoCapture", d.autoCapture),
            autoTol = (sp.getString("autoTol", null)?.toDoubleOrNull() ?: d.autoTol).coerceIn(0.5, 2.0),
            autoHoldMs = sp.getInt("autoHoldMs", d.autoHoldMs).coerceIn(400, 4000),
            smartShot = sp.getBoolean("smartShot", d.smartShot),
            flipCapture = sp.getBoolean("flipCapture", d.flipCapture),
            flipFront = sp.getBoolean("flipFront", d.flipFront),
            autoAccept = sp.getBoolean("autoAccept", d.autoAccept),
            autoAcceptScore = sp.getInt("autoAcceptScore", d.autoAcceptScore).coerceIn(60, 95),
            pinHash = sp.getString("pinHash", null)?.takeIf { it.isNotBlank() },
            pinSalt = sp.getString("pinSalt", null)?.takeIf { it.isNotBlank() },
            lastBackupMs = sp.getLong("lastBackupMs", 0L)
        )
    }

    fun save(s: Settings) {
        sp.edit()
            .putString("topGap", s.topGap.toString())
            .putString("bottomGap", s.bottomGap.toString())
            .putString("leftGap", s.leftGap.toString())
            .putString("rightGap", s.rightGap.toString())
            .putInt("jpegQuality", s.jpegQuality)
            .putInt("minKb", s.minKb)
            .putInt("maxKb", s.maxKb)
            .putInt("photoW", s.photoW)
            .putInt("photoH", s.photoH)
            .putBoolean("hqCapture", s.hqCapture)
            .putBoolean("embedSrgb", s.embedSrgb)
            .putString("nameRegex", s.nameRegex)
            .putString("outputDir", s.outputDir)
            .putString("rootUri", s.rootUri)
            .putBoolean("serialMode", s.serialMode)
            .putBoolean("keepOriginals", s.keepOriginals)
            .putBoolean("autoCapture", s.autoCapture)
            .putString("autoTol", s.autoTol.toString())
            .putInt("autoHoldMs", s.autoHoldMs)
            .putBoolean("smartShot", s.smartShot)
            .putBoolean("flipCapture", s.flipCapture)
            .putBoolean("flipFront", s.flipFront)
            .putBoolean("autoAccept", s.autoAccept)
            .putInt("autoAcceptScore", s.autoAcceptScore)
            .putString("pinHash", s.pinHash)
            .putString("pinSalt", s.pinSalt)
            .putLong("lastBackupMs", s.lastBackupMs)
            .putInt("ver", 3)
            .apply()
    }
}

/** Tüm ekranların paylaştığı ayar durumu (Compose tarafından izlenir). */
object SettingsState {
    private var prefs: Prefs? = null
    private var state by mutableStateOf(Settings())

    val current: Settings get() = state

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = Prefs(ctx.applicationContext)
        prefs = p
        state = p.load()
    }

    fun update(s: Settings) {
        state = s
        prefs?.save(s)
    }
}
