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
        public double headFrac = 0.62;       // (saç tepesi → çene) / kırpma yüksekliği
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
            p.headFrac = headFrac;
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
        double idealH = headH / p.headFrac;
        double top = top0 - p.topGap * idealH;

        double fitH = Math.min(Math.min(idealH, iw / ratio), ih);
        double room = ih - Math.max(top, 0.0);
        double shrunk = Math.min(fitH, room);
        double minH = Math.max(Math.max(0.6 * fitH, (fy2 - top) * 1.03), (double) p.targetH);
        double h = (shrunk >= minH) ? shrunk : fitH;
        double y1 = Math.min(Math.max(top, 0.0), ih - h);
        double w = h * ratio;
        double x1 = Math.min(Math.max(cx - w / 2.0, 0.0), iw - w);
        return fitInside(new double[]{x1, y1, x1 + w, y1 + h}, iw, ih, ratio);
    }
}
