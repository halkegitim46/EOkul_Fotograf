package com.eokul.studio.core;

/**
 * Kırpılacak alanın hızlı kalite denetimi: bulanıklık, aşırı karanlık / parlaklık.
 * Bulanıklık ölçüsü içerikten bağımsızdır: görüntü 3x3 ortalamayla hafifçe bulanıklaştırıldığında Laplace
 * varyansının ne kadarının kaldığına bakılır (keskin ≈ 0.10, orta bulanık ≈ 0.27, çok bulanık ≥ 0.4).
 */
public final class Quality {
    private Quality() {}

    public static final double BLUR_LIMIT = 0.25;
    public static final double DARK_LIMIT = 70.0;
    public static final double BRIGHT_LIMIT = 165.0;
    public static final double CONTRAST_LIMIT = 22.0;

    public static final class Report {
        public final double blurRatio;
        public final double brightness;
        public final double contrast;

        Report(double blurRatio, double brightness, double contrast) {
            this.blurRatio = blurRatio;
            this.brightness = brightness;
            this.contrast = contrast;
        }

        public boolean blurry() { return blurRatio > BLUR_LIMIT; }
        public boolean dark() { return brightness < DARK_LIMIT; }
        public boolean bright() { return brightness > BRIGHT_LIMIT; }
        /** Soluk / düz görüntü: yüz bölgesinde tonlar birbirine çok yakın. */
        public boolean flat() { return contrast < CONTRAST_LIMIT; }
    }

    private static final int W = 160;

    /** [box] = {x1,y1,x2,y2}: kırpma alanı (kaynak piksel koordinatları). */
    public static Report analyze(int[] argb, int iw, int ih, double[] box) {
        double bw = box[2] - box[0], bh = box[3] - box[1];
        int H = Math.max(8, (int) Math.round(W * bh / Math.max(bw, 1.0)));
        double[] g = new double[W * H];
        // Alan ortalamalı küçültme (kutu süzgeci)
        for (int y = 0; y < H; y++) {
            double sy1 = box[1] + bh * y / H, sy2 = box[1] + bh * (y + 1) / H;
            int y1 = clamp((int) Math.floor(sy1), ih), y2 = Math.max(y1 + 1, clamp((int) Math.ceil(sy2), ih + 1));
            for (int x = 0; x < W; x++) {
                double sx1 = box[0] + bw * x / W, sx2 = box[0] + bw * (x + 1) / W;
                int x1 = clamp((int) Math.floor(sx1), iw), x2 = Math.max(x1 + 1, clamp((int) Math.ceil(sx2), iw + 1));
                double sum = 0;
                int n = 0;
                for (int yy = y1; yy < Math.min(y2, ih); yy++) {
                    for (int xx = x1; xx < Math.min(x2, iw); xx++) {
                        int p = argb[yy * iw + xx];
                        sum += 0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255);
                        n++;
                    }
                }
                g[y * W + x] = n > 0 ? sum / n : 0;
            }
        }
        double v0 = lapVar(g, W, H);
        double[] b = box3(g, W, H);
        double v1 = lapVar(b, W, H);
        double ratio = v0 < 1e-6 ? 0.0 : v1 / v0;
        // Yönlü kontrol: hareket (kamera sallanması) bulanıklığı tek eksende detayı yok eder, genel ölçüde kaybolur.
        double rx = dirRatio(g, b, W, H, true), ry = dirRatio(g, b, W, H, false);
        ratio = Math.max(ratio, Math.max(rx, ry));

        int cx1 = (int) (W * 0.2), cx2 = (int) (W * 0.8), cy1 = (int) (H * 0.2), cy2 = (int) (H * 0.8);
        double s = 0, s2 = 0;
        int n = 0;
        for (int y = cy1; y < cy2; y++) {
            for (int x = cx1; x < cx2; x++) {
                double v = g[y * W + x];
                s += v;
                s2 += v * v;
                n++;
            }
        }
        double mean = s / n;
        double sd = Math.sqrt(Math.max(0, s2 / n - mean * mean));
        return new Report(ratio, mean, sd);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max - 1 < 0 ? 0 : max));
    }

    private static double lapVar(double[] g, int w, int h) {
        double s = 0, s2 = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double l = 4 * g[y * w + x] - g[(y - 1) * w + x] - g[(y + 1) * w + x] - g[y * w + x - 1] - g[y * w + x + 1];
                s += l;
                s2 += l * l;
                n++;
            }
        }
        double m = s / n;
        return s2 / n - m * m;
    }

    private static double dirVar(double[] g, int w, int h, boolean horizontal) {
        double s = 0, s2 = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int o = y * w + x;
                double d = horizontal ? 2 * g[o] - g[o - 1] - g[o + 1] : 2 * g[o] - g[o - w] - g[o + w];
                s += d;
                s2 += d * d;
                n++;
            }
        }
        double m = s / n;
        return s2 / n - m * m;
    }

    private static double dirRatio(double[] g, double[] blurred, int w, int h, boolean horizontal) {
        double v0 = dirVar(g, w, h, horizontal);
        return v0 < 1e-6 ? 0.0 : dirVar(blurred, w, h, horizontal) / v0;
    }

    private static double[] box3(double[] g, int w, int h) {
        double[] o = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double s = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int yy = Math.min(h - 1, Math.max(0, y + dy)), xx = Math.min(w - 1, Math.max(0, x + dx));
                        s += g[yy * w + xx];
                    }
                }
                o[y * w + x] = s / 9;
            }
        }
        return o;
    }
}
