package com.eokul.studio.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yapıştırılan sınıf listesini (Excel / e-Okul / metin) öğrenci numarası + ad soyad satırlarına çevirir.
 *
 * Desteklenen girişler: "12345<TAB>Ayşe Yılmaz", "12345;Ayşe;Yılmaz", "12345 - Ayşe Yılmaz",
 * "12345 AYŞE YILMAZ", "Yılmaz, Ayşe, 12345", e-Okul sınıf listesi sütunları
 * ("1 <TAB> 12345 <TAB> AYŞE <TAB> YILMAZ <TAB> K").
 */
public final class RosterParser {
    private RosterParser() {}

    public enum Delimiter { AUTO, TAB, SEMICOLON, COMMA, PIPE, DASH, SPACE }

    public enum CaseMode { KEEP, TITLE, UPPER }

    public static final class Row {
        public final int line;
        public final String no;
        public final String name;
        public final String error;   // null ise satır geçerli

        Row(int line, String no, String name, String error) {
            this.line = line;
            this.no = no;
            this.name = name;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }
    }

    public static final class Result {
        public final List<Row> rows;
        public final Delimiter detected;
        public final int skippedHeaders;

        Result(List<Row> rows, Delimiter detected, int skippedHeaders) {
            this.rows = rows;
            this.detected = detected;
            this.skippedHeaders = skippedHeaders;
        }

        public List<Row> valid() {
            List<Row> out = new ArrayList<>();
            for (Row r : rows) if (r.ok()) out.add(r);
            return out;
        }

        public int errorCount() {
            int n = 0;
            for (Row r : rows) if (!r.ok()) n++;
            return n;
        }
    }

