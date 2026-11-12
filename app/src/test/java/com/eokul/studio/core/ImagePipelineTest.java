package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImagePipelineTest {
    private static int[] solid(int w, int h, int rgb) {
        int[] p = new int[w * h];
        java.util.Arrays.fill(p, 0xFF000000 | rgb);
        return p;
    }

    @Test
    public void finishKeepsSizeAndColor() {
        int[] src = solid(1200, 1600, 0x808080);
        int[] out = Finish.render(src, 1200, 1600, new double[]{150, 150, 1050, 1370}, 133, 171, new Finish.Opts());
        assertEquals(133 * 171, out.length);
        int c = out[out.length / 2] & 255;
        assertTrue("Düz renk bozulmamalı: " + c, Math.abs(c - 128) <= 2);
    }

    @Test
    public void jpegHasHeaderAndMinimumSize() {
        int[] px = solid(133, 171, 0x6090C0);
        byte[] jpg = JpegEncoder.encode(px, 133, 171, 100, 300, null, 20 * 1024 + 200);
        assertEquals(0xFF, jpg[0] & 255);
        assertEquals(0xD8, jpg[1] & 255);
        assertTrue("En az 20 KB: " + jpg.length, jpg.length >= 20 * 1024);
    }

    @Test
    public void qualityDetectsDarkness() {
        int w = 600, h = 800;
        int[] sharp = new int[w * h];
        int[] dark = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (((x / 6) + (y / 6)) % 2 == 0) ? 200 : 60;
                sharp[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                int d = v / 6;
                dark[y * w + x] = 0xFF000000 | (d << 16) | (d << 8) | d;
            }
        }
        double[] box = {50, 50, 550, 750};
        assertTrue(Quality.analyze(dark, w, h, box).dark());
        assertTrue(!Quality.analyze(sharp, w, h, box).dark());
    }

    /** Detaylı (sert kenarlı) bir görüntü net sayılır; aynı görüntü güçlü bulanıklaştırılınca bulanık sayılmalı. */
    @Test
    public void qualityDetectsBlur() {
        int w = 600, h = 800;
        java.util.Random r = new java.util.Random(3);
        double[][] cell = new double[h / 4 + 1][w / 4 + 1];
        for (double[] row : cell) for (int i = 0; i < row.length; i++) row[i] = 40 + r.nextDouble() * 170;
        double[] g = new double[w * h];
        int[] sharp = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            g[y * w + x] = cell[y / 4][x / 4];
            int v = (int) g[y * w + x];
            sharp[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        // Büyük yarıçaplı ortalama süzgeç (iki geçiş): ayrıntılar kaybolur.
        double[] cur = g;
        for (int pass = 0; pass < 2; pass++) {
            double[] tmp = new double[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double s = 0; int n = 0;
                for (int k = -12; k <= 12; k++) { int xx = Math.max(0, Math.min(w - 1, x + k)); s += cur[y * w + xx]; n++; }
                tmp[y * w + x] = s / n;
            }
            double[] out = new double[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double s = 0; int n = 0;
                for (int k = -12; k <= 12; k++) { int yy = Math.max(0, Math.min(h - 1, y + k)); s += tmp[yy * w + x]; n++; }
                out[y * w + x] = s / n;
            }
            cur = out;
        }
        int[] blurred = new int[w * h];
        for (int i = 0; i < blurred.length; i++) { int v = (int) cur[i]; blurred[i] = 0xFF000000 | (v << 16) | (v << 8) | v; }
        double[] box = {50, 50, 550, 750};
        assertTrue("Detaylı görüntü bulanık sayılmamalı", !Quality.analyze(sharp, w, h, box).blurry());
        assertTrue("Bulanıklaştırılmış görüntü bulanık sayılmalı", Quality.analyze(blurred, w, h, box).blurry());
    }

    @Test
    public void qualityFlagsFlatContrast() {
        int w = 400, h = 500;
        int[] flat = new int[w * h];
        java.util.Arrays.fill(flat, 0xFF808080);
        assertTrue(Quality.analyze(flat, w, h, new double[]{20, 20, 380, 480}).flat());
    }
}
