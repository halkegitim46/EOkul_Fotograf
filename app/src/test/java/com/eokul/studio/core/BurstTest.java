package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BurstTest {
    @Test public void cleanFrameKeepsScore() {
        assertEquals(80, Burst.total(80, true, 0.1, 0.9, Lighting.OK));
    }

    @Test public void unknownValuesAreNotPenalised() {
        assertEquals(80, Burst.total(80, true, -1, -1, Lighting.OK));
    }

    @Test public void closedEyesLoseToOpenEyes() {
        assertTrue(Burst.total(80, true, 0.1, 0.05, Lighting.OK) < Burst.total(70, true, 0.1, 0.9, Lighting.OK));
    }

    @Test public void smilePenaltyScalesAndCaps() {
        assertEquals(80, Burst.total(80, true, 0.30, 0.9, Lighting.OK));
        int mid = Burst.total(80, true, 0.6, 0.9, Lighting.OK);
        assertTrue(mid < 80 && mid > 66);
        assertEquals(66, Burst.total(80, true, 1.0, 0.9, Lighting.OK));
    }

    @Test public void noFaceAndBadLight() {
        assertEquals(45, Burst.total(80, false, -1, -1, Lighting.OK));
        assertEquals(70, Burst.total(80, true, 0.0, 0.9, Lighting.BACKLIT));
        assertEquals(74, Burst.total(80, true, 0.0, 0.9, Lighting.UNEVEN));
    }

    @Test public void clampedToRange() {
        assertEquals(0, Burst.total(10, false, 1.0, 0.0, Lighting.GLARE));
        assertEquals(100, Burst.total(120, true, 0, 1, Lighting.OK));
    }
}
