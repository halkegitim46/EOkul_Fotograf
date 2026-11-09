package com.eokul.studio.core;

/**
 * Seri çekimde kare seçimi için ek puan düzeltmesi. Temel puan {@link Score#total} ile gelir; burada kareye
 * özgü, resmî fotoğraf kuralıyla çelişen durumlar cezalandırılır: yüz yok, gözler kapalı, belirgin gülümseme,
 * arka ışık / parlama / tek yandan ışık. Bilinmeyen değer (negatif olasılık, NaN) ceza almaz.
 */
public final class Burst {
    private Burst() {}

    public static final int NO_FACE_PENALTY = 35;
    public static final int EYES_CLOSED_PENALTY = 25;
    public static final double EYES_CLOSED_BELOW = 0.35;
    public static final int SMILE_MAX_PENALTY = 14;
    public static final double SMILE_FROM = 0.35;
    public static final double SMILE_FULL = 0.80;

    /**
     * @param base      {@link Score#total} sonucu (0..100)
     * @param faceFound bu karede yüz bulundu mu
     * @param smile     gülümseme olasılığı (0..1); negatif = bilinmiyor
     * @param eyeOpen   iki gözün açıklık olasılığının küçüğü (0..1); negatif = bilinmiyor
     * @param lightCode {@link Lighting} kodu
     */
    public static int total(int base, boolean faceFound, double smile, double eyeOpen, int lightCode) {
        double p = 0;
        if (!faceFound) p += NO_FACE_PENALTY;
        if (eyeOpen >= 0 && eyeOpen < EYES_CLOSED_BELOW) p += EYES_CLOSED_PENALTY;
        if (smile >= 0) p += SMILE_MAX_PENALTY * clamp((smile - SMILE_FROM) / (SMILE_FULL - SMILE_FROM));
        switch (lightCode) {
            case Lighting.BACKLIT: p += 10; break;
            case Lighting.GLARE: p += 10; break;
            case Lighting.UNEVEN: p += 6; break;
            default: break;
        }
        int r = (int) Math.round(base - p);
        return r < 0 ? 0 : Math.min(r, 100);
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
