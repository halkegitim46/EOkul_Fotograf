package com.eokul.studio.core;

/**
 * 100 üzerinden çekim kalite puanı: kadraj 25, netlik 25, baş açısı 20, ışık 20, göz açıklığı 10.
 * Bir kalite göstergesidir; resmi uygunluğu tek başına garanti etmez. Bilinmeyen değer (NaN veya negatif
 * olasılık) nötr puan alır, böylece eksik ölçüm çekimi gereksiz yere cezalandırmaz.
 */
public final class Score {
    private Score() {}

    /**
     * @param framingDev yüz konumunun toleransa göre sapması (0 = tam yerinde, 1 = tolerans sınırı)
     * @param blurRatio  {@link Quality.Report#blurRatio}; NaN = bilinmiyor
     * @param roll       baş yan eğikliği (derece); göz çizgisi varsa ondan hesaplanmış değer
     * @param yaw        baş dönüklüğü (derece)
     * @param brightness yüz bölgesi ortalama parlaklığı (0..255); NaN = bilinmiyor
     * @param contrast   yüz bölgesi ton farkı (std. sapma); NaN = bilinmiyor
     * @param eyeOpen    iki gözün açıklık olasılığının küçüğü (0..1); negatif = bilinmiyor
     */
    public static int total(double framingDev, double blurRatio, double roll, double yaw,
                            double brightness, double contrast, double eyeOpen) {
        // Bilinmeyen (NaN) ölçüm tüm puanı sıfırlamamalı: orta değer alır
        double f = Double.isNaN(framingDev) ? 20 : 25 * clamp(1 - Math.max(0, framingDev) / 2.5);

        double s = Double.isNaN(blurRatio) ? 18 : 25 * clamp((0.40 - blurRatio) / (0.40 - 0.12));

        double a = (Double.isNaN(roll) ? 10 : 10 * clamp(1 - Math.abs(roll) / 20.0))
            + (Double.isNaN(yaw) ? 10 : 10 * clamp(1 - Math.abs(yaw) / 30.0));

        double light;
        if (Double.isNaN(brightness)) {
            light = 12;
        } else if (brightness < 95) {
            light = 12 * clamp((brightness - 40) / 55.0);
        } else if (brightness > 165) {
            light = 12 * clamp((225 - brightness) / 60.0);
        } else {
            light = 12;
        }
        double con = Double.isNaN(contrast) ? 6 : 8 * clamp((contrast - 10) / 35.0);

        double e = eyeOpen < 0 || Double.isNaN(eyeOpen) ? 7 : 10 * clamp((eyeOpen - 0.2) / 0.4);

        return (int) Math.round(f + s + a + light + con + e);
    }

    /** Puanı kısa bir sözel dereceye çevirir. */
    public static String label(int score) {
        if (score >= 85) return "Çok iyi";
        if (score >= 70) return "İyi";
        if (score >= 50) return "Orta";
        return "Zayıf";
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
