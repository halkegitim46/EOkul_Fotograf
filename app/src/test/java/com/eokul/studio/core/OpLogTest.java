package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class OpLogTest {
    private static File tmp() throws Exception {
        File d = Files.createTempDirectory("oplog").toFile();
        d.deleteOnExit();
        return new File(d, "oplog.jsonl");
    }

    @Test public void roundTripsTypesAndSpecialCharacters() throws Exception {
        OpLog log = new OpLog(tmp(), 1 << 20);
        log.log("error", "where", "save", "msg", "Dosya \"x\"\\y\nsatır2\tğüşiöç", "n", 5, "d", 0.1234567, "ok", false, "z", null);
        List<OpLog.Event> ev = log.readAll();
        assertEquals(1, ev.size());
        OpLog.Event e = ev.get(0);
        assertEquals("error", e.type);
        assertEquals("Dosya \"x\"\\y\nsatır2\tğüşiöç", e.str("msg"));
        assertEquals(5, e.num("n", -1), 0);
        assertEquals(0.123, e.num("d", -1), 1e-9);
        assertFalse(e.bool("ok"));
        assertTrue(e.has("z"));
        assertNull(e.f.get("z"));
    }

    @Test public void nanAndInfinityBecomeNull() throws Exception {
        OpLog log = new OpLog(tmp(), 1 << 20);
        log.log("frame", "a", Double.NaN, "b", Float.POSITIVE_INFINITY, "c", 1.5f);
        OpLog.Event e = log.readAll().get(0);
        assertNull(e.f.get("a"));
        assertNull(e.f.get("b"));
        assertEquals(1.5, e.num("c", 0), 1e-9);
    }

    @Test public void badLinesAreSkipped() {
        assertNull(OpLog.parse(""));
        assertNull(OpLog.parse("{not json"));
        assertNull(OpLog.parse("{\"e\":\"x\"}"));
        assertNull(OpLog.parse(null));
        assertNotNull(OpLog.parse("{\"t\":1,\"e\":\"x\"}"));
    }

    @Test public void trimsWhenTooBigAndKeepsNewest() throws Exception {
        File f = tmp();
        OpLog log = new OpLog(f, 8192);
        for (int i = 0; i < 600; i++) log.log("frame", "i", i, "pad", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        List<OpLog.Event> ev = log.readAll();
        assertTrue("kırpılmalı: " + ev.size(), ev.size() < 600);
        assertTrue(f.length() < 8192 + 400);
        assertEquals(599, (int) ev.get(ev.size() - 1).num("i", -1));
    }

    @Test public void clearRemovesEverything() throws Exception {
        OpLog log = new OpLog(tmp(), 1 << 20);
        log.log("a");
        log.clear();
        assertTrue(log.readAll().isEmpty());
        log.log("b");
        assertEquals(1, log.readAll().size());
    }

    @Test public void concurrentWritesAreAllKept() throws Exception {
        OpLog log = new OpLog(tmp(), 1 << 22);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int id = t;
            Thread th = new Thread(() -> { for (int i = 0; i < 100; i++) log.log("frame", "t", id, "i", i); });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) th.join();
        assertEquals(400, log.readAll().size());
    }
}
