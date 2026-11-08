package com.eokul.studio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tek bir listeden (tüm kademeler bir arada) öğrencileri ve sınıfları ayırır.
 *
 * Tanınan biçimler:
 *  - Başlıklı: "No | Ad Soyad | Sınıf" (sütun adları serbest: Okul No, Öğrenci No, Adı, Soyadı, Sınıf, Şube, Sınıf/Şube…)
 *  - Başlıksız: sütunlar içeriğinden bulunur (numara, ad, "6/A" gibi sınıf; sıra no varsa elenir)
 *  - Sınıf ve şube ayrı sütunlarda ("6" + "A") veya tek sütunda ("6A", "6/A", "6-A", "6. Sınıf A Şubesi")
 *  - Birleştirilmiş (boş bırakılmış) sınıf hücreleri: üstteki sınıfla doldurulur
 *  - Sınıf başlık satırları: "6. Sınıf A Şubesi" satırı, altındaki öğrenciler o sınıfa
 *  - Her sınıfın ayrı sayfada ("6A", "6B" sekmeleri) olduğu çalışma kitapları
 */
public final class FlatList {
    private FlatList() {}

    public enum Source { COLUMN, HEADING, SHEET_NAME, NONE }

    public static final class Group {
        /** Normalleştirilmiş sınıf adı ("6A"); sınıf bilgisi olmayanlar için "Sınıfsız". */
        public final String label;
        public String grade;
        public String branch;
        /** Satırlar: {numara, ad soyad}. */
        public final List<String[]> students = new ArrayList<>();

        Group(String label) {
            this.label = label;
        }
    }

    public static final class Result {
        public final List<Group> groups = new ArrayList<>();
        public Source source = Source.NONE;
        public boolean headerFound;
        public final List<String> notes = new ArrayList<>();

        public int total() {
            int n = 0;
            for (Group g : groups) n += g.students.size();
            return n;
        }
    }

    public static final String UNKNOWN = "Sınıfsız";

    private static final Locale TR = Locale.forLanguageTag("tr-TR");

    // ───────────────────────── sınıf adı ─────────────────────────

    /** {etiket, düzey, şube}; düzey/şube ayrışamazsa null. Boş girdi için null. */
    static String[] normClass(String raw) {
        if (raw == null) return null;
        String up = raw.trim().toUpperCase(TR);
        if (up.isEmpty()) return null;
        up = up.replaceAll("(?<![A-ZÇĞİÖŞÜ0-9])(SINIFI|SINIF|ŞUBESİ|ŞUBESI|ŞUBE|SUBESI|SUBE)(?![A-ZÇĞİÖŞÜ0-9])", " ");
        up = up.replaceAll("[./\\\\\\-_,:;()]", " ").replaceAll("\\s+", " ").trim();
        if (up.isEmpty()) return null;
        Matcher m = Pattern.compile("^(\\d{1,2}) ?([A-ZÇĞİÖŞÜ]{1,2})$").matcher(up);
        if (m.find()) return new String[]{m.group(1) + m.group(2), m.group(1), m.group(2)};
        m = Pattern.compile("^(\\d{1,2})$").matcher(up);
        if (m.find()) return new String[]{m.group(1), m.group(1), ""};
        return new String[]{up, null, null};
    }

    /** Hücre kesin olarak sınıf gibi mi görünüyor ("6A", "6/A", "6. Sınıf A Şubesi")? Serbest metin sayılmaz. */
    static boolean looksLikeClass(String raw) {
        String[] n = normClass(raw);
        return n != null && n[1] != null && !n[2].isEmpty();
    }

    private static final Pattern HEADING_WORDS = Pattern.compile(
            "(?i)(\\d{1,2})\\s*[.]?\\s*s[ıi]n[ıi]f.*|.*\\d{1,2}\\s*[./\\-\\\\]?\\s*[A-ZÇĞİÖŞÜa-zçğıöşü]\\s*(s[ıi]n[ıi]f[ıi]?|[şs]ubesi?)?");

    // ───────────────────────── başlık sözlüğü ─────────────────────────

    private static String compact(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(TR).replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
                .replace('ş', 's').replace('ö', 'o').replace('ç', 'c').replace("i̇", "i");
        return t.replaceAll("[^a-z0-9]", "");
    }

