package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScoreTest {
    @Test public void perfectIs100() {
        assertEquals(100, Score.total(0, 0.10, 0, 0, 115, 50, 0.9));
    }

    @Test public void unknownsAreNeutralNotZero() {
        int s = Score.total(0, Double.NaN, 0, 0, Double.NaN, Double.NaN, -1);
        assertTrue("bilinmeyenler çok düşük puan vermemeli: " + s, s >= 75 && s < 100);
    }

    @Test public void blurHurts() {
        int sharp = Score.total(0, 0.12, 0, 0, 115, 50, 0.9);
        int blurry = Score.total(0, 0.45, 0, 0, 115, 50, 0.9);
        assertEquals(25, sharp - blurry);
    }

    @Test public void closedEyesAndTiltHurt() {
        int ok = Score.total(0.3, 0.15, 2, 3, 110, 40, 0.9);
        int bad = Score.total(0.3, 0.15, 18, 25, 110, 40, 0.05);
        assertTrue(ok - bad >= 25);
    }

    @Test public void darkAndOffFramePenalised() {
        int good = Score.total(0.2, 0.15, 0, 0, 110, 40, 0.8);
        int dark = Score.total(0.2, 0.15, 0, 0, 50, 15, 0.8);
        int off = Score.total(2.5, 0.15, 0, 0, 110, 40, 0.8);
        assertTrue(good > dark + 10);
        assertTrue(good > off + 15);
    }

    @Test public void monotonicInFraming() {
        int prev = 101;
        for (double d = 0; d <= 3; d += 0.25) {
            int s = Score.total(d, 0.2, 0, 0, 110, 40, 0.8);
            assertTrue(s <= prev);
            prev = s;
        }
    }

    @Test public void labels() {
        assertEquals("Çok iyi", Score.label(90));
        assertEquals("Zayıf", Score.label(10));
    }
}