    private static final Locale TR = Locale.forLanguageTag("tr-TR");
    private static final Pattern DASH_NO_FIRST = Pattern.compile("^\\s*(\\d+)\\s*[-–—:]\\s*(\\S.*)$");
    private static final Pattern DASH_NO_LAST = Pattern.compile("^(.*\\S)\\s*[-–—]\\s*(\\d+)\\s*$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Boşluk türlerini (NBSP vb.) normal boşluğa çevirir, sıfır genişlikli karakterleri siler, fazlalıkları temizler. */
    public static String normalizeSpaces(String s) {
        if (s == null) return "";
        String t = s.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
                .replace("​", "").replace("﻿", "");
        return t.replaceAll("\\s+", " ").trim();
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        while (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    public static String applyCase(String name, CaseMode mode) {
        String n = normalizeSpaces(name);
        switch (mode) {
            case UPPER:
                return n.toUpperCase(TR);
            case TITLE: {
                StringBuilder sb = new StringBuilder();
                boolean startWord = true;
                String lower = n.toLowerCase(TR);
                for (int i = 0; i < lower.length(); ) {
                    int cp = lower.codePointAt(i);
                    int len = Character.charCount(cp);
                    String ch = new String(Character.toChars(cp));
                    if (startWord && Character.isLetter(cp)) {
                        sb.append(ch.toUpperCase(TR));
                        startWord = false;
                    } else {
                        sb.append(ch);
                        if (ch.equals(" ") || ch.equals("-") || ch.equals("'") || ch.equals("’")) startWord = true;
                        else if (Character.isLetter(cp)) startWord = false;
                    }
                    i += len;
                }
                return sb.toString();
            }
            default:
                return n;
        }
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }

    private static boolean isGenderToken(String t) {
        String u = t.toUpperCase(TR);
        return u.equals("K") || u.equals("E") || u.equals("KIZ") || u.equals("KIZ.") || u.equals("ERKEK")
                || u.equals("KADIN") || u.equals("KIZI");
    }

    private static int count(List<String> lines, char c) {
        int n = 0;
        for (String l : lines) if (l.indexOf(c) >= 0) n++;
        return n;
    }

    private static Delimiter detect(List<String> lines) {
        int need = Math.max(1, (int) Math.ceil(lines.size() * 0.6));
        if (count(lines, '\t') >= need) return Delimiter.TAB;
        if (count(lines, ';') >= need) return Delimiter.SEMICOLON;
        if (count(lines, '|') >= need) return Delimiter.PIPE;
        if (count(lines, ',') >= need) return Delimiter.COMMA;
        int dash = 0;
        for (String l : lines) {
            if (DASH_NO_FIRST.matcher(l).matches() || DASH_NO_LAST.matcher(l).matches()) dash++;
        }
        if (dash >= need) return Delimiter.DASH;
        return Delimiter.SPACE;
    }

    private static List<String> split(String line, Delimiter d) {
        String[] parts;
        switch (d) {
            case TAB: parts = line.split("\t"); break;
            case SEMICOLON: parts = line.split(";"); break;
            case PIPE: parts = line.split("\\|"); break;
            case COMMA: parts = line.split(","); break;
            default: parts = line.split("\\s+"); break;
        }
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = normalizeSpaces(stripQuotes(p));
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Ad için kullanılacak sözcükler: rakam içermeyen, harf içeren, cinsiyet işareti olmayan parçalar. */
    private static List<String> nameWords(List<String> tokens) {
        List<String> words = new ArrayList<>();
        for (String t : tokens) {
            for (String w : t.split(" ")) {
                if (!w.isEmpty() && hasLetter(w) && !hasDigit(w)) words.add(w);
            }
        }
        List<String> noGender = new ArrayList<>();
        for (String w : words) if (!isGenderToken(w)) noGender.add(w);
        return noGender.size() >= 2 ? noGender : words;
    }

    /** e-Okul listesi alt satırı: "Kız Öğrenci Sayısı : … Toplam Öğrenci Sayısı : 29". */
    private static boolean isSummaryLine(String l) {
        String u = l.toLowerCase(TR);
        return u.contains("sayısı") || u.contains("sayisi") || u.trim().startsWith("toplam");
    }

    public static Result parse(String text, Delimiter requested, boolean surnameFirst, CaseMode caseMode) {
        List<String> lines = new ArrayList<>();
        List<Integer> lineNos = new ArrayList<>();
        String[] raw = (text == null ? "" : text).split("\\r?\\n|\\r");
        for (int i = 0; i < raw.length; i++) {
            String l = raw[i].replace(' ', ' ').replace("﻿", "");
            if (!l.trim().isEmpty() && !isSummaryLine(l)) {
                lines.add(l);
                lineNos.add(i + 1);
            }
        }

        int skipped = 0;
        // Başlık satırı: ilk satırda hiç rakam yoksa atlanır (Öğrenci No / Adı Soyadı ...)
        if (!lines.isEmpty() && !hasDigit(lines.get(0)) && lines.size() > 1) {
            lines.remove(0);
            lineNos.remove(0);
            skipped = 1;
        }

        Delimiter d = requested == Delimiter.AUTO ? detect(lines) : requested;
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> firstLine = new HashMap<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int ln = lineNos.get(i);
            String no = null;
            List<String> words;

            if (d == Delimiter.DASH) {
                Matcher a = DASH_NO_FIRST.matcher(line);
                Matcher b = DASH_NO_LAST.matcher(line);
                if (a.matches()) {
                    no = a.group(1);
                    words = nameWords(split(a.group(2), Delimiter.SPACE));
                } else if (b.matches()) {
                    no = b.group(2);
                    words = nameWords(split(b.group(1), Delimiter.SPACE));
                } else {
                    words = new ArrayList<>();
                }
            } else {
                List<String> tokens = split(line, d);
                int idx = -1, best = 0;
                for (int k = 0; k < tokens.size(); k++) {
                    String t = tokens.get(k);
                    if (DIGITS.matcher(t).matches() && t.length() > best) {
                        best = t.length();
                        idx = k;
                    }
                }
                if (idx >= 0) {
                    no = tokens.get(idx);
                    tokens.remove(idx);
                }
                words = nameWords(tokens);
            }

            String error = null;
            String name = "";
            if (no == null) {
                error = "Numara bulunamadı";
            } else if (no.length() > 11) {
                error = "Numara çok uzun";
            } else if (words.isEmpty()) {
                error = "Ad soyad bulunamadı";
            } else {
                if (surnameFirst && words.size() >= 2) {
                    List<String> rot = new ArrayList<>(words.subList(1, words.size()));
                    rot.add(words.get(0));
                    words = rot;
                }
                name = applyCase(String.join(" ", words), caseMode);
                if (name.length() < 2) error = "Ad soyad çok kısa";
            }

            if (error == null) {
                Integer prev = firstLine.get(no);
                if (prev != null) error = "Mükerrer numara (satır " + prev + ")";
                else firstLine.put(no, ln);
            }
            rows.add(new Row(ln, no == null ? "" : no, name, error));
        }
        return new Result(rows, d, skipped);
    }

    // ------------------------------------------------------------------ dosya adları

    /** Dosya adında geçersiz karakterleri (\ / : * ? " < > | ve kontrol karakterleri) temizler. */
    public static String sanitizeFileName(String s) {
        String t = normalizeSpaces(s).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "");
        t = t.replaceAll("[. ]+$", "").trim();
        if (t.length() > 120) t = t.substring(0, 120).trim();
        return t.isEmpty() ? "_" : t;
    }

    /** "12345-Ayşe Yılmaz" (uzantısız). */
    public static String studentStem(String no, String name) {
        return sanitizeFileName(no + "-" + name);
    }

    /** İki numarayı sayısal sıraya göre karşılaştırır (uzunluk, sonra metin). */
    public static int compareNo(String a, String b) {
        String x = a.replaceFirst("^0+(?=.)", ""), y = b.replaceFirst("^0+(?=.)", "");
        if (x.length() != y.length()) return Integer.compare(x.length(), y.length());
        return x.compareTo(y);
    }
}
