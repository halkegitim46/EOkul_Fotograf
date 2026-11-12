package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class FramingTest {
    private static final double[] FACE = {450, 500, 750, 860};

    private double[] defaultBox(double headTop) {
        Crop.Params p = new Crop.Params();
        p.topGap = 0.03;
        p.bottomGap = 0.35;
        p.hairMin = 0.01;
        return Crop.defaultCropBox(1200, 1600, FACE, headTop, p);
    }

    @Test
    public void defaultCropPassesFramingChecks() {
        double[] box = defaultBox(440);
        List<String> issues = Framing.check(FACE, 440, null, box);
        assertEquals("Varsayılan kırpma kadraj kurallarını geçmeli: " + issues, 0, issues.size());
    }

    @Test
    public void detectsCutHeadAndChin() {
        double[] box = defaultBox(440);
        double[] shiftedDown = {box[0], box[1] + 120, box[2], box[3] + 120};
        assertTrue(Framing.check(FACE, 440, null, shiftedDown).contains(Framing.HEAD_CUT));
        double[] shiftedUp = {box[0], box[1] - 400, box[2], box[3] - 400};
        assertTrue(Framing.check(FACE, 440, null, shiftedUp).contains(Framing.CHIN_CUT));
    }

    @Test
    public void detectsOffCenterAndSize() {
        double[] box = defaultBox(440);
        double[] right = {box[0] + 150, box[1], box[2] + 150, box[3]};
        assertTrue(Framing.check(FACE, 440, null, right).contains(Framing.OFF_CENTER));
        double[] huge = {0, 0, 1200 * 1.0, 1200 / 133.0 * 171.0};
        assertTrue(Framing.check(new double[]{500, 500, 560, 580}, 490, null, huge).contains(Framing.FACE_SMALL));
    }

    @Test
    public void eyeChecks() {
        double[] box = defaultBox(440);
        double eyeY = box[1] + 0.30 * (box[3] - box[1]);
        assertEquals(0, Framing.check(FACE, 440, new double[]{520, eyeY, 680, eyeY}, box).size());
        assertTrue(Framing.check(FACE, 440, new double[]{520, eyeY - 30, 680, eyeY + 30}, box).contains(Framing.EYE_TILT));
        assertTrue(Framing.check(FACE, 440, new double[]{520, box[1] + 5, 680, box[1] + 5}, box).contains(Framing.EYE_LEVEL));
    }
}
