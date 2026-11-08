package com.eokul.studio.core;

/**
 * Işık dengesi denetimi: arka ışık, tek yandan ışık ve parlama.
 * Yüzün kendi parlaklığı yetmez (Quality zaten ölçer); asıl ipucu yüz ile ARKA PLAN arasındaki fark ve
 * yüzün sol-sağ yarısı arasındaki farktır. Girdi küçültülmüş tüm kare olabilir (ör. 96 px genişlik).
 */
public final class Lighting {
    private Lighting() {}

    public static final int OK = 0;
    public static final int BACKLIT = 1;
    public static final int UNEVEN = 2;
    public static final int GLARE = 3;

    /** Arka plan yüzden en az bu kadar (0-255 ışık birimi) parlaksa ve yeterince aydınlıksa arka ışık. */
    public static final double BACKLIT_DELTA = 40.0;
    public static final double BACKLIT_BG_MIN = 130.0;
    public static final double UNEVEN_RATIO = 0.35;
    public static final double GLARE_CLIP_FRAC = 0.10;

    public static final class Report {
        public final int code;
        public final double faceMean;
        public final double bgMean;
        /** |sol - sağ| / ortalama (0 = dengeli). */
        public final double sideDiff;
        /** Yüzde patlak (>=245) piksellerin oranı. */
        public final double clipFrac;

        Report(int code, double faceMean, double bgMean, double sideDiff, double clipFrac) {
            this.code = code;
            this.faceMean = faceMean;
            this.bgMean = bgMean;
            this.sideDiff = sideDiff;
            this.clipFrac = clipFrac;
        }
    }

    private static double luma(int p) {
        return 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
    }

    /**
     * @param argb  görüntü pikselleri (dik, yani döndürülmüş)
     * @param fx1.. yüz kutusu, 0..1 normalleştirilmiş (aynalanmamış)
     * @return null: ölçüm için yeterli veri yok
     */
    public static Report analyze(int[] argb, int w, int h, double fx1, double fy1, double fx2, double fy2) {
        if (w < 16 || h < 16 || argb.length < w * h) return null;
        double fw = fx2 - fx1, fh = fy2 - fy1;
        if (fw <= 0 || fh <= 0) return null;
        // Yüz çekirdeği: kutunun ortadaki %60'ı (saç ve kenar gölgeleri dışarıda)
        int cx1 = clampI(fx1 + fw * 0.20, w), cx2 = clampI(fx2 - fw * 0.20, w);
        int cy1 = clampI(fy1 + fh * 0.20, h), cy2 = clampI(fy2 - fh * 0.20, h);
        if (cx2 - cx1 < 3 || cy2 - cy1 < 3) return null;
        // Arka plan: yüz kutusunun %30 genişletilmişinin dışında ve çenenin biraz altına kadar (giysi dışarıda kalsın)
        int ex1 = clampI(fx1 - fw * 0.30, w), ex2 = clampI(fx2 + fw * 0.30, w);
        int ey1 = clampI(fy1 - fh * 0.30, h), ey2 = clampI(fy2 + fh * 0.30, h);
        int ylimit = clampI(fy2 + fh * 0.25, h);

        double faceSum = 0, leftSum = 0, rightSum = 0;
        int faceN = 0, leftN = 0, rightN = 0, clip = 0;
        int midX = (cx1 + cx2) / 2;
        for (int y = cy1; y < cy2; y++) {
            for (int x = cx1; x < cx2; x++) {
                double l = luma(argb[y * w + x]);
                faceSum += l;
                faceN++;
                if (l >= 245) clip++;
                if (x < midX) { leftSum += l; leftN++; } else { rightSum += l; rightN++; }
            }
        }
        double bgSum = 0;
        int bgN = 0;
        for (int y = 0; y < ylimit; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= ex1 && x < ex2 && y >= ey1 && y < ey2) continue;
                bgSum += luma(argb[y * w + x]);
                bgN++;
            }
        }
        if (faceN == 0 || leftN == 0 || rightN == 0) return null;
        double face = faceSum / faceN;
        double bg = bgN >= 40 ? bgSum / bgN : Double.NaN;
        double side = Math.abs(leftSum / leftN - rightSum / rightN) / Math.max(face, 1.0);
        double clipFrac = clip / (double) faceN;

        int code = OK;
        if (!Double.isNaN(bg) && bg - face > BACKLIT_DELTA && bg > BACKLIT_BG_MIN) code = BACKLIT;
        else if (clipFrac > GLARE_CLIP_FRAC || face > 205) code = GLARE;
        else if (side > UNEVEN_RATIO && face > 40) code = UNEVEN;
        return new Report(code, face, bg, side, clipFrac);
    }

    private static int clampI(double norm, int size) {
        int v = (int) Math.round(norm * size);
        return Math.max(0, Math.min(size, v));
    }
}
