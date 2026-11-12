package com.eokul.studio.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cihazda tutulan işlem kaydı (tanılama): her satır tek bir olay, düz JSON ({"t":...,"e":"tür",...}).
 * Ağ yok, yalnızca yerel dosya. Boyut sınırı aşılınca en eski yarısı atılır. Yazma arka plan iş parçacığındadır
 * (çekim akışını yavaşlatmaz); [flush] bekletir. Kişisel veri (ad) yazılmaz; çağıran numarayı kısaltıp kullanır.
 */
public final class OpLog {
    /** Tek olay: zaman damgası (ms, epoch), tür ve düz alanlar (String / Long / Double / Boolean / null). */
    public static final class Event {
        public final long t;
        public final String type;
        public final Map<String, Object> f;

        public Event(long t, String type, Map<String, Object> f) {
            this.t = t;
            this.type = type;
            this.f = f;
        }

        public String str(String k) {
            Object v = f.get(k);
            return v == null ? null : String.valueOf(v);
        }

        public double num(String k, double def) {
            Object v = f.get(k);
            return v instanceof Number ? ((Number) v).doubleValue() : def;
        }

        public boolean bool(String k) {
            return Boolean.TRUE.equals(f.get(k));
        }

        public boolean has(String k) {
            return f.containsKey(k);
        }
    }

    private final File file;
    private final long maxBytes;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "oplog");
        th.setDaemon(true);
        return th;
    });

    public OpLog(File file, long maxBytes) {
        this.file = file;
        this.maxBytes = Math.max(4096, maxBytes);
    }

    /** Olay ekler; alanlar anahtar/değer çiftleri halinde: log("frame", "i", 1, "score", 88). */
    public void log(String type, Object... kv) {
        final long t = System.currentTimeMillis();
        final String line = format(t, type, kv);
        io.execute(() -> append(line));
    }

    /** Bekleyen yazmaların bitmesini bekler (rapor almadan önce / testlerde). */
    public void flush() {
        try {
            io.submit(() -> { }).get();
        } catch (Exception ignored) {
            // yoksay
        }
    }

    public void clear() {
        flush();
        synchronized (this) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Dosyadaki tüm olaylar (bozuk satırlar atlanır). */
    public List<Event> readAll() {
        flush();
        List<Event> out = new ArrayList<>();
        synchronized (this) {
            if (!file.exists()) return out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = r.readLine()) != null) {
                    Event e = parse(ln);
                    if (e != null) out.add(e);
                }
            } catch (IOException ignored) {
                // okunabilen kadarı
            }
        }
        return out;
    }

    /** Ham satırlar (rapor eki için), en fazla son [max] satır. */
    public List<String> readLines(int max) {
        flush();
        List<String> all = new ArrayList<>();
        synchronized (this) {
            if (!file.exists()) return all;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = r.readLine()) != null) if (!ln.isEmpty()) all.add(ln);
            } catch (IOException ignored) {
                // okunabilen kadarı
            }
        }
        return all.size() <= max ? all : new ArrayList<>(all.subList(all.size() - max, all.size()));
    }

    public synchronized long sizeBytes() {
        return file.exists() ? file.length() : 0L;
    }

    private synchronized void append(String line) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            if (file.exists() && file.length() > maxBytes) trim();
            try (FileOutputStream o = new FileOutputStream(file, true)) {
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // kayıt hatası uygulamayı etkilememeli
        }
    }

    /** Son yarıyı tutar (satır sınırından). */
    private void trim() throws IOException {
        List<String> all = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = r.readLine()) != null) all.add(ln);
        }
        List<String> keep = all.subList(all.size() / 2, all.size());
        try (FileOutputStream o = new FileOutputStream(file, false)) {
            for (String s : keep) o.write((s + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------ biçim / ayrıştırma

    public static String format(long t, String type, Object... kv) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"t\":").append(t).append(",\"e\":");
        quote(sb, type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(',');
            quote(sb, String.valueOf(kv[i]));
            sb.append(':');
            value(sb, kv[i + 1]);
        }
        return sb.append('}').toString();
    }

    private static void value(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean) sb.append(v);
        else if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(Math.rint(d * 1000.0) / 1000.0);
        } else if (v instanceof Number) sb.append(((Number) v).longValue());
        else quote(sb, String.valueOf(v));
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Tek satırlık düz JSON nesnesini ayrıştırır; biçim bozuksa null. */
    public static Event parse(String line) {
        if (line == null) return null;
        int[] p = {0};
        String s = line.trim();
        if (s.length() < 2 || s.charAt(0) != '{') return null;
        p[0] = 1;
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            while (true) {
                skipWs(s, p);
                if (p[0] >= s.length()) return null;
                if (s.charAt(p[0]) == '}') break;
                if (s.charAt(p[0]) == ',') {
                    p[0]++;
                    continue;
                }
                String k = readString(s, p);
                skipWs(s, p);
                if (s.charAt(p[0]++) != ':') return null;
                skipWs(s, p);
                m.put(k, readValue(s, p));
            }
        } catch (RuntimeException e) {
            return null;
        }
        Object t = m.remove("t");
        Object e = m.remove("e");
        if (!(t instanceof Number) || e == null) return null;
        return new Event(((Number) t).longValue(), String.valueOf(e), m);
    }

    private static void skipWs(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    private static String readString(String s, int[] p) {
        if (s.charAt(p[0]) != '"') throw new IllegalStateException("string bekleniyordu");
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(p[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char n = s.charAt(p[0]++);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                        p[0] += 4;
                        break;
                    default: sb.append(n);
                }
            } else sb.append(c);
        }
    }

    private static Object readValue(String s, int[] p) {
        char c = s.charAt(p[0]);
        if (c == '"') return readString(s, p);
        int st = p[0];
        while (p[0] < s.length() && ",}".indexOf(s.charAt(p[0])) < 0) p[0]++;
        String tok = s.substring(st, p[0]).trim();
        if (tok.equals("null")) return null;
        if (tok.equals("true")) return Boolean.TRUE;
        if (tok.equals("false")) return Boolean.FALSE;
        if (tok.indexOf('.') >= 0 || tok.indexOf('e') >= 0 || tok.indexOf('E') >= 0) return Double.parseDouble(tok);
        return Long.parseLong(tok);
    }
}
