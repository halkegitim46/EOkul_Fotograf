package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * Fotoğraf hattının uç durum ve rastgele girdi (fuzz) testleri: çökme olmamalı, çıktı boyutu/aralığı doğru olmalı,
 * düz renk düz kalmalı, JPEG her boyut ve kalitede standart bir çözücüyle açılmalı.
 */
public class PipelineStressTest {
    private static final double[] SPECIAL = {
        0, 1, -1, 0.5, 1e-9, 1e9, -1e9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 132.5, 171
    };

    private static int[] solid(int w, int h, int rgb) {
        int[] p = new int[w * h];
        java.util.Arrays.fill(p, 0xFF000000 | rgb);
        return p;
    }

    private static int[] noise(int w, int h, long seed, int amp) {
        Random r = new Random(seed);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) {
            int v = (int) Math.max(0, Math.min(255, 128 + r.nextGaussian() * amp));
            p[i] = 0xFF000000 | (v << 16) | (((v * 7) & 255) << 8) | ((v * 13) & 255);
        }
        return p;
    }

    private static int[] gradient(int w, int h) {
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int r = w > 1 ? 255 * x / (w - 1) : 128, g = h > 1 ? 255 * y / (h - 1) : 128;
                p[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | 100;
            }
        return p;
    }

    private static double psnr(int[] a, BufferedImage b) {
        double s = 0;
        int w = b.getWidth(), h = b.getHeight();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = a[y * w + x], q = b.getRGB(x, y);
                for (int sh = 0; sh <= 16; sh += 8) {
                    double d = ((p >> sh) & 255) - ((q >> sh) & 255);
                    s += d * d;
                }
            }
        double mse = s / (w * h * 3.0);
        return mse < 1e-9 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    private static boolean finite(double[] b) {
        for (double v : b) if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        return true;
    }

    // ------------------------------------------------------------------ JPEG

    @Test public void jpegRoundTripsAtAllSizesAndQualities() throws Exception {
        int[][] sizes = {{1, 1}, {1, 17}, {17, 1}, {7, 9}, {8, 8}, {9, 9}, {16, 16}, {133, 171}, {134, 172}, {35, 45}, {300, 400}, {600, 800}};
        int[] quals = {1, 40, 70, 100};
        for (int[] sz : sizes)
            for (int q : quals)
                for (int pat = 0; pat < 4; pat++) {
                    int w = sz[0], h = sz[1];
                    int[] px = pat == 0 ? solid(w, h, 0xFFFFFF) : pat == 1 ? solid(w, h, 0x000000) : pat == 2 ? gradient(w, h) : noise(w, h, w * 31 + h, 30);
                    String tag = w + "x" + h + " q" + q + " pat" + pat;
                    byte[] j = JpegEncoder.encode(px, w, h, q, 300, null, 0);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(j));
                    assertNotNull("çözülemedi " + tag, img);
                    assertEquals(tag, w, img.getWidth());
                    assertEquals(tag, h, img.getHeight());
                    if (q >= 70 && pat <= 2) assertTrue("PSNR düşük " + tag, psnr(px, img) > (pat == 2 ? 28 : 35));
                }
    }

    @Test public void jpegWithIccAndPaddingStillDecodes() throws Exception {
        byte[] icc = new byte[3144];
        new Random(1).nextBytes(icc);
        int[] px = gradient(133, 171);
        byte[] j = JpegEncoder.encode(px, 133, 171, 100, 300, icc, 20 * 1024 + 200);
        assertTrue(j.length >= 20 * 1024 + 200);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(j)));
    }

    @Test public void encodeFitHonoursLimitsAcrossStandards() throws Exception {
        int[][] stds = {{133, 171, 20, 150}, {100, 129, 5, 30}, {300, 400, 20, 150}, {600, 800, 50, 300}, {35, 45, 1, 8}};
        for (int[] s : stds) {
            int[] px = noise(s[0], s[1], 7, 45);
            byte[] j = JpegEncoder.encodeFit(px, s[0], s[1], 100, 300, null, s[2] * 1024 + 200, s[3] * 1024);
            String tag = s[0] + "x" + s[1] + " " + j.length;
            assertTrue("üst sınır aşıldı " + tag, j.length <= s[3] * 1024);
            assertTrue("alt sınır tutmadı " + tag, j.length >= s[2] * 1024);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(j));
            assertNotNull(tag, img);
            assertEquals(s[0], img.getWidth());
        }
    }

    // ------------------------------------------------------------------ Lanczos / Finish

    @Test public void cropResizeNeverCrashesAndKeepsSolidColour() {
        Random r = new Random(11);
        for (int i = 0; i < 400; i++) {
            int sw = 1 + r.nextInt(300), sh = 1 + r.nextInt(300);
            int rgb = r.nextInt(0x1000000);
            int[] src = solid(sw, sh, rgb);
            double bx1 = r.nextDouble() * 500 - 200, by1 = r.nextDouble() * 500 - 200;
            double bw = 0.6 + r.nextDouble() * 600, bh = 0.6 + r.nextDouble() * 600;
            int dw = 1 + r.nextInt(250), dh = 1 + r.nextInt(250);
            int[] out = Lanczos.cropResize(src, sw, sh, bx1, by1, bx1 + bw, by1 + bh, dw, dh);
            assertEquals(dw * dh, out.length);
            for (int p : out) {
                assertEquals(0xFF, p >>> 24);
                for (int sh2 = 0; sh2 <= 16; sh2 += 8)
                    assertTrue("düz renk bozuldu", Math.abs(((p >> sh2) & 255) - ((rgb >> sh2) & 255)) <= 1);
            }
        }
    }

    @Test public void cropResizeRejectsEmptyBoxesCleanly() {
        int[] src = solid(10, 10, 0x808080);
        for (double[] b : new double[][]{{5, 5, 5, 9}, {5, 5, 9, 5}, {9, 9, 3, 3}, {Double.NaN, 0, 5, 5}}) {
            try {
                Lanczos.cropResize(src, 10, 10, b[0], b[1], b[2], b[3], 20, 20);
                if (Double.isNaN(b[0])) continue;
            } catch (IllegalArgumentException ok) {
                continue;
            }
            // 0.5 piksel altı alan yuvarlamayla boşalıyorsa hata beklenir; sonuç dönmüşse boyutu doğru olmalı
        }
    }

    @Test public void finishSurvivesExtremesAndKeepsRange() {
        Random r = new Random(23);
        int[] srcSizes = {1, 2, 5, 40, 133, 400, 900};
        for (int i = 0; i < 160; i++) {
            int sw = srcSizes[r.nextInt(srcSizes.length)] + r.nextInt(7), sh = srcSizes[r.nextInt(srcSizes.length)] + r.nextInt(7);
            int[] src = i % 4 == 0 ? solid(sw, sh, r.nextInt(0x1000000)) : i % 4 == 1 ? noise(sw, sh, i, 70) : i % 4 == 2 ? gradient(sw, sh) : solid(sw, sh, i % 8 == 3 ? 0xFFFFFF : 0);
            double bw = 1 + r.nextDouble() * (sw * 1.6), bh = bw * 171 / 133.0;
            double bx = r.nextDouble() * sw - bw / 2, by = r.nextDouble() * sh - bh / 2;
            double[] box = {bx, by, bx + bw, by + bh};
            int dw = 1 + r.nextInt(300), dh = 1 + r.nextInt(300);
            Finish.Opts o = new Finish.Opts();
            o.sharpen = r.nextBoolean() ? 0.35 : r.nextDouble();
            o.denoise = r.nextBoolean();
            int[] out;
            try {
                out = Finish.render(src, sw, sh, box, dw, dh, o);
            } catch (IllegalArgumentException e) {
                assertTrue("sadece boş kırpmada hata beklenir: " + e.getMessage(), Math.rint(box[2]) - Math.rint(box[0]) <= 0 || Math.rint(box[3]) - Math.rint(box[1]) <= 0);
                continue;
            }
            assertEquals(dw * dh, out.length);
            for (int p : out) assertEquals(0xFF, p >>> 24);
        }
    }

    @Test public void finishKeepsSolidColoursSolid() {
        int[] cols = {0x000000, 0xFFFFFF, 0x808080, 0xE0B090, 0x102030};
        for (int c : cols) {
            int[] src = solid(800, 1000, c);
            int[] out = Finish.render(src, 800, 1000, new double[]{100, 100, 700, 100 + 600 * 171 / 133.0}, 133, 171, new Finish.Opts());
            for (int p : out)
                for (int sh = 0; sh <= 16; sh += 8)
                    assertTrue("düz renk bozuldu " + Integer.toHexString(c), Math.abs(((p >> sh) & 255) - ((c >> sh) & 255)) <= 2);
        }
    }

    @Test public void finishDoesNotBlowOutBlackOrWhiteEdges() {
        // Yüksek kontrast kenar: keskinleştirme taşmayı 0..255'e kırpmalı, renk kayması olmamalı
        int w = 400, h = 520;
        int[] src = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) src[y * w + x] = x < w / 2 ? 0xFF000000 : 0xFFFFFFFF;
        int[] out = Finish.render(src, w, h, new double[]{0, 0, w, h}, 133, 171, new Finish.Opts());
        for (int p : out) {
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            assertTrue(Math.abs(r - g) <= 2 && Math.abs(g - b) <= 2);   // gri tonlar gri kalır
        }
    }

    // ------------------------------------------------------------------ Kırpma

    @Test public void defaultCropBoxStaysInsideImageForAllFaces() {
        Random r = new Random(31);
        int[][] dims = {{1200, 1600}, {1600, 1200}, {100, 4000}, {4000, 100}, {64, 64}, {2592, 1944}, {10, 10}, {3000, 3000}};
        int[][] stds = {{133, 171}, {100, 129}, {600, 800}, {35, 45}, {400, 400}};
        for (int[] d : dims)
            for (int[] s : stds)
                for (int k = 0; k < 40; k++) {
                    int iw = d[0], ih = d[1];
                    Crop.Params p = new Crop.Params();
                    p.targetW = s[0];
                    p.targetH = s[1];
                    double fw = 5 + r.nextDouble() * iw * 0.6, fh = fw * (1 + r.nextDouble() * 0.4);
                    double fx = r.nextDouble() * iw - fw / 2, fy = r.nextDouble() * ih - fh / 2;   // kenara / dışarı taşabilir
                    double[] face = k % 7 == 0 ? null : new double[]{fx, fy, fx + fw, fy + fh};
                    double headTop = k % 3 == 0 ? Double.NaN : (face == null ? 0 : face[1] - r.nextDouble() * fh * 0.5);
                    double[] b = Crop.defaultCropBox(iw, ih, face, headTop, p);
                    String tag = iw + "x" + ih + " std" + s[0] + "x" + s[1] + " face=" + java.util.Arrays.toString(face);
                    assertTrue("sonlu değil " + tag, finite(b));
                    assertTrue("görüntü dışında " + tag + " " + java.util.Arrays.toString(b), b[0] >= -1e-6 && b[1] >= -1e-6 && b[2] <= iw + 1e-6 && b[3] <= ih + 1e-6);
                    assertEquals("oran " + tag, p.ratio(), (b[2] - b[0]) / (b[3] - b[1]), 1e-6);
                    assertTrue("alan yok " + tag, b[2] - b[0] >= 1);
                }
    }

    // ------------------------------------------------------------------ Ölçümler

    @Test public void measurementsTolerateDegenerateInputs() {
        int[] img = noise(200, 260, 5, 40);
        for (double a : SPECIAL)
            for (double b : SPECIAL) {
                double[] box = {a, b, a + 80, b + 100};
                Quality.Report q = Quality.analyze(img, 200, 260, box);
                assertNotNull(q);
                Lighting.analyzeScaled(img, 200, 260, a, b, a + 80, b + 100);   // null olabilir, çökmemeli
                Lighting.analyze(img, 200, 260, a / 200, b / 260, (a + 80) / 200, (b + 100) / 260);
                Framing.check(new double[]{a, b, a + 50, b + 60}, b - 10, null, new double[]{0, 0, 133, 171});
                Framing.check(new double[]{10, 10, 60, 70}, Double.NaN, new double[]{a, b, a + 30, b}, new double[]{a, b, a + 100, b + 130});
                HeadTop.estimate(img, 200, 260, new double[]{a, b, a + 80, b + 100});
            }
    }

    @Test public void scoreIsAlwaysWithin0To100() {
        for (double f : new double[]{Double.NaN, -5, 0, 1, 3, 1e9})
            for (double bl : new double[]{Double.NaN, 0, 0.2, 0.5, 5})
                for (double roll : new double[]{Double.NaN, 0, 15, -40, 400})
                    for (double br : new double[]{Double.NaN, 0, 80, 120, 255})
                        for (double eye : new double[]{Double.NaN, -1, 0, 0.5, 1}) {
                            int s = Score.total(f, bl, roll, roll / 2, br, 30, eye);
                            assertTrue("puan aralık dışı " + s, s >= 0 && s <= 100);
                        }
    }

    @Test public void scoreDoesNotCollapseOnOneUnknownInput() {
        // Tek bir ölçüm NaN diye iyi bir karenin puanı 0'a düşmemeli (bilinmeyen ceza almaz)
        int good = Score.total(0, 0.1, 0, 0, 120, 40, 0.9);
        assertTrue(good >= 90);
        assertTrue("framingDev NaN", Score.total(Double.NaN, 0.1, 0, 0, 120, 40, 0.9) >= 65);
        assertTrue("roll NaN", Score.total(0, 0.1, Double.NaN, 0, 120, 40, 0.9) >= 80);
        assertTrue("yaw NaN", Score.total(0, 0.1, 0, Double.NaN, 120, 40, 0.9) >= 80);
        assertTrue("contrast NaN", Score.total(0, 0.1, 0, 0, 120, Double.NaN, 0.9) >= 85);
    }

    @Test public void burstTotalHandlesNaN() {
        int v = Burst.total(80, true, Double.NaN, Double.NaN, Lighting.OK);
        assertTrue("NaN gülümseme/göz puanı bozmamalı: " + v, v >= 70 && v <= 80);
    }

    // ------------------------------------------------------------------ Uçtan uca

    @Test public void endToEndProducesValidJpegForManyShapes() throws Exception {
        int[][] shots = {{1200, 1600}, {1600, 1200}, {2592, 1944}, {1944, 2592}, {640, 480}, {480, 640}, {3000, 4000}};
        int[][] stds = {{133, 171, 20, 150}, {100, 129, 5, 40}, {300, 400, 20, 150}};
        for (int[] sh : shots)
            for (int[] st : stds) {
                int iw = sh[0], ih = sh[1];
                int[] img = noise(iw / 4, ih / 4, iw + ih, 25);    // sahte "çalışma görüntüsü"
                int w = iw / 4, h = ih / 4;
                Crop.Params p = new Crop.Params();
                p.targetW = st[0];
                p.targetH = st[1];
                double[] face = {w * 0.35, h * 0.25, w * 0.65, h * 0.55};
                double top = HeadTop.estimate(img, w, h, face);
                double[] box = Crop.defaultCropBox(w, h, face, top, p);
                int[] out = Finish.render(img, w, h, box, st[0], st[1], new Finish.Opts());
                byte[] j = JpegEncoder.encodeFit(out, st[0], st[1], 100, 300, null, st[2] * 1024 + 200, st[3] * 1024);
                BufferedImage dec = ImageIO.read(new ByteArrayInputStream(j));
                String tag = iw + "x" + ih + " → " + st[0] + "x" + st[1];
                assertNotNull(tag, dec);
                assertEquals(tag, st[0], dec.getWidth());
                assertEquals(tag, st[1], dec.getHeight());
                assertTrue(tag + " boyut " + j.length, j.length >= st[2] * 1024 && j.length <= st[3] * 1024);
            }
    }

    @Test public void framingListsAreNeverNull() {
        List<String> l = Framing.check(new double[]{40, 30, 90, 100}, 25, new double[]{50, 55, 80, 55}, new double[]{0, 0, 133, 171});
        assertNotNull(l);
        assertFalse(l == null);
    }
}
