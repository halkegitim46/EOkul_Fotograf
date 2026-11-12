package com.eokul.studio.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Kadraj kontrolü: yüz ve (varsa) göz noktalarının son kırpma alanındaki konumunu ölçülebilir kurallarla denetler.
 * Her kural ayrı bir koda sahiptir; arayüz bunları açıklayıcı mesajlara çevirir.
 */
public final class Framing {
    private Framing() {}

    public static final String HEAD_CUT = "HEAD_CUT";        // saç tepesi kadrajın dışında
    public static final String HEAD_TIGHT = "HEAD_TIGHT";    // baş üstü boşluğu çok az
    public static final String CHIN_CUT = "CHIN_CUT";        // çene alt kenara çok yakın / dışında
    public static final String FACE_SMALL = "FACE_SMALL";
    public static final String FACE_LARGE = "FACE_LARGE";
    public static final String OFF_CENTER = "OFF_CENTER";
    public static final String EYE_LEVEL = "EYE_LEVEL";      // göz hizası beklenen aralığın dışında
    public static final String EYE_TILT = "EYE_TILT";        // göz çizgisi eğik

    /**
     * @param face    {x1,y1,x2,y2} yüz kutusu
     * @param headTop saç tepesi y; bilinmiyorsa NaN
     * @param eyes    {solX, solY, sağX, sağY} veya null
     * @param box     {x1,y1,x2,y2} kırpma alanı
     */
    public static List<String> check(double[] face, double headTop, double[] eyes, double[] box) {
        List<String> out = new ArrayList<>();
        double w = box[2] - box[0], h = box[3] - box[1];
        if (w <= 0 || h <= 0) return out;
        double top = Double.isNaN(headTop) ? face[1] : headTop;
        if (top < box[1]) out.add(HEAD_CUT);
        else if (!Double.isNaN(headTop) && (headTop - box[1]) < 0.012 * h) out.add(HEAD_TIGHT);
        if (face[3] > box[3] - 0.02 * h) out.add(CHIN_CUT);
        double fh = (face[3] - face[1]) / h;
        if (fh < 0.30) out.add(FACE_SMALL);
        else if (fh > 0.72) out.add(FACE_LARGE);
        double cx = (face[0] + face[2]) / 2.0, bx = (box[0] + box[2]) / 2.0;
        if (Math.abs(cx - bx) > 0.15 * w) out.add(OFF_CENTER);
        if (eyes != null && eyes.length == 4) {
            double ey = ((eyes[1] + eyes[3]) / 2.0 - box[1]) / h;
            if (ey < 0.15 || ey > 0.50) out.add(EYE_LEVEL);
            double ang = Math.toDegrees(Math.atan2(eyes[3] - eyes[1], eyes[2] - eyes[0]));
            if (Math.abs(ang) > 8.0 && Math.abs(ang) < 172.0) out.add(EYE_TILT);
        }
        return out;
    }
}
