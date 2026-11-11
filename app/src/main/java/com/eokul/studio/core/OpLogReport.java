package com.eokul.studio.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link OpLog} olaylarından okunabilir tanılama raporu: özet sayılar + otomatik "bulgular" + ham kayıtlar.
 * Olay şeması (alanlar): app_start{ver,dev,sdk,hq,...}, shutter{id,lens,burst}, frame{id,i,ms,bytes,score,face,eye,smile,
 * light,blur,bright,contrast,scoreMs}, capture{id,frames,chosen,early,ms,lens}, review_ready{id,ms,prefetch,w,h,faces},
 * save_start{id,score,hard,soft,auto,reviewMs}, save{id,ok,ms,outBytes,err}, discard{id}, error{where,msg}, op{name,ok,ms,n,err}.
 */
public final class OpLogReport {
    private OpLogReport() {}

    /** İlk karenin "yeterince iyi" sayıldığı puan ({@code BurstPick.GOOD_ENOUGH} ile aynı olmalı). */
    public static final int GOOD_ENOUGH = 86;

    public static String build(List<OpLog.Event> ev, String header, List<String> rawTail) {
        StringBuilder sb = new StringBuilder();
        sb.append("E-OKUL FOTO STUDIO — TANILAMA RAPORU\n");
        sb.append(header == null ? "" : header).append('\n');
        sb.append("\n== ÖZET ==\n");
        List<String> findings = new ArrayList<>();
        summary(ev, sb, findings);
        sb.append("\n== BULGULAR ==\n");
        if (findings.isEmpty()) sb.append("Belirgin bir sorun saptanmadı.\n");
        for (String f : findings) sb.append("• ").append(f).append('\n');
        sb.append("\n== KAYITLAR (son ").append(rawTail == null ? 0 : rawTail.size()).append(") ==\n");
        if (rawTail != null) for (String l : rawTail) sb.append(l).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ özet

    static void summary(List<OpLog.Event> ev, StringBuilder sb, List<String> findings) {
        if (ev.isEmpty()) {
            sb.append("Kayıt yok.\n");
            return;
        }
        long t0 = ev.get(0).t, t1 = ev.get(ev.size() - 1).t;
        SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy HH:mm", Locale.forLanguageTag("tr"));
        sb.append("Dönem: ").append(df.format(new Date(t0))).append(" – ").append(df.format(new Date(t1)))
            .append("  (").append(ev.size()).append(" olay)\n");

        List<OpLog.Event> captures = of(ev, "capture"), frames = of(ev, "frame"), ready = of(ev, "review_ready");
        List<OpLog.Event> starts = of(ev, "save_start"), saves = of(ev, "save"), errors = of(ev, "error"), ops = of(ev, "op");
        int discards = of(ev, "discard").size();

        sb.append("Çekim: ").append(captures.size()).append("   kayıt: ").append(saves.size())
            .append("   vazgeçilen: ").append(discards).append("   hata: ").append(errors.size()).append('\n');

        // --- kare sayısı ve erken çıkış
        int[] fc = new int[4];
        int early = 0, flipped = 0;
        for (OpLog.Event c : captures) {
            int n = (int) c.num("frames", 1);
            fc[Math.max(1, Math.min(3, n))]++;
            if (c.bool("early")) early++;
        }
        if (!captures.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "Kare sayısı: 1 kare %d · 2 kare %d · 3 kare %d   (erken çıkış %%%d)\n",
                fc[1], fc[2], fc[3], pct(early, captures.size())));
        }

