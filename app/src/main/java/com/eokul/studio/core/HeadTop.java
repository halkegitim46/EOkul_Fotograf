package com.eokul.studio.core;

import java.util.Arrays;

/**
 * Saçın gerçek tepe noktasını arka plana bakarak bulur (masaüstü v17.6 estimate_head_top ile aynı algoritma).
 *
 * Yüzün üstündeki şeritten arka plan rengi alınır; yüzün ortasındaki sütun bandında bu renkten
 * belirgin ayrışan ilk satır "baş üstü" sayılır. Düzgün olmayan arka planda veya saç arka planla aynı
 * renkteyse NaN döner.
 */
public final class HeadTop {
    private HeadTop() {}

    private static final double[] GAMMA = new double[256];
    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            GAMMA[i] = (c <= 0.04045) ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    private static double f(double t) {
        return (t > 0.008856) ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0;
    }

    private static int to8(double v) {
        long r = Math.round(v);
        return (int) (r < 0 ? 0 : (r > 255 ? 255 : r));
    }

    /** RGB → OpenCV 8-bit Lab (L*255/100, a+128, b+128). */
    private static void lab(int p, float[] out, int o) {
        double r = GAMMA[(p >> 16) & 0xFF], g = GAMMA[(p >> 8) & 0xFF], b = GAMMA[p & 0xFF];
        double x = (0.412453 * r + 0.357580 * g + 0.180423 * b) / 0.950456;
        double y = 0.212671 * r + 0.715160 * g + 0.072169 * b;
        double z = (0.019334 * r + 0.119193 * g + 0.950227 * b) / 1.088754;
        double fy = f(y);
        double l = (y > 0.008856) ? 116.0 * fy - 16.0 : 903.3 * y;
        out[o] = to8(l * 255.0 / 100.0);
        out[o + 1] = to8(500.0 * (f(x) - fy) + 128.0);
        out[o + 2] = to8(200.0 * (fy - f(z)) + 128.0);
    }

