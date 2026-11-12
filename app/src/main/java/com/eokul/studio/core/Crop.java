package com.eokul.studio.core;

/**
 * Vesikalık kırpma geometrisi (masaüstü sürüm v17.6 ile aynı mantık).
 * Kutular {x1, y1, x2, y2} biçiminde double[4] olarak taşınır.
 */
public final class Crop {
    private Crop() {}

    public static final class Params {
        public int targetW = 133;
        public int targetH = 171;
        public double topGap = 0.10;         // saç tepesinin üstündeki boşluk / kırpma yüksekliği
        public double bottomGap = 0.35;      // çene altı boşluğu / kırpma yüksekliği
        public double leftGap = 0.16;        // yüzün solundaki asgari boşluk / kırpma genişliği
        public double rightGap = 0.16;       // yüzün sağındaki asgari boşluk / kırpma genişliği
        public double hairAllowance = 0.35;  // saç tepesi bulunamazsa: yüz üstünden yukarı pay (yüz birimi)
        public double hairMin = 0.10;        // baş üstü en az bu kadar yukarıda varsayılır (yüz birimi)

        public double ratio() {
            return (double) targetW / targetH;
        }

        public Params copy() {
            Params p = new Params();
            p.targetW = targetW;
            p.targetH = targetH;
            p.topGap = topGap;
            p.bottomGap = bottomGap;
            p.leftGap = leftGap;
            p.rightGap = rightGap;
            p.hairAllowance = hairAllowance;
            p.hairMin = hairMin;
            return p;
        }
    }

    /** Yüz büyüklüğü birimi: DNN (yüksek) ve kare kutulara karşı dayanıklı. */
    public static double faceUnit(double[] face) {
        return 0.5 * ((face[3] - face[1]) + 1.25 * (face[2] - face[0]));
    }

    /**
     * Bir görüntü boyutunda ([fromW]x[fromH]) tanımlı kutuyu başka bir boyuta ([toW]x[toH]) ölçekler.
     * Kayıtlı kırpma kutusu çekim anındaki çalışma görüntüsü koordinatındadır; "Kırpmayı düzenle" ise küçültülmüş
     * orijinali açar. Boyutlar aynıysa veya eski kayıtta boyut bilinmiyorsa (0) kutu olduğu gibi döner.
     */
    public static double[] scaleBox(double[] b, int fromW, int fromH, int toW, int toH) {
        if (fromW <= 0 || fromH <= 0 || toW <= 0 || toH <= 0 || (fromW == toW && fromH == toH)) return b.clone();
        double sx = toW / (double) fromW, sy = toH / (double) fromH;
        return new double[]{b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy};
    }

    /** Kutuyu en-boy oranını koruyarak görüntü içine sığdırır (merkezi mümkün olduğunca korur). */
    public static double[] fitInside(double[] b, int iw, int ih, double ratio) {
        double w = Math.max(1.0, Math.min(Math.min(b[2] - b[0], iw), ih * ratio));
        double h = w / ratio;
        double cx = (b[0] + b[2]) / 2.0, cy = (b[1] + b[3]) / 2.0;
        double x1 = Math.min(Math.max(cx - w / 2.0, 0.0), iw - w);
        double y1 = Math.min(Math.max(cy - h / 2.0, 0.0), ih - h);
        return new double[]{x1, y1, x1 + w, y1 + h};
    }

    public static double[] fromCenter(double cx, double cy, double w, double ratio) {
        double h = w / ratio;
        return new double[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2};
    }

    /**
     * @param face    {x1,y1,x2,y2} veya yüz yoksa null
     * @param headTop saç tepesi y koordinatı; bilinmiyorsa Double.NaN
     */
    private static double clamp(double v, double lo, double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }

    /** ML Kit yüz kutusunun genişlik / (saç dahil baş yüksekliği) oranı için tipik değer. Kılavuz ve yatay boşluk hesabında kullanılır. */
    public static final double FACE_ASPECT = 0.72;

    /**
     * Kırpma yüksekliğinin kaçta kaçını baş (saç tepesi - çene) kaplar. Dikey boşluklar (üst+alt) ve yatay boşluklar
     * (sol+sağ) birlikte karşılandığında daha büyük kutu gereken hangisiyse baş oranı onu izler; çekim kılavuzu bu değeri
     * kullandığından canlı kılavuz ile çekim sonrası kırpma aynı geometriyi gösterir.
     */
    public static double headFraction(Params p, double faceAspect) {
        double tg = clamp(p.topGap, 0.01, 0.25);
        double bg = clamp(p.bottomGap, 0.05, 0.55);
        double lg = clamp(p.leftGap, 0.05, 0.40);
        double rg = clamp(p.rightGap, 0.05, 0.40);
        if (tg + bg > 0.85) bg = 0.85 - tg;
        if (lg + rg > 0.85) rg = 0.85 - lg;
        double vertical = Math.max(0.10, 1.0 - tg - bg);
        double horizontal = p.ratio() * Math.max(0.10, 1.0 - lg - rg) / Math.max(0.3, faceAspect);
        return Math.min(vertical, horizontal);
    }

    public static double[] defaultCropBox(int iw, int ih, double[] face, double headTop, Params p) {
        double ratio = p.ratio();
        if (face == null) {
            double cw = Math.min(iw, ih * ratio) * 0.85;
            return fitInside(fromCenter(iw / 2.0, ih * 0.45, cw, ratio), iw, ih, ratio);
        }
        double fy1 = face[1], fy2 = face[3];
        double u = faceUnit(face);
        double cx = (face[0] + face[2]) / 2.0;

        double top0 = Double.isNaN(headTop) ? fy1 - u * p.hairAllowance : headTop;
        top0 = Math.min(top0, fy1 - u * p.hairMin);
        double headH = Math.max(fy2 - top0, 1.0);
        // Dikey ve yatay asgari boşlukların ikisini de karşılayan, hedef oranlı tek kutu.
        // Kenar boşlukları birbirinden bağımsızdır; dar görüntülerde görüntü sınırı önceliklidir.
        double tg = clamp(p.topGap, 0.01, 0.25);
        double bg = clamp(p.bottomGap, 0.05, 0.55);
        double lg = clamp(p.leftGap, 0.05, 0.40);
        double rg = clamp(p.rightGap, 0.05, 0.40);
        if (tg + bg > 0.85) bg = 0.85 - tg;
        if (lg + rg > 0.85) rg = 0.85 - lg;
        double verticalH = headH / Math.max(0.10, 1.0 - tg - bg);
        double faceW = Math.max(1.0, face[2] - face[0]);
        double horizontalH = faceW / (ratio * Math.max(0.10, 1.0 - lg - rg));
        double h = Math.max(Math.max(verticalH, horizontalH), p.targetH);
        h = Math.min(h, Math.min(ih, iw / ratio));
        double w = h * ratio;
        double y1 = top0 - tg * h;
        // Yatay konum: yüz merkezi, sol/sağ boşluk oranlarına göre kutunun içine yerleşir (eşit boşlukta tam ortada).
        // (Önceki sürüm yüzü sol boşluğa göre hizalıyordu; kutu yüzden geniş olunca yüz sola yaslanıp sağ taraf boş kalıyordu.)
        double x1 = cx - w * (1.0 + lg - rg) / 2.0;
        return fitInside(new double[]{x1, y1, x1 + w, y1 + h}, iw, ih, ratio);
    }
}
