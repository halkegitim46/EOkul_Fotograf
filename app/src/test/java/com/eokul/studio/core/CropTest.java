package com.eokul.studio.core;

import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class CropTest {
    private static final double RATIO = 133.0 / 171.0;

    private static void checkBox(double[] b, int iw, int ih, String ctx) {
        double w = b[2] - b[0], h = b[3] - b[1];
        assertTrue(ctx + " sınır dışı: " + java.util.Arrays.toString(b),
                b[0] >= -1e-6 && b[1] >= -1e-6 && b[2] <= iw + 1e-6 && b[3] <= ih + 1e-6);
        assertTrue(ctx + " oran bozuk: " + (w / h), Math.abs(w / h - RATIO) < 1e-3);
        assertTrue(ctx + " boyut sıfır", w > 1 && h > 1);
    }

    @Test
    public void boxAlwaysInsideImageWithCorrectRatio() {
        Random r = new Random(42);
        Crop.Params p = new Crop.Params();
        p.topGap = 0.03;
        for (int n = 0; n < 3000; n++) {
            int iw = 400 + r.nextInt(2000), ih = 400 + r.nextInt(2400);
            double fw = 40 + r.nextDouble() * Math.min(iw, ih) * 0.8;
            double fh = fw * (1.0 + r.nextDouble() * 0.3);
            double x1 = r.nextDouble() * Math.max(1, iw - fw), y1 = r.nextDouble() * Math.max(1, ih - fh);
            double[] face = {x1, y1, Math.min(iw, x1 + fw), Math.min(ih, y1 + fh)};
            double head = (n % 3 == 0) ? Double.NaN : face[1] - fh * (0.05 + r.nextDouble() * 0.4);
            checkBox(Crop.defaultCropBox(iw, ih, face, head, p), iw, ih, "yüzlü #" + n);
        }
    }

    @Test
    public void noFaceGivesCenteredValidBox() {
        Crop.Params p = new Crop.Params();
        int[][] sizes = {{1200, 1600}, {1600, 1200}, {500, 500}, {3000, 400}, {400, 3000}};
        for (int[] s : sizes) checkBox(Crop.defaultCropBox(s[0], s[1], null, Double.NaN, p), s[0], s[1], "yüzsüz " + s[0] + "x" + s[1]);
    }

    @Test
    public void typicalPortraitKeepsWholeFaceAndHeadTop() {
        Crop.Params p = new Crop.Params();
        p.topGap = 0.03;
        p.headFrac = 0.62;
        p.hairMin = 0.01;
        int iw = 1200, ih = 1600;
        double[] face = {450, 500, 750, 860};
        double headTop = 440;
        double[] b = Crop.defaultCropBox(iw, ih, face, headTop, p);
        checkBox(b, iw, ih, "tipik");
        assertTrue("Saç tepesi kadrajda", b[1] <= headTop);
        assertTrue("Çene kadrajda", b[3] >= face[3]);
        assertTrue("Yüz yatayda kadrajda", b[0] <= face[0] && b[2] >= face[2]);
    }

    @Test public void scaleBoxMapsToResizedOriginal() {
        // 2592x1944 çalışma görüntüsünde kaydedilmiş kutu, 1600x1200'e küçültülmüş orijinalde aynı bölgeyi göstermeli
        double[] b = {648, 486, 1944, 1944 * 0.75 + 0};
        double[] r = Crop.scaleBox(b, 2592, 1944, 1600, 1200);
        double k = 1600 / 2592.0;
        org.junit.Assert.assertArrayEquals(new double[]{648 * k, 486 * k, 1944 * k, b[3] * (1200 / 1944.0)}, r, 1e-9);
    }

    @Test public void scaleBoxIsNoOpWhenUnknownOrSame() {
        double[] b = {10, 20, 110, 150};
        org.junit.Assert.assertArrayEquals(b, Crop.scaleBox(b, 0, 0, 1600, 1200), 0);
        org.junit.Assert.assertArrayEquals(b, Crop.scaleBox(b, 1600, 1200, 1600, 1200), 0);
        org.junit.Assert.assertNotSame(b, Crop.scaleBox(b, 1600, 1200, 1600, 1200));
    }
}