    private enum Role { NO, ORD, NAME, AD, SOY, CLASS, LEVEL, BRANCH }

    private static Role roleOf(String cell) {
        String c = compact(cell);
        if (c.isEmpty()) return null;
        switch (c) {
            case "no": case "numara": case "ogrencino": case "ogrencinumarasi": case "okulno": case "okulnumarasi":
            case "ogrno": case "ogrenciid": case "ogrnumarasi": case "numarasi": case "ogrencinumara":
                return Role.NO;
            case "sno": case "sirano": case "sira": case "sirasi": case "sn": case "sirn": case "s":
                return Role.ORD;
            case "adsoyad": case "adisoyadi": case "adisoyad": case "adsoyadi": case "ogrenciadi": case "ogrenciadisoyadi":
            case "ogrenciadsoyad": case "adsoyadiogrenci": case "isimsoyisim": case "isimsoyad": case "adiniz":
            case "ogrenci": case "ogrencibilgisi": case "adisoyadiogrenci":
                return Role.NAME;
            case "ad": case "adi": case "isim": case "ogrenciad":
                return Role.AD;
            case "soyad": case "soyadi": case "soyisim":
                return Role.SOY;
            case "sinif": case "sinifi": case "sinifsube": case "sinifisubesi": case "sinifsubesi": case "sinifvesube":
            case "sinifadi": case "sinifkodu": case "ogrencisinifi": case "subesi sinif":
                return Role.CLASS;
            case "seviye": case "duzey": case "kademe": case "sinifduzeyi": case "sinifseviyesi":
                return Role.LEVEL;
            case "sube": case "subesi":
                return Role.BRANCH;
            default:
                return null;
        }
    }

    private static final class Cols {
        int no = -1, ord = -1, name = -1, ad = -1, soy = -1, cls = -1, level = -1, branch = -1;

        boolean usable() {
            return no >= 0 && (name >= 0 || ad >= 0);
        }

        boolean hasClass() {
            return cls >= 0 || (level >= 0 && branch >= 0);
        }
    }

    /** Satır başlık satırı mı? (en az iki farklı rol) */
    private static Cols headerCols(String[] r) {
        Cols c = new Cols();
        int roles = 0;
        for (int i = 0; i < r.length; i++) {
            Role ro = roleOf(r[i]);
            if (ro == null) continue;
            switch (ro) {
                case NO: if (c.no < 0) { c.no = i; roles++; } break;
                case ORD: if (c.ord < 0) { c.ord = i; roles++; } break;
                case NAME: if (c.name < 0) { c.name = i; roles++; } break;
                case AD: if (c.ad < 0) { c.ad = i; roles++; } break;
                case SOY: if (c.soy < 0) { c.soy = i; roles++; } break;
                case CLASS: if (c.cls < 0) { c.cls = i; roles++; } break;
                case LEVEL: if (c.level < 0) { c.level = i; roles++; } break;
                case BRANCH: if (c.branch < 0) { c.branch = i; roles++; } break;
            }
        }
        // "Sınıf" sütunu varken ayrıca "Şube" sütunu: ikisi birlikte okunur (düzey + şube)
        if (c.cls >= 0 && c.branch >= 0 && c.level < 0) {
            c.level = c.cls;
        }
        boolean nameish = c.name >= 0 || c.ad >= 0;
        // En az iki anlamlı rol ve numara+ad ikilisi ya da ad+sınıf ikilisi
        if (roles >= 2 && ((c.no >= 0 && nameish) || (nameish && c.hasClass()) || (c.no >= 0 && c.hasClass()))) return c;
        return null;
    }

    // ───────────────────────── başlıksız: sütunları içerikten bul ─────────────────────────

    private static boolean isInt(String s) {
        return s != null && s.trim().matches("\\d{1,9}");
    }

