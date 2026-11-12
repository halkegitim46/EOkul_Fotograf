package com.eokul.studio.core;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class OpLogReportTest {
    private static OpLog.Event ev(String type, Object... kv) {
        return OpLog.parse(OpLog.format(1_700_000_000_000L + (long) (Math.random() * 1000), type, kv));
    }

    private static String report(List<OpLog.Event> e) {
        return OpLogReport.build(e, "Cihaz: test", new ArrayList<>());
    }

    @Test public void emptyLogDoesNotCrash() {
        assertTrue(report(new ArrayList<>()).contains("Kayıt yok"));
    }

    @Test public void healthyRunHasNoFindings() {
        List<OpLog.Event> e = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = "s" + i;
            e.add(ev("shutter", "id", id, "burst", 3));
            e.add(ev("frame", "id", id, "i", 0, "score", 92, "face", true, "eye", 0.9, "smile", 0.1, "light", 0));
            e.add(ev("capture", "id", id, "frames", 1, "chosen", 0, "early", true, "ms", 1500));
            e.add(ev("review_ready", "id", id, "ms", 200, "prefetch", "hit"));
            e.add(ev("save_start", "id", id, "score", 92, "hard", "", "soft", "", "auto", true, "reviewMs", 800));
            e.add(ev("save", "id", id, "ok", true, "ms", 600));
        }
        String r = report(e);
        assertTrue(r, r.contains("Belirgin bir sorun saptanmadı"));
        assertTrue(r, r.contains("erken çıkış %100"));
        assertTrue(r, r.contains("Ön çözme isabeti: isabet 10"));
    }

    @Test public void flagsSlowAndFailingRun() {
        List<OpLog.Event> e = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String id = "s" + i;
            e.add(ev("frame", "id", id, "i", 0, "score", 60, "face", true, "eye", 0.1, "smile", 0.9, "light", Lighting.BACKLIT));
            e.add(ev("capture", "id", id, "frames", 3, "chosen", 1, "early", false, "ms", 6500));
            e.add(ev("review_ready", "id", id, "ms", 2600, "prefetch", "miss"));
            e.add(ev("save_start", "id", id, "score", 62, "hard", "blurry", "soft", "backlit", "auto", false, "reviewMs", 5000));
            e.add(ev("save", "id", id, "ok", i % 5 != 0, "ms", 2500, "err", "boom"));
        }
        e.add(ev("error", "where", "decode", "msg", "java.lang.OutOfMemoryError: x"));
        String r = report(e);
        assertTrue(r, r.contains("İlk kare çoğunlukla yeterince iyi değil"));
        assertTrue(r, r.contains("gözler kapalı"));
        assertTrue(r, r.contains("arka ışık"));
        assertTrue(r, r.contains("Ön çözme çoğunlukla ıskalıyor"));
        assertTrue(r, r.contains("inceleme ekranı medyan"));
        assertTrue(r, r.contains("Deklanşörden kare seçimine medyan"));
        assertTrue(r, r.contains("Kaydetme medyan"));
        assertTrue(r, r.contains("kayıt başarısız"));
        assertTrue(r, r.contains("OutOfMemory"));
        assertTrue(r, r.contains("Otomatik kabul oranı düşük"));
    }

    @Test public void survivesMissingFields() {
        List<OpLog.Event> e = new ArrayList<>();
        e.add(ev("capture"));
        e.add(ev("frame"));
        e.add(ev("review_ready"));
        e.add(ev("save_start"));
        e.add(ev("save"));
        e.add(ev("error"));
        e.add(ev("op"));
        e.add(ev("unknown_type", "x", 1));
        assertTrue(report(e).contains("ÖZET"));
    }

    @Test public void flagsOriginalsNotSaved() {
        List<OpLog.Event> e = new ArrayList<>();
        e.add(ev("orig", "ok", false, "err", "java.io.IOException: boom"));
        e.add(ev("orig", "ok", false, "err", "skipped:keepOriginalsOff"));
        e.add(ev("orig", "ok", false, "err", "skipped:fromSaved"));
        String r = report(e);
        assertTrue(r, r.contains("SAKLANAMADI"));
        assertTrue(r, r.contains("KAPALI"));
    }
}
