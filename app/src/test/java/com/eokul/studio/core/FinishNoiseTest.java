package com.eokul.studio.core;

import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class FinishNoiseTest {
    private static final int W = 600, H = 800;

    /** Düz zemin + yarı koyu daire; ilintili (lekeli) gürültü eklenmiş. */
    private static int[] noisy(long seed, double amp) {
        Random r = new Random(seed);
        double[] n = new double[W * H];
        for (int i = 0; i < n.length; i++) n[i] = r.nextGaussian();
        double[] b = new double[n.length];   // 3x3 ortalama: ilintili gürültü
        for (int y = 1; y < H - 1; y++)
            for (int x = 1; x < W - 1; x++) {
                double s = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) s += n[(y + dy) * W + x + dx];
                b[y * W + x] = s / 3.0 * amp;
            }
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                boolean in = (x - 300) * (x - 300) + (y - 350) * (y - 350) < 140 * 140;
                double base = in ? 120 : 225;
                int v = (int) Math.max(0, Math.min(255, Math.round(base + b[y * W + x])));
                px[y * W + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        return px;
    }

    private static double sigma(int[] out, int w, int h) {
        float[] y = new float[w * h];
        for (int i = 0; i < y.length; i++) y[i] = out[i] & 255;
        return Finish.noiseSigma(y, w, h);
    }

    /** Geniş kırpmada da (≥3x küçültme) gürültü azaltma devreye girmeli. */
    @Test public void denoiseRunsOnLargeCrops() {
        int[] src = noisy(5, 14);
        double[] box = {50, 30, 550, 30 + 500 * 171 / 133.0};   // 500 px → 3.8x küçültme
        Finish.Opts off = new Finish.Opts();
        off.denoise = false;
        off.sharpen = 0;
        Finish.Opts on = new Finish.Opts();
        on.sharpen = 0;
        double sOff = sigma(Finish.render(src, W, H, box, 133, 171, off), 133, 171);
        double sOn = sigma(Finish.render(src, W, H, box, 133, 171, on), 133, 171);
        assertTrue("gürültü azalmalı: " + sOn + " < " + sOff, sOn < sOff * 0.85);
    }

    /** Gürültülü karede keskinleştirme, temiz kareden daha zayıf uygulanır. */
    @Test public void sharpenBacksOffWhenNoisy() {
        int[] src = noisy(9, 14);
        double[] box = {50, 30, 550, 30 + 500 * 171 / 133.0};
        Finish.Opts sharp = new Finish.Opts();
        sharp.denoise = false;
        Finish.Opts flat = new Finish.Opts();
        flat.denoise = false;
        flat.sharpen = 0;
        double a = sigma(Finish.render(src, W, H, box, 133, 171, sharp), 133, 171);
        double b = sigma(Finish.render(src, W, H, box, 133, 171, flat), 133, 171);
        assertTrue("fazla keskinleştirme: " + a + " vs " + b, a < b * 1.15);
    }
}
