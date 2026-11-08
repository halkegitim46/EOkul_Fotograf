package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LightingTest {
    private static final int W = 96, H = 128;

    private static int gray(int v) { return 0xFF000000 | (v << 16) | (v << 8) | v; }

    /** Yüz kutusu (0.3..0.7, 0.25..0.6); sol/sağ yarı ve arka plan parlaklığı ayrı verilir. */
    private static int[] frame(int bg, int faceL, int faceR) {
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean face = x >= 0.30 * W && x < 0.70 * W && y >= 0.25 * H && y < 0.60 * H;
                px[y * W + x] = gray(face ? (x < W / 2 ? faceL : faceR) : bg);
            }
        }
        return px;
    }

    private static Lighting.Report run(int[] px) {
        return Lighting.analyze(px, W, H, 0.30, 0.25, 0.70, 0.60);
    }

    @Test public void normalLightIsOk() {
        assertEquals(Lighting.OK, run(frame(150, 140, 140)).code);
    }

    @Test public void brightBackgroundDarkFaceIsBacklit() {
        assertEquals(Lighting.BACKLIT, run(frame(215, 85, 85)).code);
    }

    @Test public void darkRoomIsNotBacklit() {
        // arka plan da karanlık: bu "karanlık" sorunudur (Quality), arka ışık değil
        assertEquals(Lighting.OK, run(frame(60, 55, 55)).code);
    }

    @Test public void oneSidedLightIsUneven() {
        assertEquals(Lighting.UNEVEN, run(frame(130, 60, 170)).code);
    }

    @Test public void blownOutFaceIsGlare() {
        assertEquals(Lighting.GLARE, run(frame(180, 252, 252)).code);
    }

    @Test public void tooSmallOrBadBoxGivesNull() {
        assertNull(Lighting.analyze(new int[4], 2, 2, 0.3, 0.3, 0.7, 0.7));
        assertNull(Lighting.analyze(frame(150, 140, 140), W, H, 0.7, 0.3, 0.3, 0.7));
        assertNotNull(run(frame(150, 140, 140)));
    }

    @Test public void scaledAnalysisOnFullResolutionFrame() {
        int w = 480, h = 640;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                boolean face = x >= 0.30 * w && x < 0.70 * w && y >= 0.25 * h && y < 0.60 * h;
                px[y * w + x] = gray(face ? 85 : 215);
            }
        Lighting.Report r = Lighting.analyzeScaled(px, w, h, 0.30 * w, 0.25 * h, 0.70 * w, 0.60 * h);
        assertNotNull(r);
        assertEquals(Lighting.BACKLIT, r.code);
        assertNull(Lighting.analyzeScaled(null, w, h, 1, 1, 2, 2));
    }
}