    private static double median(float[] a) {
        float[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        return (n % 2 == 1) ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    private static double percentile(double[] sorted, double q) {
        double pos = (sorted.length - 1) * q / 100.0;
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    /**
     * @param argb   tüm görüntünün pikselleri (0xAARRGGBB)
     * @param face   {x1,y1,x2,y2}
     * @return saç tepesi y koordinatı veya Double.NaN
     */
    public static double estimate(int[] argb, int iw, int ih, double[] face) {
        try {
            double fx1 = face[0], fy1 = face[1], fx2 = face[2];
            double fw = fx2 - fx1;
            double u = Crop.faceUnit(face);
            double cx = (fx1 + fx2) / 2.0;
            double y0 = Math.max(0.0, fy1 - 1.2 * u);
            double y1 = Math.min((double) ih, fy1 + 0.25 * u);
            if (fy1 - y0 < 0.35 * u || y1 - y0 < 24 || fw < 24) return Double.NaN;
            double xa = Math.max(0.0, cx - 1.4 * fw), xb = Math.min((double) iw, cx + 1.4 * fw);

            int ry0 = (int) y0, ry1 = (int) y1, rx0 = (int) xa, rx1 = (int) xb;
            int rw = rx1 - rx0, rh = ry1 - ry0;
            if (rh < 16 || rw < 16) return Double.NaN;

            double k = Math.min(1.0, 360.0 / rw);
            int mw = rw, mh = rh;
            int[] pix;
            if (k < 1.0) {
                mw = Math.max(1, (int) Math.round(rw * k));
                mh = Math.max(1, (int) Math.round(rh * k));
                pix = areaDownscale(argb, iw, rx0, ry0, rw, rh, mw, mh);
            } else {
                pix = new int[rw * rh];
                for (int y = 0; y < rh; y++)
                    System.arraycopy(argb, (ry0 + y) * iw + rx0, pix, y * rw, rw);
            }

            float[] lab = new float[mw * mh * 3];
            for (int i = 0; i < mw * mh; i++) lab(pix[i], lab, i * 3);

            int sr = Math.max(3, (int) (mh * 0.12));
            int sn = sr * mw;
            float[] c0 = new float[sn], c1 = new float[sn], c2 = new float[sn];
            for (int i = 0; i < sn; i++) {
                c0[i] = lab[i * 3];
                c1[i] = lab[i * 3 + 1];
                c2[i] = lab[i * 3 + 2];
            }
            double b0 = median(c0), b1 = median(c1), b2 = median(c2);

            double[] dev = new double[sn];
            double sum = 0;
            for (int i = 0; i < sn; i++) {
                double d0 = c0[i] - b0, d1 = c1[i] - b1, d2 = c2[i] - b2;
                dev[i] = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
                sum += dev[i];
            }
            Arrays.sort(dev);
            if (sum / sn > 9.0 || percentile(dev, 90) > 22.0) return Double.NaN;  // düzgün olmayan fon
            double thr = Math.max(16.0, percentile(dev, 99) * 1.5 + 2.0);

            boolean[] raw = new boolean[mw * mh];
            for (int i = 0; i < mw * mh; i++) {
                double d0 = lab[i * 3] - b0, d1 = lab[i * 3 + 1] - b1, d2 = lab[i * 3 + 2] - b2;
                raw[i] = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2) > thr;
            }
            boolean[] mask = majority5(raw, mw, mh);

            int cs = Math.max(0, (int) (((cx - 0.5 * fw) - xa) * k));
            int ce = Math.min(mw, (int) (((cx + 0.5 * fw) - xa) * k) + 1);
            if (ce - cs < 4) return Double.NaN;
            double[] frac = new double[mh];
            for (int y = 0; y < mh; y++) {
                int cnt = 0;
                for (int x = cs; x < ce; x++) if (mask[y * mw + x]) cnt++;
                frac[y] = (double) cnt / (ce - cs);
            }

            final int run = 4;
            int hit = -1;
            for (int r = sr; r < mh - run; r++) {
                boolean all = true;
                for (int j = 0; j < run; j++) {
                    if (frac[r + j] < 0.30) { all = false; break; }
                }
                if (all) { hit = r; break; }
            }
            if (hit < 0 || hit <= sr) return Double.NaN;
            double top = y0 + hit / k;
            if (!(fy1 - 1.1 * u <= top && top <= fy1 + 0.1 * u)) return Double.NaN;
            return top;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** 5x5 ikili medyan (kenarlar tekrarlanır): 25 pikselin en az 13'ü doluysa dolu. */
    private static boolean[] majority5(boolean[] m, int w, int h) {
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int cnt = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    int yy = Math.min(Math.max(y + dy, 0), h - 1);
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = Math.min(Math.max(x + dx, 0), w - 1);
                        if (m[yy * w + xx]) cnt++;
                    }
                }
                out[y * w + x] = cnt >= 13;
            }
        }
        return out;
    }

    /** Alan ortalamalı (INTER_AREA benzeri) küçültme. */
    private static int[] areaDownscale(int[] src, int sw, int ox, int oy, int rw, int rh, int dw, int dh) {
        int[] out = new int[dw * dh];
        double sx = (double) rw / dw, sy = (double) rh / dh;
        for (int y = 0; y < dh; y++) {
            double ya = y * sy, yb = ya + sy;
            int y0 = (int) Math.floor(ya), y1 = Math.min(rh, (int) Math.ceil(yb));
            for (int x = 0; x < dw; x++) {
                double xa = x * sx, xb = xa + sx;
                int x0 = (int) Math.floor(xa), x1 = Math.min(rw, (int) Math.ceil(xb));
                double r = 0, g = 0, b = 0, wsum = 0;
                for (int yy = y0; yy < y1; yy++) {
                    double wy = Math.min(yb, yy + 1) - Math.max(ya, yy);
                    for (int xx = x0; xx < x1; xx++) {
                        double wx = Math.min(xb, xx + 1) - Math.max(xa, xx);
                        double wgt = wx * wy;
                        int p = src[(oy + yy) * sw + ox + xx];
                        r += wgt * ((p >> 16) & 0xFF);
                        g += wgt * ((p >> 8) & 0xFF);
                        b += wgt * (p & 0xFF);
                        wsum += wgt;
                    }
                }
                out[y * dw + x] = 0xFF000000 | (to8(r / wsum) << 16) | (to8(g / wsum) << 8) | to8(b / wsum);
            }
        }
        return out;
    }
}
