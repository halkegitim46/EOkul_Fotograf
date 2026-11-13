package com.eokul.studio.core;

import com.eokul.studio.core.RosterParser.CaseMode;
import com.eokul.studio.core.RosterParser.Delimiter;
import com.eokul.studio.core.RosterParser.Result;
import com.eokul.studio.core.RosterParser.Row;

import java.util.List;

class RosterChecks {
    static int fails = 0, total = 0;

    static void check(String label, Object got, Object want) {
        total++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            fails++;
            System.out.println("  HATA  " + label + "\n     beklenen: " + want + "\n     gelen   : " + got);
        }
    }

    static String dump(Result r) {
        StringBuilder sb = new StringBuilder();
        for (Row x : r.rows) sb.append(x.ok() ? x.no + "=" + x.name : "!" + x.error).append(" | ");
        return sb.toString();
    }

    static Result p(String text) {
        return RosterParser.parse(text, Delimiter.AUTO, false, CaseMode.KEEP);
    }

    static int run() {
        fails = 0; total = 0;
        // 1) Sekmeli (Excel kopyası)
        Result r = p("101\tAyşe Yılmaz\n102\tMehmet Kaya\n103\tZeynep Çelik");
        check("sekme alg.", r.detected, Delimiter.TAB);
        check("sekme", dump(r), "101=Ayşe Yılmaz | 102=Mehmet Kaya | 103=Zeynep Çelik | ");

        // 2) Ad ve soyad ayrı sütun + noktalı virgül
        r = p("101;Ayşe;Yılmaz\n102;Mehmet Ali;Kaya");
        check("; alg.", r.detected, Delimiter.SEMICOLON);
        check(";", dump(r), "101=Ayşe Yılmaz | 102=Mehmet Ali Kaya | ");

        // 3) Tire ayraçlı, kendi çıktı biçimimiz ve tireli isim
        r = p("12345-Ayşe Yılmaz\n12346 - Ali-Can Demir\n12347 – Fatma Şahin");
        check("tire alg.", r.detected, Delimiter.DASH);
        check("tire", dump(r), "12345=Ayşe Yılmaz | 12346=Ali-Can Demir | 12347=Fatma Şahin | ");

        // 4) Boşluk ayraçlı, büyük harf, numara başta
        r = p("12345 AYŞE YILMAZ\n12346 MEHMET ALİ KAYA");
        check("boşluk alg.", r.detected, Delimiter.SPACE);
        check("boşluk", dump(r), "12345=AYŞE YILMAZ | 12346=MEHMET ALİ KAYA | ");

        // 5) Numara sonda + virgül, soyad önce
        r = RosterParser.parse("Yılmaz, Ayşe, 101\nKaya, Mehmet Ali, 102", Delimiter.AUTO, true, CaseMode.KEEP);
        check("virgül alg.", r.detected, Delimiter.COMMA);
        check("virgül soyad-önce", dump(r), "101=Ayşe Yılmaz | 102=Mehmet Ali Kaya | ");   // ilk sözcük soyad: sona alınır

        // 6) e-Okul sınıf listesi sütunları: Sıra, No, Adı, Soyadı, Cinsiyet (+ başlık satırı)
        r = p("Sıra No\tÖğrenci No\tAdı\tSoyadı\tCinsiyeti\n1\t1204\tAYŞE\tYILMAZ\tK\n2\t1311\tMEHMET ALİ\tKAYA\tE\n12\t98765\tÖMER\tÇELİK\tE");
        check("e-okul başlık atlandı", r.skippedHeaders, 1);
        check("e-okul", dump(r), "1204=AYŞE YILMAZ | 1311=MEHMET ALİ KAYA | 98765=ÖMER ÇELİK | ");

        // 7) Başlık yok, sıra no + okul no + ad (boşluklu)
        r = p("1 1204 Ayşe Yılmaz\n2 1311 Mehmet Kaya");
        check("sıra+no", dump(r), "1204=Ayşe Yılmaz | 1311=Mehmet Kaya | ");

        // 8) Türkçe başlık harfleri: BÜYÜK → Başlık, İ/ı
        r = RosterParser.parse("1\tIŞIL İPEK\n2\tİSMAİL ÇAĞLAR", Delimiter.AUTO, false, CaseMode.TITLE);
        check("başlık harfi", dump(r), "1=Işıl İpek | 2=İsmail Çağlar | ");
        r = RosterParser.parse("1\tışıl ipek", Delimiter.AUTO, false, CaseMode.UPPER);
        check("büyük harf", dump(r), "1=IŞIL İPEK | ");
        r = RosterParser.parse("1\tali-can o'neil", Delimiter.AUTO, false, CaseMode.TITLE);
        check("tire/kesme", dump(r), "1=Ali-Can O'Neil | ");

        // 9) Hatalı satırlar ve mükerrer numara
        r = p("101\tAyşe Yılmaz\nAhmet Kaya\n102\n101\tBaşka Kişi\n99999999999999\tUzun No");
        check("hatalar", dump(r), "101=Ayşe Yılmaz | !Numara bulunamadı | !Ad soyad bulunamadı | !Mükerrer numara (satır 1) | !Numara çok uzun | ");
        check("hata sayısı", r.errorCount(), 4);
        check("geçerli sayısı", r.valid().size(), 1);

        // 10) Tırnaklı CSV, NBSP, BOM, Windows satır sonu, boş satırlar
        r = p("﻿\"101\";\"Ayşe Yılmaz\"\r\n\r\n\"102\";\"Mehmet Kaya\"\r\n");
        check("tırnak/nbsp/bom", dump(r), "101=Ayşe Yılmaz | 102=Mehmet Kaya | ");

        // 11) Tek satır, başlık yok; yalnızca rakam / yalnızca isim
        r = p("101 Ayşe Yılmaz");
        check("tek satır", dump(r), "101=Ayşe Yılmaz | ");
        r = p("");
        check("boş", r.rows.size(), 0);

        // 12) Sınıf sütunu ("9/A", "9-A") ad olarak alınmamalı; cinsiyet sözcüğü atılmalı
        r = p("101\tAyşe\tYılmaz\t9/A\tKız\n102\tMehmet\tKaya\t9/A\tErkek");
        check("sınıf+cinsiyet", dump(r), "101=Ayşe Yılmaz | 102=Mehmet Kaya | ");

        // 13) Tek sözcüklü ad + cinsiyet harfi benzeri soyad
        r = p("101\tAyşe\tK");
        check("ad + K soyad korunur", dump(r), "101=Ayşe K | ");

        // 14) Açık ayraç seçimi: sekme zorla
        r = RosterParser.parse("101\tAyşe Yılmaz;x", Delimiter.TAB, false, CaseMode.KEEP);
        check("zorla TAB", r.detected, Delimiter.TAB);

        // 15) Dosya adı kuralları
        check("stem", RosterParser.studentStem("12345", "Ayşe Yılmaz"), "12345-Ayşe Yılmaz");
        check("stem geçersiz karakter", RosterParser.studentStem("1", "A/B: C*? \"D\"<>|"), "1-AB C D");
        check("stem uzun", RosterParser.sanitizeFileName("x".repeat(300)).length(), 120);
        check("stem nokta", RosterParser.sanitizeFileName("Ad Soyad. "), "Ad Soyad");
        check("stem boş", RosterParser.sanitizeFileName("///"), "_");

        // 16) Sayısal sıralama
        List<String> nos = new java.util.ArrayList<>(java.util.Arrays.asList("100", "20", "3", "0003", "1000"));
        nos.sort(RosterParser::compareNo);
        check("sıralama", nos, "[3, 0003, 20, 100, 1000]");

        // 17) Büyük liste (1500 satır) hızlı ve doğru
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 1500; i++) sb.append(10000 + i).append('\t').append("Öğrenci").append(' ').append("Soyad").append(i % 7).append('\n');
        long t0 = System.nanoTime();
        r = p(sb.toString());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("1500 satır geçerli", r.valid().size(), 1500);
        check("1500 satır süre < 500ms", ms < 500, true);
        System.out.println("  1500 satır ayrıştırma: " + ms + " ms");

        // 18) e-Okul alt toplam satırı öğrenci sayılmamalı
        r = p("S.No\tÖğrenci No\tAdı\tSoyadı\tCinsiyeti\n1\t111\tHATİCE\tDENEME\tKız\nKız Öğrenci Sayısı :\tErkek Öğrenci Sayısı :\tToplam Öğrenci Sayısı :\t29\n");
        check("alt toplam", dump(r), "111=HATİCE DENEME | ");

        System.out.println(fails == 0 ? "ROSTER_OK (" + total + " kontrol)" : "BAŞARISIZ: " + fails + "/" + total);
        return fails;
    }
}
