package com.eokul.studio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eokul.studio.core.Crop

data class Settings(
    val topGap: Double = 0.03,
    val headFrac: Double = 0.62,
    val jpegQuality: Int = 100,
    val minKb: Int = 20,
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
    val autoHoldMs: Int = 1200,
    /** Akıllı çekim: göz açıklığı, netlik, telefon hareketi denetimi ve 3 kareden en iyisini seçme. */
    val smartShot: Boolean = true,
    /** Kamerayla çekilen fotoğrafı sağ-sol çevir (bazı telefonlarda fotoğraf önizlemeye göre ters kaydedilirse). */
    val flipCapture: Boolean = false,
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
        p.headFrac = headFrac
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
        return Settings(
            topGap = (if (sp.getInt("ver", 1) < 2) d.topGap else (sp.getString("topGap", null)?.toDoubleOrNull() ?: d.topGap)).coerceIn(0.01, 0.25),
            headFrac = (sp.getString("headFrac", null)?.toDoubleOrNull() ?: d.headFrac).coerceIn(0.45, 0.80),
            jpegQuality = sp.getInt("jpegQuality", d.jpegQuality).coerceIn(70, 100),
            minKb = sp.getInt("minKb", d.minKb).coerceIn(1, 500),
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
            .putString("headFrac", s.headFrac.toString())
            .putInt("jpegQuality", s.jpegQuality)
            .putInt("minKb", s.minKb)
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
            .putBoolean("autoAccept", s.autoAccept)
            .putInt("autoAcceptScore", s.autoAcceptScore)
            .putString("pinHash", s.pinHash)
            .putString("pinSalt", s.pinSalt)
            .putLong("lastBackupMs", s.lastBackupMs)
            .putInt("ver", 2)
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
