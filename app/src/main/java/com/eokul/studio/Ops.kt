package com.eokul.studio

import android.content.Context
import android.os.Build
import com.eokul.studio.core.OpLog
import com.eokul.studio.core.OpLogReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cihaz üstü işlem günlüğü (çekim, kare puanı, kayıt, hata, yedek...). Öğrenci adı yazılmaz; numara kısa bir
 * özetle (hash) anılır. Ayarlar > "Tanılama ve rapor" ile rapor dosyası alınıp geliştiriciye iletilir.
 */
object Ops {
    private var log: OpLog? = null
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (log != null) return
        appCtx = ctx.applicationContext
        log = OpLog(File(ctx.filesDir, "oplog.jsonl"), 600_000L)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { log?.log("crash", "where", t.name, "msg", e.toString()); log?.flush() } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    /** Ayarlar anlık görüntüsüyle uygulama başlangıcı. */
    fun appStart(s: Settings) {
        val ver = try { appCtx?.packageManager?.getPackageInfo(appCtx!!.packageName, 0)?.versionName } catch (_: Throwable) { null }
        log("app_start", "ver", ver ?: "?", "dev", "${Build.MANUFACTURER} ${Build.MODEL}", "sdk", Build.VERSION.SDK_INT,
            "hq", s.hqCapture, "flipB", s.flipCapture, "flipF", s.flipFront, "std", "${s.photoW}x${s.photoH}",
            "maxKb", s.maxKb, "smart", s.smartShot, "holdMs", s.autoHoldMs)
    }

    fun log(type: String, vararg kv: Any?) {
        try { log?.log(type, *kv) } catch (_: Throwable) { }
    }

    /** Öğrenci numarasının kısa, geri döndürülemeyen kimliği. */
    fun id(no: String): String = Integer.toHexString(no.hashCode() xor 0x5bd1e995).takeLast(6)

    fun error(where: String, t: Throwable) {
        log("error", "where", where, "msg", (t.toString()).take(300), "mem", Diag.memInfo())
    }

    /** İnceleme uyarı metninden kısa kod (rapor için). */
    fun warnCode(msg: String): String = when {
        msg.contains("Yüz bulunamadı") -> "noface"
        msg.contains("yüz algılandı") -> "multiface"
        msg.contains("hedef çözünürlükten küçük") -> "smallcrop"
        msg.contains("bulanık") -> "blurry"
        msg.contains("karanlık") -> "dark"
        msg.contains("aşırı parlak") -> "bright"
        msg.contains("soluk") -> "flat"
        msg.contains("Arka ışık") -> "backlit"
        msg.contains("tek yandan") -> "uneven"
        msg.contains("parlama") -> "glare"
        msg.contains("Başın üst") -> "headcut"
        msg.contains("Baş üstü") -> "headtight"
        msg.contains("Çene") -> "chincut"
        msg.contains("küçük kalıyor") -> "facesmall"
        msg.contains("fazla dolduruyor") -> "facelarge"
        msg.contains("ortalı değil") -> "offcenter"
        msg.contains("Göz hizası") -> "eyelevel"
        msg.contains("Göz çizgisi") -> "tilt"
        msg.contains("yana eğik") -> "roll"
        msg.contains("kameraya dönük değil") -> "yaw"
        else -> "other"
    }

    fun clear() { try { log?.clear() } catch (_: Throwable) { } }

    fun reportText(): String {
        val l = log ?: return "Kayıt yok."
        val c = appCtx
        val ver = try { c?.packageManager?.getPackageInfo(c.packageName, 0)?.versionName } catch (_: Throwable) { null }
        val header = "Oluşturulma: " + SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr")).format(Date()) +
            "\nSürüm: ${ver ?: "?"}   Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}   Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            "\nBellek: ${Diag.memInfo()}   Günlük boyutu: ${l.sizeBytes() / 1024} KB\n(Öğrenci adı içermez.)"
        return OpLogReport.build(l.readAll(), header, l.readLines(300))
    }
}
