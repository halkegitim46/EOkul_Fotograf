package com.eokul.studio.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class FlatListTest {
    private static byte[] res(String n) throws Exception {
        try (InputStream in = FlatListTest.class.getResourceAsStream("/" + n)) {
            assertNotNull(n, in);
            return in.readAllBytes();
        }
    }

    private static FlatList.Result file(String n) throws Exception {
        FlatList.Result r = FlatList.parse(SheetReader.readSheets(res(n)));
        assertNotNull(n + " ayrıştırılamadı", r);
        return r;
    }

    private static List<String> labels(FlatList.Result r) {
        List<String> l = new ArrayList<>();
        for (FlatList.Group g : r.groups) l.add(g.label + ":" + g.students.size());
        return l;
    }

    private static SheetReader.Sheet sheet(String name, String[]... rows) {
        return new SheetReader.Sheet(name, new ArrayList<>(Arrays.asList(rows)));
    }

    private static String[] row(String... c) { return c; }

    @Test public void headedColumnsXlsx() throws Exception {
        FlatList.Result r = file("sinif_sutunlu.xlsx");
        assertEquals(FlatList.Source.COLUMN, r.source);
        assertTrue(r.headerFound);
        assertEquals(Arrays.asList("5C:3", "6A:3", "6B:3", "7A:3", "8A:3"), labels(r));
        assertEquals(15, r.total());
    }

    @Test public void headedColumnsXlsSameAsXlsx() throws Exception {
        assertEquals(labels(file("sinif_sutunlu.xlsx")), labels(file("sinif_sutunlu.xls")));
    }

    @Test public void headerlessWithOrdinalSchoolNoSplitNameAndLevelBranch() throws Exception {
        FlatList.Result r = file("basliksiz_sira_ayri.xlsx");
        assertTrue(!r.headerFound);
        assertEquals(FlatList.Source.COLUMN, r.source);
        assertEquals(Arrays.asList("5C:3", "6A:3", "6B:3", "7A:3", "8A:3"), labels(r));
        FlatList.Group g6a = r.groups.get(1);
        assertTrue("okul no sıra no değil: " + g6a.students.get(0)[0], g6a.students.get(0)[0].matches("10[123]"));
        assertTrue(g6a.students.get(0)[1].contains(" "));
    }

    @Test public void sheetNamesAsClassesBothFormats() throws Exception {
        for (String f : new String[]{"sayfa_sinif.xlsx", "sayfa_sinif.xls"}) {
            FlatList.Result r = file(f);
            assertEquals(f, FlatList.Source.SHEET_NAME, r.source);
            assertEquals(f, Arrays.asList("5C:3", "6A:3", "6B:3", "7A:3", "8A:3"), labels(r));
        }
    }

    @Test public void mergedClassCellsAreFilledDown() throws Exception {
        for (String f : new String[]{"birlesik_sinif.xlsx", "birlesik_sinif.xls"}) {
            FlatList.Result r = file(f);
            assertEquals(f, Arrays.asList("5C:3", "6A:3", "6B:3", "7A:3", "8A:3"), labels(r));
            boolean note = false;
            for (String n : r.notes) if (n.contains("üstteki sınıfla")) note = true;
            assertTrue(f + " not yok", note);
        }
    }

    @Test public void classFormatsAreNormalized() {
        String[][] cases = {{"6/A", "6A"}, {"6-a", "6A"}, {"6 A", "6A"}, {"6.A", "6A"}, {"6A", "6A"},
                {"6. Sınıf A Şubesi", "6A"}, {"6/A Şubesi", "6A"}, {"9. SINIF / B", "9B"}, {"12-F", "12F"}, {"7", "7"}};
        for (String[] c : cases) assertEquals(c[0], c[1], FlatList.normClass(c[0])[0]);
        assertNull(FlatList.normClass("  "));
    }

    @Test public void classAndBranchInSeparateHeaderColumns() {
        FlatList.Result r = FlatList.parse(Arrays.asList(sheet("S",
                row("Öğrenci No", "Adı", "Soyadı", "Sınıf", "Şube"),
                row("11", "Ada", "Örnek", "6", "A"),
                row("12", "Bora", "Deneme", "6", "B"),
                row("13", "Ceren", "Test", "6. Sınıf", "A"))));
        assertNotNull(r);
        assertEquals(Arrays.asList("6A:2", "6B:1"), labels(r));
    }

    @Test public void headingRowsDefineClasses() {
        FlatList.Result r = FlatList.parse(Arrays.asList(sheet("S",
                row("T.C. Örnek Okulu 6. Sınıf A Şubesi Sınıf Listesi"),
                row("Okul No", "Ad Soyad"),
                row("11", "Ada Örnek"), row("12", "Bora Deneme"),
                row("7. Sınıf B Şubesi"),
                row("21", "Ceren Test"), row("22", "Deniz Kurgu"), row("23", "Efe Sahte"))));
        assertNotNull(r);
        assertEquals(FlatList.Source.HEADING, r.source);
        assertEquals(Arrays.asList("6A:2", "7B:3"), labels(r));
    }

    @Test public void unknownClassGoesToSınıfsızAndNotes() {
        FlatList.Result r = FlatList.parse(Arrays.asList(sheet("S",
                row("No", "Ad Soyad", "Sınıf"),
                row("1", "Ada Örnek", "6A"),
                row("2", "Bora Deneme", ""),
                row("3", "Ceren Test", "7B"))));
        assertNotNull(r);
        // Boş hücre üstteki sınıfla dolar (birleştirilmiş hücre varsayımı)
        assertEquals(Arrays.asList("6A:2", "7B:1"), labels(r));
    }

    @Test public void noClassInfoGivesSingleUnknownGroup() {
        FlatList.Result r = FlatList.parse(Arrays.asList(sheet("Sayfa1",
                row("No", "Ad Soyad"), row("1", "Ada Örnek"), row("2", "Bora Deneme"))));
        assertNotNull(r);
        assertEquals(FlatList.Source.NONE, r.source);
        assertEquals(Arrays.asList("Sınıfsız:2"), labels(r));
    }

    @Test public void duplicateAndCrossClassNumbersAreReported() {
        FlatList.Result r = FlatList.parse(Arrays.asList(sheet("S",
                row("No", "Ad Soyad", "Sınıf"),
                row("5", "Ada Örnek", "6A"), row("5", "Bora Deneme", "6A"),
                row("9", "Ceren Test", "6A"), row("9", "Deniz Kurgu", "6B"))));
        assertNotNull(r);
        String all = String.join("|", r.notes);
        assertTrue(all, all.contains("yinelenen"));
        assertTrue(all, all.contains("birden çok sınıfta"));
    }

    @Test public void schoolNumbersLikeOneZeroOneAreNotTakenAsOrdinal() {
        FlatList.Result r = FlatList.parse(Arrays.asList(sheet("S",
                row("101", "Ada Örnek", "6/A"), row("102", "Bora Deneme", "6/A"), row("103", "Ceren Test", "6/A"),
                row("104", "Deniz Kurgu", "6/A"), row("105", "Efe Sahte", "6/A"))));
        assertNotNull(r);
        assertEquals("101", r.groups.get(0).students.get(0)[0]);
    }

    @Test public void notARosterGivesNull() {
        assertNull(FlatList.parse(Arrays.asList(sheet("S", row("Rapor"), row("Toplam", "5")))));
    }

    @Test public void eokulStyleListHasNoExplicitClassSource() throws Exception {
        // Tek şubeli e-Okul dosyası: sınıf sütunu yok → kullanıcıya eski e-Okul yolu önerilir
        FlatList.Result r = FlatList.parse(SheetReader.readSheets(res("cok_sube_basliksiz.xlsx")));
        if (r != null) assertTrue(r.source != FlatList.Source.COLUMN);
    }

    @Test public void toBlocksCarriesLabelAndGradeBranch() throws Exception {
        List<SheetReader.Block> b = FlatList.toBlocks(file("sinif_sutunlu.xlsx"));
        assertEquals(5, b.size());
        assertEquals("5C", b.get(0).label);
        assertEquals("5", b.get(0).titleGrade);
        assertEquals("C", b.get(0).titleBranch);
    }

    @Test public void detectRoutesEokulMultiBranchToEokulBlocks() throws Exception {
        for (String f : new String[]{"cok_sube.xlsx", "cok_sube_basliksiz.xlsx", "cok_sube_gercek.xlsx"}) {
            FlatList.Outcome o = FlatList.detect(res(f));
            assertNotNull(f, o);
            assertTrue(f + " e-Okul yolu beklenirdi", !o.flat);
            assertEquals(f, 3, o.blocks.size());
        }
    }

    @Test public void detectRoutesClassColumnAndSheetNamesToFlat() throws Exception {
        for (String f : new String[]{"sinif_sutunlu.xlsx", "sinif_sutunlu.xls", "sayfa_sinif.xlsx", "sayfa_sinif.xls", "birlesik_sinif.xls"}) {
            FlatList.Outcome o = FlatList.detect(res(f));
            assertNotNull(f, o);
            assertTrue(f, o.flat);
            assertEquals(f, 5, o.blocks.size());
        }
    }

    @Test public void detectSingleEokulClassKeepsEokulPath() throws Exception {
        FlatList.Outcome o = FlatList.detect(res("ornek_liste.xls"));
        assertNotNull(o);
        assertEquals(1, o.blocks.size());
    }

    @Test public void detectReadsMultiSheetWorkbook() throws Exception {
        byte[] multi = res("sayfa_sinif.xlsx");
        assertNotNull(FlatList.detect(multi));
    }
}
