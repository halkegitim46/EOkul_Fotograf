package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import org.junit.Test;

public class SheetReaderTest {
    @Test
    public void xlsFileGivesStudentsAndIgnoresFooter() throws Exception {
        byte[] data;
        try (InputStream in = getClass().getResourceAsStream("/ornek_liste.xls")) {
            data = in.readAllBytes();
        }
        assertTrue(SheetReader.looksLikeExcel(data));
        String text = SheetReader.readText(data);
        RosterParser.Result r = RosterParser.parse(text, RosterParser.Delimiter.AUTO, false, RosterParser.CaseMode.TITLE);
        assertEquals(8, r.valid().size());
    }

    @Test
    public void htmlTableXlsIsParsed() {
        String h = "<html><body><table><tr><th>S.No</th><th>Ad Soyad</th></tr>"
                + "<tr><td>12345</td><td>Ay&#351;e&nbsp;Y&#305;lmaz</td></tr>"
                + "<tr><td>12346</td><td>Mehmet Kaya</td></tr></table></body></html>";
        assertTrue(SheetReader.looksLikeHtml(h));
        RosterParser.Result r = RosterParser.parse(SheetReader.htmlToText(h), RosterParser.Delimiter.AUTO, false, RosterParser.CaseMode.KEEP);
        assertEquals(2, r.valid().size());
        assertEquals("Ayşe Yılmaz", r.valid().get(0).name);
    }

    @Test
    public void workbookStreamNameIsCaseInsensitive() throws Exception {
        byte[] data;
        try (InputStream in = getClass().getResourceAsStream("/kucuk_harf.xls")) {
            data = in.readAllBytes();
        }
        RosterParser.Result r = RosterParser.parse(SheetReader.readText(data), RosterParser.Delimiter.AUTO, false, RosterParser.CaseMode.TITLE);
        assertEquals(8, r.valid().size());
    }

    @Test
    public void ole2FileWithoutWorkbookGivesClearError() {
        byte[] data = new byte[1024];
        data[0] = (byte) 0xD0;
        data[1] = (byte) 0xCF;
        try {
            SheetReader.readText(data);
            assertTrue("Hata beklenirdi", false);
        } catch (java.io.IOException | RuntimeException e) {
            assertTrue(e.getMessage() != null || e instanceof RuntimeException);
        }
    }

    @Test
    public void xlsxUsesFirstSheetInTabOrderNotFileName() throws Exception {
        byte[] data;
        try (InputStream in = getClass().getResourceAsStream("/cok_sayfali.xlsx")) {
            data = in.readAllBytes();
        }
        String text = SheetReader.readText(data);
        assertTrue("Sekme sırasındaki ilk sayfa okunmalı", !text.contains("DECOY"));
        RosterParser.Result r = RosterParser.parse(text, RosterParser.Delimiter.AUTO, false, RosterParser.CaseMode.TITLE);
        assertEquals(8, r.valid().size());
    }

    @Test
    public void eokulListSkipsHeaderAndFooter() throws Exception {
        byte[] data;
        try (InputStream in = getClass().getResourceAsStream("/ornek_liste.xls")) {
            data = in.readAllBytes();
        }
        String t = SheetReader.readEokulList(data);
        assertTrue(t != null);
        String[] lines = t.trim().split("\\n");
        assertEquals(8, lines.length);
        assertTrue("Başlık olmamalı", !t.contains("S.No") && !t.toLowerCase().contains("soyad"));
        assertTrue("Alt toplam olmamalı", !t.contains("Toplam") && !t.contains("Sayısı"));
        assertTrue(lines[0].matches("\\d+\\t.+"));
    }

    private static byte[] res(String n) throws Exception {
        try (InputStream in = SheetReaderTest.class.getResourceAsStream(n)) {
            return in.readAllBytes();
        }
    }

    @Test
    public void multiBranchFileSplitsByFooterAndRestart() throws Exception {
        java.util.List<SheetReader.Block> b = SheetReader.readEokulBlocks(res("/cok_sube.xlsx"));
        assertEquals(3, b.size());
        assertEquals(5, b.get(0).students.size());
        assertEquals("101", b.get(0).students.get(0)[0]);
        assertEquals(4, b.get(1).students.size());
        assertEquals(null, b.get(0).mismatch());
        assertTrue(b.get(2).mismatch() != null);   // alt satır 4 kız diyor, listede 3
    }

    @Test
    public void multiBranchSplitsOnSerialRestartWithoutFooter() throws Exception {
        java.util.List<SheetReader.Block> b = SheetReader.readEokulBlocks(res("/sadece_sira.xlsx"));
        assertEquals(2, b.size());
        assertEquals(3, b.get(0).students.size());
        assertEquals(2, b.get(1).students.size());
    }

    @Test
    public void singleClassFileIsOneBlock() throws Exception {
        java.util.List<SheetReader.Block> b = SheetReader.readEokulBlocks(res("/ornek_liste.xls"));
        assertEquals(1, b.size());
    }

    @Test
    public void realEokulMultiBranchSample() throws Exception {
        java.util.List<SheetReader.Block> b = SheetReader.readEokulBlocks(res("/cok_sube_gercek.xlsx"));
        assertEquals(3, b.size());
        assertEquals(4, b.get(0).students.size());
        assertEquals("2001", b.get(0).students.get(0)[0]);
        assertEquals("ÖRNEK1 ÖĞRENCİ", b.get(0).students.get(0)[1]);
        assertEquals("6", b.get(0).titleGrade);
        assertEquals("A", b.get(0).titleBranch);
        assertEquals(3, b.get(0).girlsInFooter);
        assertEquals(1, b.get(0).boysInFooter);
        assertEquals(4, b.get(0).totalInFooter);
        assertTrue(b.get(0).verified());
        assertEquals(8, b.get(1).students.size());
        assertEquals(8, b.get(1).totalInFooter);
        assertTrue(b.get(1).verified());
        assertEquals(5, b.get(2).students.size());
    }

    @Test
    public void userOriginalWithoutClassTitleSplitsAndVerifies() throws Exception {
        java.util.List<SheetReader.Block> b = SheetReader.readEokulBlocks(res("/cok_sube_basliksiz.xlsx"));
        assertEquals(3, b.size());
        assertEquals(4, b.get(0).students.size());
        assertEquals(8, b.get(1).students.size());
        assertEquals(5, b.get(2).students.size());
        for (SheetReader.Block x : b) {
            assertEquals(null, x.titleGrade);
            assertTrue(x.verified());
        }
    }
}
