package com.eokul.studio.core;

import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class EncodeFitTest {
    private static int[] textured(int w, int h) {
        Random r = new Random(3);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) {
            int v = (int) Math.max(0, Math.min(255, 128 + r.nextGaussian() * 40));
            p[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return p;
    }

    @Test public void qualityDropsToFitMax() {
        int[] px = textured(133, 171);
        byte[] free = JpegEncoder.encode(px, 133, 171, 100, 300, null, 0);
        int max = free.length / 2;
        byte[] fit = JpegEncoder.encodeFit(px, 133, 171, 100, 300, null, 0, max);
        assertTrue("fit=" + fit.length + " max=" + max, fit.length <= max);
        assertTrue(fit.length > max / 3);   // gereksiz düşük kalite seçilmemeli
    }

    @Test public void noLimitKeepsQuality() {
        int[] px = textured(133, 171);
        byte[] a = JpegEncoder.encode(px, 133, 171, 100, 300, null, 0);
        byte[] b = JpegEncoder.encodeFit(px, 133, 171, 100, 300, null, 0, 0);
        assertTrue(a.length == b.length);
    }

    @Test public void minBytesStillPadded() {
        int[] px = new int[133 * 171];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        byte[] o = JpegEncoder.encodeFit(px, 133, 171, 100, 300, null, 20_000, 150_000);
        assertTrue(o.length >= 20_000 && o.length <= 150_000);
    }
}