    private static boolean isNameLike(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 3) return false;
        int letters = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) letters++;
        return letters >= 3 && !looksLikeClass(t);
    }

    private static Cols inferCols(List<String[]> rows) {
        int sample = 0, width = 0;
        List<String[]> use = new ArrayList<>();
        for (String[] r : rows) {
            int ne = 0;
            for (String c : r) if (c != null && !c.trim().isEmpty()) ne++;
            if (ne >= 2) {
                use.add(r);
                width = Math.max(width, r.length);
                if (++sample >= 400) break;
            }
        }
        if (use.size() < 2 || width == 0) return null;
        int n = use.size();
        int[] nonEmpty = new int[width], ints = new int[width], cls = new int[width], nameLike = new int[width];
        int[] letter1 = new int[width], small = new int[width], words1 = new int[width];
        List<java.util.Set<String>> distinct = new ArrayList<>();
        for (int c = 0; c < width; c++) distinct.add(new java.util.HashSet<>());
        for (String[] r : use) {
            for (int c = 0; c < width && c < r.length; c++) {
                String v = r[c] == null ? "" : r[c].trim();
                if (v.isEmpty()) continue;
                nonEmpty[c]++;
                distinct.get(c).add(v);
                if (isInt(v)) {
                    ints[c]++;
                    if (v.length() <= 2 && Integer.parseInt(v) >= 1 && Integer.parseInt(v) <= 12) small[c]++;
                }
                if (looksLikeClass(v)) cls[c]++;
                if (isNameLike(v)) {
                    nameLike[c]++;
                    if (v.split("\\s+").length == 1) words1[c]++;
                }
                if (v.matches("(?i)[A-ZÇĞİÖŞÜ]{1,2}")) letter1[c]++;
            }
        }
        Cols k = new Cols();
        // sınıf sütunu
        for (int c = 0; c < width; c++) {
            if (nonEmpty[c] >= 2 && cls[c] >= 0.7 * nonEmpty[c] && cls[c] >= 2) { k.cls = c; break; }
        }
        // düzey + şube ayrı sütunlar (sınıf sütunu bulunamadıysa)
        if (k.cls < 0) {
            int lv = -1, br = -1;
            for (int c = 0; c < width; c++) {
                if (nonEmpty[c] == 0) continue;
                if (lv < 0 && !isOrdinal(use, c) && small[c] >= 0.9 * nonEmpty[c] && distinct.get(c).size() <= 12 && distinct.get(c).size() * 2 <= nonEmpty[c]) lv = c;
                else if (br < 0 && letter1[c] >= 0.9 * nonEmpty[c] && distinct.get(c).size() <= 12) br = c;
            }
            if (lv >= 0 && br >= 0) { k.level = lv; k.branch = br; }
        }
        // numara sütunları
        java.util.List<Integer> numCols = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            if (c == k.level) continue;
            if (nonEmpty[c] >= 2 && ints[c] >= 0.9 * nonEmpty[c]) numCols.add(c);
        }
        for (int c : numCols) {
            // sıra no: değerler 1..k artan (veya yeniden başlayan) bir dizi
            if (isOrdinal(use, c) && numCols.size() > 1) { if (k.ord < 0) k.ord = c; }
            else if (k.no < 0) k.no = c;
        }
        if (k.no < 0 && !numCols.isEmpty()) {
            // tek sayısal sütun ve o da sıra gibi görünüyorsa numara olarak kabul edilir (başka sütun yok)
            k.no = numCols.get(0);
            if (k.ord == k.no) k.ord = -1;
        }
        // ad sütunları
        java.util.List<Integer> textCols = new ArrayList<>();
        for (int c = 0; c < width; c++) {
            if (c == k.cls || c == k.level || c == k.branch || c == k.no || c == k.ord) continue;
            if (nonEmpty[c] >= 2 && nameLike[c] >= 0.7 * nonEmpty[c]) textCols.add(c);
        }
        if (textCols.size() >= 2 && textCols.get(1) == textCols.get(0) + 1
                && words1[textCols.get(0)] >= 0.7 * nonEmpty[textCols.get(0)]
                && words1[textCols.get(1)] >= 0.7 * nonEmpty[textCols.get(1)]) {
            k.ad = textCols.get(0);
            k.soy = textCols.get(1);
        } else if (!textCols.isEmpty()) {
            k.name = textCols.get(0);
        }
        return k.usable() ? k : null;
    }

    private static boolean isOrdinal(List<String[]> rows, int col) {
        int prev = 0, ok = 0, total = 0;
        for (String[] r : rows) {
            if (col >= r.length || !isInt(r[col])) continue;
            int v = Integer.parseInt(r[col].trim());
            if (total == 0 && v > 1) return false;   // sıra no 1'den başlar; 101, 102… okul numarasıdır
            total++;
            if (v == prev + 1 || v == 1) ok++;
            prev = v;
        }
        return total >= 2 && ok >= 0.9 * total;
    }

    // ───────────────────────── ayrıştırma ─────────────────────────

    private static String cell(String[] r, int i) {
        return i >= 0 && i < r.length && r[i] != null ? r[i].trim() : "";
    }

    private static boolean isFooterText(String joined) {
        String c = compact(joined);
        return c.contains("ogrencisayisi") || c.startsWith("toplam") || c.startsWith("genel toplam");
    }

    private static final Pattern H1 = Pattern.compile("(?<!\\d)(\\d{1,2}) sinifi? ([a-z]{1,2}) subesi?");
    private static final Pattern H2 = Pattern.compile("(?<!\\d)(\\d{1,2}) ([a-z]{1,2}) sinifi?");

    /** "T.C. … 6. Sınıf A Şubesi Sınıf Listesi" gibi uzun başlıklardan "6A" çıkarır; yoksa null. */
    static String headingOf(String text) {
        String t = compact2(text);
        Matcher m = H1.matcher(t);
        if (m.find()) return m.group(1) + m.group(2).toUpperCase(Locale.ROOT);
        m = H2.matcher(t);
        if (m.find()) return m.group(1) + m.group(2).toUpperCase(Locale.ROOT);
        return null;
    }

    /** Küçük harf, Türkçe harfler sadeleştirilmiş, harf/rakam dışı boşluk. */
    private static String compact2(String s) {
        String t = s.trim().toLowerCase(TR).replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
                .replace('ş', 's').replace('ö', 'o').replace('ç', 'c').replace("i\u0307", "i");
        return t.replaceAll("[^a-z0-9]", " ").replaceAll("\\s+", " ").trim();
    }

    private static final class Entry {
        String no, name, cls;
        Source src;
    }

    /** Yoksa null (öğrenci numarası + ad bulunamadı). */
    public static Result parse(List<SheetReader.Sheet> sheets) {
        if (sheets == null || sheets.isEmpty()) return null;
        Result res = new Result();
        List<Entry> all = new ArrayList<>();
        int filled = 0, skipped = 0, tables = 0;
                for (SheetReader.Sheet sh : sheets) {
            List<String[]> rows = sh.rows;
            // Başlık satırı
            int hdr = -1;
            Cols cols = null;
            for (int i = 0; i < Math.min(rows.size(), 40); i++) {
                Cols hc = headerCols(rows.get(i));
                if (hc != null && hc.usable()) { hdr = i; cols = hc; break; }
            }
            boolean headed = cols != null;
            if (cols == null) cols = inferCols(rows);
            if (cols == null) continue;
            tables++;
            if (headed) res.headerFound = true;

            String heading = null, lastCls = "";
            String sheetCls = null;
            if (looksLikeClass(sh.name)) sheetCls = normClass(sh.name)[0];
            // Başlık satırından önceki başlık/üst bilgi satırları ("6. Sınıf A Şubesi Sınıf Listesi")
            if (headed) {
                for (int i = 0; i < hdr; i++) {
                    StringBuilder pre = new StringBuilder();
                    for (String c : rows.get(i)) if (c != null && !c.trim().isEmpty()) pre.append(c.trim()).append(' ');
                    String hw = headingOf(pre.toString());
                    if (hw != null) heading = hw;
                }
            }
            for (int i = (headed ? hdr + 1 : 0); i < rows.size(); i++) {
                String[] r = rows.get(i);
                // yinelenen başlık satırı (her sayfa/bölümde tekrarlanabilir)
                Cols again = headerCols(r);
                if (again != null && again.usable()) { cols = again; continue; }
                int ne = 0;
                StringBuilder joined = new StringBuilder();
                for (String c : r) if (c != null && !c.trim().isEmpty()) { ne++; joined.append(c.trim()).append(' '); }
                if (ne == 0) continue;
                String noS = cell(r, cols.no);
                String name = cols.name >= 0 ? cell(r, cols.name) : (cell(r, cols.ad) + " " + cell(r, cols.soy));
                name = name.trim().replaceAll("\\s+", " ");
                boolean dataRow = isInt(noS) && name.length() >= 2 && !isFooterText(joined.toString());
                if (!dataRow) {
                    // sınıf başlık satırı: tek hücre, sınıf gibi
                    if (!isFooterText(joined.toString())) {
                        String t = joined.toString().trim();
                        String hw = headingOf(t);
                        if (hw != null) { heading = hw; continue; }
                        if (ne == 1) {
                            String[] hn = normClass(t);
                            if (hn != null && hn[1] != null && (looksLikeClass(t) || HEADING_WORDS.matcher(t).matches())) {
                                heading = hn[0];
                                continue;
                            }
                        }
                    }
                    if (ne >= 2 && isInt(noS) && name.length() < 2) skipped++;
                    continue;
                }
                String raw = "";
                if (cols.level >= 0 && cols.branch >= 0 && cols.level != cols.branch) {
                    String lv = cell(r, cols.level), br = cell(r, cols.branch);
                    String[] ln = normClass(lv);
                    if (ln != null && ln[1] != null && ln[2].isEmpty() && !br.isEmpty()) raw = ln[1] + br;   // "6" + "A", "6. Sınıf" + "A"
                    else raw = lv.isEmpty() ? br : lv;
                } else if (cols.cls >= 0) {
                    raw = cell(r, cols.cls);
                }
                Entry e = new Entry();
                e.no = noS;
                e.name = name;
                boolean hasClassCol = cols.hasClass();
                if (!raw.isEmpty()) {
                    e.cls = raw;
                    e.src = Source.COLUMN;
                    lastCls = raw;
                } else if (hasClassCol && !lastCls.isEmpty()) {
                    e.cls = lastCls;       // birleştirilmiş hücre: üstteki sınıf
                    e.src = Source.COLUMN;
                    filled++;
                } else if (heading != null) {
                    e.cls = heading;
                    e.src = Source.HEADING;
                } else if (sheetCls != null) {
                    e.cls = sheetCls;
                    e.src = Source.SHEET_NAME;
                } else {
                    e.cls = "";
                    e.src = Source.NONE;
                }
                all.add(e);
            }
        }
        if (tables == 0 || all.isEmpty()) return null;

        // Gruplama (ilk görülme sırası; sonra doğal sıralama)
        Map<String, Group> byLabel = new LinkedHashMap<>();
        boolean anyCol = false, anyHead = false, anySheet = false;
        int noClass = 0;
        for (Entry e : all) {
            String[] n = normClass(e.cls);
            String label = n == null ? UNKNOWN : n[0];
            if (n == null) noClass++;
            Group g = byLabel.get(label);
            if (g == null) {
                g = new Group(label);
                if (n != null && n[1] != null) {
                    g.grade = n[1];
                    g.branch = n[2];
                }
                byLabel.put(label, g);
            }
            g.students.add(new String[]{e.no, e.name});
            if (e.src == Source.COLUMN) anyCol = true;
            else if (e.src == Source.HEADING) anyHead = true;
            else if (e.src == Source.SHEET_NAME) anySheet = true;
        }
        res.groups.addAll(byLabel.values());
        Collections.sort(res.groups, new Comparator<Group>() {
            @Override public int compare(Group a, Group b) {
                int ra = rank(a), rb = rank(b);
                if (ra != rb) return Integer.compare(ra, rb);
                if (ra == 0) {
                    int ga = Integer.parseInt(a.grade), gb = Integer.parseInt(b.grade);
                    if (ga != gb) return Integer.compare(ga, gb);
                    return a.branch.compareTo(b.branch);
                }
                return a.label.compareTo(b.label);
            }
            private int rank(Group g) {
                if (g.label.equals(UNKNOWN)) return 2;
                return g.grade != null ? 0 : 1;
            }
        });
        res.source = anyCol ? Source.COLUMN : anyHead ? Source.HEADING : anySheet ? Source.SHEET_NAME : Source.NONE;

        // Bilgi notları
        if (filled > 0) res.notes.add(filled + " satırda sınıf hücresi boştu (birleştirilmiş olabilir); üstteki sınıfla dolduruldu.");
        if (noClass > 0 && res.groups.size() > 0 && !(res.groups.size() == 1 && res.groups.get(0).label.equals(UNKNOWN)))
            res.notes.add(noClass + " öğrencinin sınıfı belli değil; “" + UNKNOWN + "” grubuna konuldu.");
        if (skipped > 0) res.notes.add(skipped + " satır ad eksik olduğu için atlandı.");
        // Aynı sınıfta yinelenen numara
        for (Group g : res.groups) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            int dup = 0;
            for (String[] s : g.students) if (!seen.add(s[0])) dup++;
            if (dup > 0) res.notes.add(g.label + ": " + dup + " yinelenen öğrenci numarası var.");
        }
        // Aynı numara birden çok sınıfta
        Map<String, String> firstSeen = new LinkedHashMap<>();
        java.util.Set<String> cross = new java.util.TreeSet<>();
        for (Group g : res.groups) {
            for (String[] s : g.students) {
                String prev = firstSeen.get(s[0]);
                if (prev != null && !prev.equals(g.label)) cross.add(s[0]);
                else if (prev == null) firstSeen.put(s[0], g.label);
            }
        }
        if (!cross.isEmpty()) {
            List<String> l = new ArrayList<>(cross);
            res.notes.add("Aynı numara birden çok sınıfta geçiyor: " + String.join(", ", l.subList(0, Math.min(5, l.size())))
                    + (l.size() > 5 ? " …" : "") + ".");
        }
        return res;
    }

    /** Arayüzün kullandığı bloklara çevirir (düzey+şube ayrışanlar başlık gibi davranır). */
    public static List<SheetReader.Block> toBlocks(Result r) {
        List<SheetReader.Block> out = new ArrayList<>();
        for (Group g : r.groups) {
            SheetReader.Block b = new SheetReader.Block();
            b.students.addAll(g.students);
            b.label = g.label;
            if (g.grade != null) {
                b.titleGrade = g.grade;
                b.titleBranch = g.branch == null || g.branch.isEmpty() ? null : g.branch;
            }
            out.add(b);
        }
        return out;
    }

    /** Biçimin kullanıcıya gösterilecek kısa açıklaması. */
    public static String describe(Result r) {
        String how;
        switch (r.source) {
            case COLUMN: how = "Sınıf sütunlu liste"; break;
            case HEADING: how = "Sınıf başlıklı liste"; break;
            case SHEET_NAME: how = "Sayfa adı = sınıf"; break;
            default: how = "Sınıf bilgisi yok"; break;
        }
        return how + (r.headerFound ? " · başlık satırı bulundu" : " · başlıksız (sütunlar içerikten tanındı)");
    }

    /** Dosyanın nasıl okunduğunu ve sonucu taşır. */
    public static final class Outcome {
        public final List<SheetReader.Block> blocks;
        /** Kullanıcıya gösterilecek kısa biçim açıklaması. */
        public final String info;
        public final List<String> notes;
        /** true: düz liste (sınıf sütunu / başlık / sayfa adı); false: e-Okul şube blokları. */
        public final boolean flat;

        Outcome(List<SheetReader.Block> blocks, String info, List<String> notes, boolean flat) {
            this.blocks = blocks;
            this.info = info;
            this.notes = notes;
            this.flat = flat;
        }
    }

    /**
     * Excel dosyasını çözer. Sıra: (1) açık sınıf sütunu veya sayfa adları → düz liste; (2) aksi halde e-Okul şube blokları
     * ile başlık satırlarıyla ayrılmış düz liste karşılaştırılır, daha çok sınıf bulan seçilir (eşitlikte e-Okul: alt toplamlarla doğrulanır).
     * Hiçbiri öğrenci bulamazsa null.
     */
    public static Outcome detect(byte[] data) throws java.io.IOException {
        Result flat = null;
        try {
            flat = parse(SheetReader.readSheets(data));
        } catch (java.io.IOException e) {
            throw e;
        } catch (RuntimeException e) {
            flat = null;   // beklenmedik biçim: e-Okul yoluna düş
        }
        if (flat != null && (flat.source == Source.COLUMN || flat.source == Source.SHEET_NAME)) {
            return new Outcome(toBlocks(flat), describe(flat), flat.notes, true);
        }
        List<SheetReader.Block> eokul = SheetReader.readEokulBlocks(data);
        if (eokul != null && (flat == null || flat.groups.size() <= eokul.size())) {
            return new Outcome(eokul, "e-Okul şube listesi", new ArrayList<String>(), false);
        }
        if (flat != null) return new Outcome(toBlocks(flat), describe(flat), flat.notes, true);
        return null;
    }
}
