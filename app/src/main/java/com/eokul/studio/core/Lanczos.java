package com.eokul.studio.core;

/**
 * Pillow'un Image.resize(..., LANCZOS) davranışını taklit eden, kırpma + küçültme yapan çözümleyici.
 * Android'in bilinear küçültmesi büyük oranlarda keskinlik/aliasing kaybı yaptığı için kullanılır.
 */
public final class Lanczos {
    private Lanczos() {}

    private static double lanczos3(double x) {
        if (x == 0.0) return 1.0;
        if (x <= -3.0 || x >= 3.0) return 0.0;
        double px = Math.PI * x;
        return Math.sin(px) / px * Math.sin(px / 3.0) / (px / 3.0);
    }

    private static final class Coeffs {
        int[] start;
        int[] count;
        double[][] w;
    }

    private static Coeffs coeffs(int inSize, int outSize) {
        double scale = (double) inSize / outSize;
        double filterScale = Math.max(scale, 1.0);
        double support = 3.0 * filterScale;
        Coeffs c = new Coeffs();
        c.start = new int[outSize];
        c.count = new int[outSize];
        c.w = new double[outSize][];
        for (int i = 0; i < outSize; i++) {
            double center = (i + 0.5) * scale;
            int xmin = (int) (center - support + 0.5);
            if (xmin < 0) xmin = 0;
            int xmax = (int) (center + support + 0.5);
            if (xmax > inSize) xmax = inSize;
            int n = Math.max(1, xmax - xmin);
            double[] ww = new double[n];
            double sum = 0;
            for (int x = 0; x < n; x++) {
                double v = lanczos3((x + xmin - center + 0.5) / filterScale);
                ww[x] = v;
                sum += v;
            }
            if (sum != 0) for (int x = 0; x < n; x++) ww[x] /= sum;
            c.start[i] = xmin;
            c.count[i] = n;
            c.w[i] = ww;
        }
        return c;
    }

    private static int clip8(double v) {
        int r = (int) Math.floor(v + 0.5);
        return r < 0 ? 0 : (r > 255 ? 255 : r);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Kutuyu (ondalıklı) tamsayıya yuvarlar, görüntü dışına taşan kısmı kenar piksellerini
     * tekrarlayarak doldurur ve dw x dh boyutuna Lanczos ile küçültür.
     *
     * @return 0xFFRRGGBB piksel dizisi
     */
    public static int[] cropResize(int[] src, int sw, int sh,
                                   double bx1, double by1, double bx2, double by2,
                                   int dw, int dh) {
        int x1 = (int) Math.rint(bx1), y1 = (int) Math.rint(by1);
        int x2 = (int) Math.rint(bx2), y2 = (int) Math.rint(by2);
        int rw = x2 - x1, rh = y2 - y1;
        if (rw <= 0 || rh <= 0) throw new IllegalArgumentException("Kırpma alanı geçersiz");

        Coeffs hc = coeffs(rw, dw);
        Coeffs vc = coeffs(rh, dh);

        // Yatay geçiş: rh satır x dw sütun, her kanal 8 bit'e yuvarlanır (Pillow gibi)
        int[][] mid = new int[3][rh * dw];
        for (int y = 0; y < rh; y++) {
            int sy = clamp(y1 + y, 0, sh - 1);
            int rowBase = sy * sw;
            for (int ox = 0; ox < dw; ox++) {
                double r = 0, g = 0, b = 0;
                int st = hc.start[ox];
                double[] ww = hc.w[ox];
                for (int k = 0; k < ww.length; k++) {
                    int p = src[rowBase + clamp(x1 + st + k, 0, sw - 1)];
                    double wk = ww[k];
                    r += wk * ((p >> 16) & 0xFF);
                    g += wk * ((p >> 8) & 0xFF);
                    b += wk * (p & 0xFF);
                }
                int i = y * dw + ox;
                mid[0][i] = clip8(r);
                mid[1][i] = clip8(g);
                mid[2][i] = clip8(b);
            }
        }

        int[] out = new int[dw * dh];
        for (int oy = 0; oy < dh; oy++) {
            int st = vc.start[oy];
            double[] ww = vc.w[oy];
            for (int ox = 0; ox < dw; ox++) {
                double r = 0, g = 0, b = 0;
                for (int k = 0; k < ww.length; k++) {
                    int i = (st + k) * dw + ox;
                    double wk = ww[k];
                    r += wk * mid[0][i];
                    g += wk * mid[1][i];
                    b += wk * mid[2][i];
                }
                out[oy * dw + ox] = 0xFF000000 | (clip8(r) << 16) | (clip8(g) << 8) | clip8(b);
            }
        }
        return out;
    }
}
