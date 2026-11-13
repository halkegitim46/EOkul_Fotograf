package com.eokul.studio

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hata tanılama: çökme yığını dosyaya yazılır; Android'in kayıtlı "ANR / çökme / bellek" çıkış nedenleri okunur.
 * Bir sonraki açılışta kısa bir rapor gösterilir (telefonda log okumadan nedeni görmek için).
 */
object Diag {
    private const val FILE = "son_hata.txt"

    /** Anlık bellek durumu: Java yığını (kullanılan / sınır) ve yerel bellek, MB. */
    fun memInfo(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val maxMb = rt.maxMemory() / 1048576
        val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / 1048576
        return "Java yığını $usedMb/$maxMb MB, yerel $nativeMb MB"
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sb = StringBuilder()
                sb.append(SimpleDateFormat("d MMM HH:mm:ss", Locale.forLanguageTag("tr")).format(Date())).append("  iş parçacığı: ").append(t.name).append('\n')
                sb.append(e.toString()).append('\n')
                sb.append(memInfo()).append('\n')
                e.stackTrace.take(14).forEach { sb.append("  at ").append(it.toString()).append('\n') }
                var c = e.cause
                var depth = 0
                while (c != null && depth < 2) {
                    sb.append("Neden: ").append(c.toString()).append('\n')
                    c.stackTrace.take(6).forEach { sb.append("  at ").append(it.toString()).append('\n') }
                    c = c.cause
                    depth++
                }
                File(app.filesDir, FILE).writeText(sb.toString())
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    /** Önceki çalışmadan kalan hata/çıkış nedeni raporu; yoksa null. Rapor bir kez gösterilir. */
    fun report(ctx: Context): String? {
        val out = StringBuilder()
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) {
                out.append("Son çökme:\n").append(f.readText().take(1500)).append('\n')
                f.delete()
            }
        } catch (_: Throwable) { }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val sp = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
                val seen = sp.getLong("lastExitTs", 0L)
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                var maxTs = seen
                for (ei in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6)) {
                    if (ei.timestamp <= seen) continue
                    if (ei.timestamp > maxTs) maxTs = ei.timestamp
                    val name = when (ei.reason) {
                        android.app.ApplicationExitInfo.REASON_ANR -> "Uygulama yanıt vermedi (ANR)"
                        android.app.ApplicationExitInfo.REASON_CRASH -> "Çökme"
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Yerel (native) çökme"
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Bellek yetersiz"
                        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Aşırı kaynak kullanımı"
                        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Başlatma hatası"
                        else -> null
                    } ?: continue
                    // Uygulama arka planda (kullanılmıyorken) bellek için kapatıldıysa bu normal bir Android davranışıdır:
                    // veriler kayıtlıdır, kullanıcıya gösterilmez. Yalnızca ön plandayken olan kapanmalar bildirilir.
                    val wasForeground = ei.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                    if (ei.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY && !wasForeground) continue
                    out.append(SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("tr")).format(Date(ei.timestamp)))
                        .append(" · ").append(name)
                        .append(if (wasForeground) " (uygulama açıkken)" else " (arka planda)")
                    val d = ei.description
                    if (!d.isNullOrBlank()) out.append(" — ").append(d.take(160))
                    out.append('\n')
                }
                if (maxTs > seen) sp.edit().putLong("lastExitTs", maxTs).apply()
            }
        } catch (_: Throwable) { }
        val s = out.toString().trim()
        return if (s.isEmpty()) null else s
    }
}