        // --- süreler
        List<Double> toFinish = new ArrayList<>(), toReady = new ArrayList<>(), saveMs = new ArrayList<>(), review = new ArrayList<>();
        for (OpLog.Event c : captures) toFinish.add(c.num("ms", Double.NaN));
        int hit = 0, miss = 0, none = 0;
        for (OpLog.Event r : ready) {
            toReady.add(r.num("ms", Double.NaN));
            String p = r.str("prefetch");
            if ("hit".equals(p)) hit++;
            else if ("miss".equals(p)) miss++;
            else none++;
        }
        for (OpLog.Event s : saves) if (s.bool("ok")) saveMs.add(s.num("ms", Double.NaN));
        for (OpLog.Event s : starts) review.add(s.num("reviewMs", Double.NaN));
        stat(sb, "Deklanşör → kare seçildi", toFinish);
        stat(sb, "Kare seçildi → inceleme hazır", toReady);
        stat(sb, "İnceleme ekranında kalma", review);
        stat(sb, "Kaydetme (dosya yazma)", saveMs);
        if (!ready.isEmpty())
            sb.append(String.format(Locale.ROOT, "Ön çözme isabeti: isabet %d · ıska %d · yok %d\n", hit, miss, none));

        // --- puanlar
        List<Double> frameScores = new ArrayList<>(), first = new ArrayList<>(), finals = new ArrayList<>();
        int eyesClosed = 0, smiling = 0, noFace = 0, scored = 0;
        Map<Integer, Integer> light = new TreeMap<>();
        for (OpLog.Event f : frames) {
            double sc = f.num("score", -1);
            if (sc < 0) continue;
            scored++;
            frameScores.add(sc);
            if ((int) f.num("i", 0) == 0) first.add(sc);
            if (!f.bool("face")) noFace++;
            double eye = f.num("eye", -1), smile = f.num("smile", -1);
            if (eye >= 0 && eye < Burst.EYES_CLOSED_BELOW) eyesClosed++;
            if (smile > 0.6) smiling++;
            int lc = (int) f.num("light", 0);
            if (lc != 0) light.merge(lc, 1, Integer::sum);
        }
        for (OpLog.Event s : starts) {
            double sc = s.num("score", -1);
            if (sc >= 0) finals.add(sc);
        }
        if (!frameScores.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "Kare puanı: ort %.0f   ilk kare ort %.0f   (≥%d olan ilk kare %%%d)\n",
                mean(frameScores), mean(first), GOOD_ENOUGH, pct(countAtLeast(first, GOOD_ENOUGH), first.size())));
        }
        if (!finals.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "Nihai puan: ort %.0f   ≥85: %%%d   <70: %%%d\n",
                mean(finals), pct(countAtLeast(finals, 85), finals.size()), pct(countBelow(finals, 70), finals.size())));
        }

        // --- uyarı kodları, otomatik kabul
        Map<String, Integer> hard = new LinkedHashMap<>(), soft = new LinkedHashMap<>();
        int auto = 0;
        for (OpLog.Event s : starts) {
            count(hard, s.str("hard"));
            count(soft, s.str("soft"));
            if (s.bool("auto")) auto++;
        }
        if (!starts.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "Otomatik kabul: %%%d\n", pct(auto, starts.size())));
            if (!hard.isEmpty()) sb.append("Ağır uyarılar: ").append(top(hard)).append('\n');
            if (!soft.isEmpty()) sb.append("Bilgi uyarıları: ").append(top(soft)).append('\n');
        }

        // --- hata dökümü
        if (!errors.isEmpty() || countFailed(saves) > 0 || countFailed(ops) > 0) {
            Map<String, Integer> by = new LinkedHashMap<>();
            for (OpLog.Event e : errors) by.merge(String.valueOf(e.str("where")), 1, Integer::sum);
            sb.append("Hatalar: ").append(by.isEmpty() ? "—" : top(by)).append("   kayıt hatası: ").append(countFailed(saves))
                .append("   işlem hatası: ").append(countFailed(ops)).append('\n');
        }

        // ================================================================== BULGULAR
        if (!first.isEmpty() && first.size() >= 5 && pct(countAtLeast(first, GOOD_ENOUGH), first.size()) < 50) {
            StringBuilder why = new StringBuilder();
            if (pct(eyesClosed, scored) >= 15) why.append(" gözler kapalı %").append(pct(eyesClosed, scored));
            if (pct(smiling, scored) >= 25) why.append(" gülümseme %").append(pct(smiling, scored));
            if (pct(noFace, scored) >= 10) why.append(" yüz bulunamadı %").append(pct(noFace, scored));
            if (!light.isEmpty()) why.append(" ışık sorunu ").append(lightNames(light, scored));
            findings.add("İlk kare çoğunlukla yeterince iyi değil (≥" + GOOD_ENOUGH + " olan %"
                + pct(countAtLeast(first, GOOD_ENOUGH), first.size()) + "), bu yüzden fazla kare çekiliyor." + (why.length() > 0 ? " Olası nedenler:" + why : ""));
        }
        if (!ready.isEmpty() && ready.size() >= 5 && hit + miss > 0 && pct(hit, hit + miss) < 60)
            findings.add("Ön çözme çoğunlukla ıskalıyor (%" + pct(hit, hit + miss) + " isabet): seçilen kare ön çözülenden farklı oluyor "
                + "veya ön çözme zamanında bitmiyor; inceleme ekranı yeniden çözüyor.");
        double mToReady = median(toReady);
        if (!Double.isNaN(mToReady) && mToReady > 1500)
            findings.add(String.format(Locale.ROOT, "Kare seçildikten sonra inceleme ekranı medyan %.1f sn'de hazır oluyor (hedef <1.5 sn): çözme/yüz tespiti yavaş.", mToReady / 1000));
        double mFinish = median(toFinish);
        if (!Double.isNaN(mFinish) && mFinish > 4000)
            findings.add(String.format(Locale.ROOT, "Deklanşörden kare seçimine medyan %.1f sn (hedef <3 sn): çekim veya kare puanlama yavaş; yüksek kaliteli çekimi kapatmayı deneyin.", mFinish / 1000));
        double mSave = median(saveMs);
        if (!Double.isNaN(mSave) && mSave > 1500)
            findings.add(String.format(Locale.ROOT, "Kaydetme medyan %.1f sn: kayıt klasörü yazması yavaş (SD kart / bulut klasörü olabilir).", mSave / 1000));
        if (countFailed(saves) > 0)
            findings.add(countFailed(saves) + " kayıt başarısız oldu; ilgili 'save' kayıtlarındaki hata metnine bakın.");
        if (!errors.isEmpty()) findings.add(errors.size() + " hata kaydı var: " + errorSamples(errors));
        int oom = 0;
        for (OpLog.Event e : errors) if (String.valueOf(e.str("msg")).contains("OutOfMemory")) oom++;
        if (oom > 0) findings.add(oom + " bellek yetersizliği (OutOfMemory) hatası: yüksek kaliteli çekimi kapatın veya diğer uygulamaları kapatın.");
        if (!starts.isEmpty() && starts.size() >= 5) {
            int total = starts.size();
            int backlit = soft.getOrDefault("backlit", 0);
            if (pct(backlit, total) >= 30) findings.add("Arka ışık bilgisi çekimlerin %" + pct(backlit, total) + "'inde çıkıyor: ortam (pencere/ışık yönü) düzeltilebilir.");
            if (!finals.isEmpty() && pct(countBelow(finals, 70), finals.size()) >= 20)
                findings.add("Nihai puanı 70'in altında olan fotoğraf oranı %" + pct(countBelow(finals, 70), finals.size()) + ": en sık ağır uyarı: " + top1(hard) + ".");
            if (pct(auto, total) < 40)
                findings.add("Otomatik kabul oranı düşük (%" + pct(auto, total) + "): çoğu fotoğraf elle onaylanıyor; ağır uyarılar veya düşük puan nedeniyle.");
        }
        if (discards > 0 && !captures.isEmpty() && pct(discards, captures.size()) >= 25)
            findings.add("Çekimlerin %" + pct(discards, captures.size()) + "'i vazgeçildi/yeniden çekildi.");
    }

    // ------------------------------------------------------------------ yardımcılar

    private static List<OpLog.Event> of(List<OpLog.Event> ev, String type) {
        List<OpLog.Event> r = new ArrayList<>();
        for (OpLog.Event e : ev) if (type.equals(e.type)) r.add(e);
        return r;
    }

    private static int pct(int a, int b) {
        return b <= 0 ? 0 : (int) Math.round(100.0 * a / b);
    }

    private static double mean(List<Double> v) {
        double s = 0;
        int n = 0;
        for (double d : v) if (!Double.isNaN(d)) {
            s += d;
            n++;
        }
        return n == 0 ? Double.NaN : s / n;
    }

    private static double median(List<Double> v) {
        List<Double> c = new ArrayList<>();
        for (double d : v) if (!Double.isNaN(d)) c.add(d);
        if (c.isEmpty()) return Double.NaN;
        Collections.sort(c);
        return c.get(c.size() / 2);
    }

    private static double percentile(List<Double> v, double p) {
        List<Double> c = new ArrayList<>();
        for (double d : v) if (!Double.isNaN(d)) c.add(d);
        if (c.isEmpty()) return Double.NaN;
        Collections.sort(c);
        return c.get(Math.min(c.size() - 1, (int) Math.floor(p * (c.size() - 1) + 0.5)));
    }

    private static void stat(StringBuilder sb, String label, List<Double> v) {
        double m = median(v);
        if (Double.isNaN(m)) return;
        sb.append(String.format(Locale.ROOT, "%s: medyan %.1f sn · ort %.1f sn · %%90 %.1f sn (n=%d)\n",
            label, m / 1000, mean(v) / 1000, percentile(v, 0.9) / 1000, count(v)));
    }

    private static int count(List<Double> v) {
        int n = 0;
        for (double d : v) if (!Double.isNaN(d)) n++;
        return n;
    }

    private static int countAtLeast(List<Double> v, double x) {
        int n = 0;
        for (double d : v) if (d >= x) n++;
        return n;
    }

    private static int countBelow(List<Double> v, double x) {
        int n = 0;
        for (double d : v) if (d < x) n++;
        return n;
    }

    private static int countFailed(List<OpLog.Event> v) {
        int n = 0;
        for (OpLog.Event e : v) if (e.has("ok") && !e.bool("ok")) n++;
        return n;
    }

    private static void count(Map<String, Integer> m, String csv) {
        if (csv == null || csv.isEmpty()) return;
        for (String s : csv.split(",")) if (!s.trim().isEmpty()) m.merge(s.trim(), 1, Integer::sum);
    }

    private static String top(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, l.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i).getKey()).append(" ×").append(l.get(i).getValue());
        }
        return sb.toString();
    }

    private static String top1(Map<String, Integer> m) {
        if (m.isEmpty()) return "—";
        String best = null;
        int bv = -1;
        for (Map.Entry<String, Integer> e : m.entrySet()) if (e.getValue() > bv) {
            best = e.getKey();
            bv = e.getValue();
        }
        return best;
    }

    private static String lightNames(Map<Integer, Integer> light, int scored) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : light.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            String n = e.getKey() == Lighting.BACKLIT ? "arka ışık" : e.getKey() == Lighting.UNEVEN ? "tek yandan ışık"
                : e.getKey() == Lighting.GLARE ? "parlama" : "kod" + e.getKey();
            sb.append(n).append(" %").append(pct(e.getValue(), scored));
        }
        return sb.toString();
    }

    private static String errorSamples(List<OpLog.Event> errors) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (OpLog.Event e : errors) {
            String k = e.str("where") + ": " + trunc(String.valueOf(e.str("msg")), 80);
            m.merge(k, 1, Integer::sum);
        }
        return top(m);
    }

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
