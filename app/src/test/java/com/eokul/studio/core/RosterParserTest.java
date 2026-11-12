package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RosterParserTest {
    /** 18 senaryo / 34 kontrol: ayraç algılama, başlık, e-Okul alt toplam satırı vb. */
    @Test
    public void allRosterChecksPass() {
        assertEquals("Başarısız liste ayrıştırma kontrolü sayısı", 0, RosterChecks.run());
    }
}
