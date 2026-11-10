package com.eokul.studio.core;

/**
 * Kaliteli son işlem hattı: kırpılan bölge önce hedefin 2 katına Lanczos ile indirilir, burada (varsa) gürültü
 * azaltılır (renk gürültüsü güçlü, parlaklık gürültüsü kenar koruyarak), sonra hedef boyuta indirilir ve
 * yalnızca parlaklığa hafif keskinleştirme (unsharp mask) uygulanır. Kumlanma / bulanıklaşma birlikte giderilir.
 */
public final class Finish {
    private Finish() {}

    public static final class Opts {
        public boolean denoise = true;
        public double sharpen = 0.35;      // 0 = kapalı
    }

    /** Immerkær gürültü tahmini (parlaklık, 0..255 ölçeğinde standart sapma). */
    static double noiseSigma(float[] y, int w, int h) {
        double s = 0;
        int n = 0;
        for (int j = 1; j < h - 1; j++) {
            for (int i = 1; i < w - 1; i++) {
                int o = j * w + i;
                double v = y[o - w - 1] - 2 * y[o - w] + y[o - w + 1]
                        - 2 * y[o - 1] + 4 * y[o] - 2 * y[o + 1]
                        + y[o + w - 1] - 2 * y[o + w] + y[o + w + 1];
                s += Math.abs(v);
                n++;
            }
        }
        if (n == 0) return 0;
        return Math.sqrt(Math.PI / 2.0) * s / (6.0 * n);
    }

    public static int[] render(int[] src, int sw, int sh, double[] box, int dw, int dh, Opts o) {
        double bw = box[2] - box[0];
        boolean downscaled = bw >= dw * 1.5;
        int iw = downscaled ? (int) Math.round(Math.min(dw * 2.0, bw)) : dw;
        int ih = (int) Math.round(iw * (double) dh / dw);
        int[] inter = Lanczos.cropResize(src, sw, sh, box[0], box[1], box[2], box[3], iw, ih);

        int n = iw * ih;
        float[] Y = new float[n], Cb = new float[n], Cr = new float[n];
        toYcc(inter, Y, Cb, Cr);
        double sigma = noiseSigma(Y, iw, ih);

        int[] mid = inter;
        // Gürültü azaltma kırpma boyutuna bakmaz, ölçülen gürültüye bakar: gerçek telefon gürültüsü (lekeli renk,
        // ilintili parlaklık) küçültmeyle yok olmaz; ölçümde ideale göre 2-4 kat kalıyordu.
        if (o.denoise && sigma > 1.2) {
            double cs = clamp(0.8 + sigma * 0.15, 0.8, 2.5);
            Cb = gauss(Cb, iw, ih, cs);
            Cr = gauss(Cr, iw, ih, cs);
            Y = bilateral(Y, iw, ih, 1.3, clamp(2.2 * sigma, 3.0, 26.0));
            mid = fromYcc(Y, Cb, Cr, n);
        }
        int[] out = (iw == dw && ih == dh) ? mid : Lanczos.cropResize(mid, iw, ih, 0, 0, iw, ih, dw, dh);

        // Gürültülü karede keskinleştirme gürültüyü de büyütür: ölçülen gürültü arttıkça azalır
        double amount = o.sharpen * (1.0 - Math.min(0.8, sigma / 6.0));
        if (amount > 0.01) out = unsharp(out, dw, dh, amount);
        return out;
    }

    // ---------------------------------------------------------------- yardımcılar

    private static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }

    private static void toYcc(int[] p, float[] Y, float[] Cb, float[] Cr) {
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            Y[i] = 0.299f * r + 0.587f * g + 0.114f * b;
            Cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b;
            Cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b;
        }
    }

    private static int clip(float v) { return v < 0 ? 0 : (v > 255 ? 255 : Math.round(v)); }

    private static int[] fromYcc(float[] Y, float[] Cb, float[] Cr, int n) {
        int[] o = new int[n];
        for (int i = 0; i < n; i++) {
            float y = Y[i], cb = Cb[i] - 128f, cr = Cr[i] - 128f;
            o[i] = 0xFF000000 | (clip(y + 1.402f * cr) << 16) | (clip(y - 0.344136f * cb - 0.714136f * cr) << 8) | clip(y + 1.772f * cb);
        }
        return o;
    }

    private static float[] gauss(float[] a, int w, int h, double sigma) {
        int r = (int) Math.ceil(sigma * 2.5);
        float[] k = new float[2 * r + 1];
        float sum = 0;
        for (int i = -r; i <= r; i++) { k[i + r] = (float) Math.exp(-(i * i) / (2 * sigma * sigma)); sum += k[i + r]; }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        float[] t = new float[a.length], o = new float[a.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * a[y * w + Math.min(w - 1, Math.max(0, x + i))];
                t[y * w + x] = s;
            }
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * t[Math.min(h - 1, Math.max(0, y + i)) * w + x];
                o[y * w + x] = s;
            }
        return o;
    }

    private static float[] bilateral(float[] a, int w, int h, double sigS, double sigR) {
        int r = 2;
        float[] ws = new float[(2 * r + 1) * (2 * r + 1)];
        for (int dy = -r; dy <= r; dy++)
            for (int dx = -r; dx <= r; dx++)
                ws[(dy + r) * (2 * r + 1) + dx + r] = (float) Math.exp(-(dx * dx + dy * dy) / (2 * sigS * sigS));
        double inv = 1.0 / (2 * sigR * sigR);
        float[] o = new float[a.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float c = a[y * w + x];
                double s = 0, wsum = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        float v = a[yy * w + xx];
                        double d = v - c;
                        double wt = ws[(dy + r) * (2 * r + 1) + dx + r] * Math.exp(-d * d * inv);
                        s += wt * v;
                        wsum += wt;
                    }
                }
                o[y * w + x] = (float) (s / wsum);
            }
        return o;
    }

    private static int[] unsharp(int[] p, int w, int h, double amount) {
        int n = w * h;
        float[] Y = new float[n], Cb = new float[n], Cr = new float[n];
        toYcc(p, Y, Cb, Cr);
        float[] B = gauss(Y, w, h, 0.8);
        int[] o = new int[n];
        for (int i = 0; i < n; i++) {
            float d = (float) (amount * (Y[i] - B[i]));
            d = Math.max(-22f, Math.min(22f, d));
            int c = p[i];
            o[i] = 0xFF000000 | (clip(((c >> 16) & 255) + d) << 16) | (clip(((c >> 8) & 255) + d) << 8) | clip((c & 255) + d);
        }
        return o;
    }
}
