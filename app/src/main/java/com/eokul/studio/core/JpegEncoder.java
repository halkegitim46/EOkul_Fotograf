package com.eokul.studio.core;

import java.io.ByteArrayOutputStream;

/**
 * Baseline JPEG kodlayıcı — 4:4:4 (renk kanalları tam çözünürlükte), standart Huffman tabloları.
 *
 * Android'in Bitmap.compress() fonksiyonu yerine kullanılır, çünkü o 4:2:0 alt örnekleme yapabilir,
 * DPI/ICC alanlarını yazmaz ve minimum dosya boyutu doldurmasını desteklemez.
 *
 * Çıktı: JFIF APP0 (dpi), isteğe bağlı sRGB ICC profili (APP2), gerekirse COM doldurma segmentleri.
 */
public final class JpegEncoder {
    private JpegEncoder() {}

    private static final int[] DC_LUM_BITS = {0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
    private static final int[] DC_LUM_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] AC_LUM_BITS = {0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125};
    private static final int[] AC_LUM_VALS = {1, 2, 3, 0, 4, 17, 5, 18, 33, 49, 65, 6, 19, 81, 97, 7, 34, 113, 20, 50, 129, 145, 161, 8, 35, 66, 177, 193, 21, 82, 209, 240, 36, 51, 98, 114, 130, 9, 10, 22, 23, 24, 25, 26, 37, 38, 39, 40, 41, 42, 52, 53, 54, 55, 56, 57, 58, 67, 68, 69, 70, 71, 72, 73, 74, 83, 84, 85, 86, 87, 88, 89, 90, 99, 100, 101, 102, 103, 104, 105, 106, 115, 116, 117, 118, 119, 120, 121, 122, 131, 132, 133, 134, 135, 136, 137, 138, 146, 147, 148, 149, 150, 151, 152, 153, 154, 162, 163, 164, 165, 166, 167, 168, 169, 170, 178, 179, 180, 181, 182, 183, 184, 185, 186, 194, 195, 196, 197, 198, 199, 200, 201, 202, 210, 211, 212, 213, 214, 215, 216, 217, 218, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250};
    private static final int[] DC_CHR_BITS = {0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
    private static final int[] DC_CHR_VALS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final int[] AC_CHR_BITS = {0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 119};
    private static final int[] AC_CHR_VALS = {0, 1, 2, 3, 17, 4, 5, 33, 49, 6, 18, 65, 81, 7, 97, 113, 19, 34, 50, 129, 8, 20, 66, 145, 161, 177, 193, 9, 35, 51, 82, 240, 21, 98, 114, 209, 10, 22, 36, 52, 225, 37, 241, 23, 24, 25, 26, 38, 39, 40, 41, 42, 53, 54, 55, 56, 57, 58, 67, 68, 69, 70, 71, 72, 73, 74, 83, 84, 85, 86, 87, 88, 89, 90, 99, 100, 101, 102, 103, 104, 105, 106, 115, 116, 117, 118, 119, 120, 121, 122, 130, 131, 132, 133, 134, 135, 136, 137, 138, 146, 147, 148, 149, 150, 151, 152, 153, 154, 162, 163, 164, 165, 166, 167, 168, 169, 170, 178, 179, 180, 181, 182, 183, 184, 185, 186, 194, 195, 196, 197, 198, 199, 200, 201, 202, 210, 211, 212, 213, 214, 215, 216, 217, 218, 226, 227, 228, 229, 230, 231, 232, 233, 234, 242, 243, 244, 245, 246, 247, 248, 249, 250};

    private static final int[] ZIGZAG = {
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    private static final int[] BASE_LUM = {
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};

    private static final int[] BASE_CHR = {
        17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
        24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};

    private static final double[][] COS = new double[8][8];
    static {
        for (int x = 0; x < 8; x++) {
            for (int u = 0; u < 8; u++) {
                double cu = (u == 0) ? Math.sqrt(0.5) : 1.0;
                COS[x][u] = 0.5 * cu * Math.cos((2 * x + 1) * u * Math.PI / 16.0);
            }
        }
    }

    /** Huffman kod/uzunluk tablosu: indeks = sembol. */
    private static final class Huff {
        final int[] code = new int[256];
        final int[] len = new int[256];
        Huff(int[] bits, int[] vals) {
            int c = 0, k = 0;
            for (int l = 1; l <= 16; l++) {
                for (int i = 0; i < bits[l - 1]; i++) {
                    code[vals[k]] = c;
                    len[vals[k]] = l;
                    k++;
                    c++;
                }
                c <<= 1;
            }
        }
    }

    private static final class BitWriter {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        int acc = 0, n = 0;
        void put(int value, int bits) {
            if (bits == 0) return;
            acc = (acc << bits) | (value & ((1 << bits) - 1));
            n += bits;
            while (n >= 8) {
                int b = (acc >> (n - 8)) & 0xFF;
                out.write(b);
                if (b == 0xFF) out.write(0);
                n -= 8;
            }
            acc &= (1 << n) - 1;
        }
        void flush() {
            if (n > 0) put((1 << (8 - n)) - 1, 8 - n);
        }
    }

    private static int[] scaledTable(int[] base, int quality) {
        int q = Math.max(1, Math.min(100, quality));
        int scale = (q < 50) ? 5000 / q : 200 - q * 2;
        int[] t = new int[64];
        for (int i = 0; i < 64; i++) {
            int v = (base[i] * scale + 50) / 100;
            t[i] = Math.max(1, Math.min(255, v));
        }
        return t;  // doğal (satır) sırada
    }

    private static int category(int v) {
        int a = Math.abs(v), c = 0;
        while (a != 0) { c++; a >>= 1; }
        return c;
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void segment(ByteArrayOutputStream o, int marker, byte[] payload) {
        o.write(0xFF);
        o.write(marker);
        u16(o, payload.length + 2);
        o.write(payload, 0, payload.length);
    }

    /**
     * @param argb     piksel dizisi (0xAARRGGBB), satır sıralı
     * @param quality  1..100
     * @param dpi      JFIF yoğunluğu (0 = yazma)
     * @param icc      gömülecek ICC profili veya null
     * @param minBytes çıktı bundan küçükse COM segmentleriyle tamamlanır (0 = kapalı)
     */
    public static byte[] encode(int[] argb, int w, int h, int quality, int dpi, byte[] icc, int minBytes) {
        int[] qLum = scaledTable(BASE_LUM, quality);
        int[] qChr = scaledTable(BASE_CHR, quality);
        Huff dcL = new Huff(DC_LUM_BITS, DC_LUM_VALS), acL = new Huff(AC_LUM_BITS, AC_LUM_VALS);
        Huff dcC = new Huff(DC_CHR_BITS, DC_CHR_VALS), acC = new Huff(AC_CHR_BITS, AC_CHR_VALS);

        // --- Renk dönüşümü (JFIF YCbCr), kenarlar tekrarlanarak 8'e tamamlanır ---
        int bw = (w + 7) / 8, bh = (h + 7) / 8;
        int pw = bw * 8, ph = bh * 8;
        double[][] plane = new double[3][pw * ph];
        for (int y = 0; y < ph; y++) {
            int sy = Math.min(y, h - 1);
            for (int x = 0; x < pw; x++) {
                int p = argb[sy * w + Math.min(x, w - 1)];
                double r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int i = y * pw + x;
                plane[0][i] = 0.299 * r + 0.587 * g + 0.114 * b - 128.0;
                plane[1][i] = -0.168736 * r - 0.331264 * g + 0.5 * b;
                plane[2][i] = 0.5 * r - 0.418688 * g - 0.081312 * b;
            }
        }

        BitWriter bits = new BitWriter();
        int[] prevDc = new int[3];
        double[] blk = new double[64];
        double[] tmp = new double[64];
        int[] zz = new int[64];

        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                for (int c = 0; c < 3; c++) {
                    int[] q = (c == 0) ? qLum : qChr;
                    Huff dc = (c == 0) ? dcL : dcC;
                    Huff ac = (c == 0) ? acL : acC;
                    double[] pl = plane[c];
                    for (int y = 0; y < 8; y++)
                        for (int x = 0; x < 8; x++)
                            blk[y * 8 + x] = pl[(by * 8 + y) * pw + bx * 8 + x];
                    // Ayrılabilir 2B DCT
                    for (int y = 0; y < 8; y++) {
                        for (int u = 0; u < 8; u++) {
                            double s = 0;
                            for (int x = 0; x < 8; x++) s += blk[y * 8 + x] * COS[x][u];
                            tmp[y * 8 + u] = s;
                        }
                    }
                    for (int u = 0; u < 8; u++) {
                        for (int v = 0; v < 8; v++) {
                            double s = 0;
                            for (int y = 0; y < 8; y++) s += tmp[y * 8 + u] * COS[y][v];
                            int qi = v * 8 + u;
                            double val = s / q[qi];
                            zz[qi] = (int) Math.round(val);
                        }
                    }
                    // DC
                    int dcVal = zz[0];
                    int diff = dcVal - prevDc[c];
                    prevDc[c] = dcVal;
                    int cat = category(diff);
                    bits.put(dc.code[cat], dc.len[cat]);
                    if (cat > 0) bits.put(diff < 0 ? diff - 1 : diff, cat);
                    // AC (zigzag)
                    int run = 0;
                    for (int k = 1; k < 64; k++) {
                        int v = zz[ZIGZAG[k]];
                        if (v == 0) { run++; continue; }
                        while (run > 15) {
                            bits.put(ac.code[0xF0], ac.len[0xF0]);
                            run -= 16;
                        }
                        int cc = category(v);
                        int sym = (run << 4) | cc;
                        bits.put(ac.code[sym], ac.len[sym]);
                        bits.put(v < 0 ? v - 1 : v, cc);
                        run = 0;
                    }
                    if (run > 0) bits.put(ac.code[0x00], ac.len[0x00]);
                }
            }
        }
        bits.flush();
        byte[] scan = bits.out.toByteArray();

        // --- Başlık segmentleri ---
        ByteArrayOutputStream app = new ByteArrayOutputStream();   // APP0 + APP2
        if (dpi > 0) {
            byte[] jfif = {'J', 'F', 'I', 'F', 0, 1, 1, 1,
                (byte) (dpi >> 8), (byte) dpi, (byte) (dpi >> 8), (byte) dpi, 0, 0};
            segment(app, 0xE0, jfif);
        }
        if (icc != null && icc.length > 0) {
            final int max = 65519;
            int count = (icc.length + max - 1) / max;
            for (int i = 0; i < count; i++) {
                int off = i * max, n = Math.min(max, icc.length - off);
                byte[] pl = new byte[14 + n];
                byte[] id = {'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0};
                System.arraycopy(id, 0, pl, 0, 12);
                pl[12] = (byte) (i + 1);
                pl[13] = (byte) count;
                System.arraycopy(icc, off, pl, 14, n);
                segment(app, 0xE2, pl);
            }
        }

        ByteArrayOutputStream main = new ByteArrayOutputStream();
        byte[] dqt = new byte[65 * 2];
        dqt[0] = 0;
        dqt[65] = 1;
        for (int k = 0; k < 64; k++) {
            dqt[1 + k] = (byte) qLum[ZIGZAG[k]];
            dqt[66 + k] = (byte) qChr[ZIGZAG[k]];
        }
        segment(main, 0xDB, dqt);
        byte[] sof = {8, (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 3,
            1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1};
        segment(main, 0xC0, sof);
        segment(main, 0xC4, dht(0x00, DC_LUM_BITS, DC_LUM_VALS));
        segment(main, 0xC4, dht(0x10, AC_LUM_BITS, AC_LUM_VALS));
        segment(main, 0xC4, dht(0x01, DC_CHR_BITS, DC_CHR_VALS));
        segment(main, 0xC4, dht(0x11, AC_CHR_BITS, AC_CHR_VALS));
        byte[] sos = {3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0};
        segment(main, 0xDA, sos);

        int total = 2 + app.size() + main.size() + scan.length + 2;
        ByteArrayOutputStream pad = new ByteArrayOutputStream();
        int missing = minBytes - total;
        while (missing > 0) {
            int payload = Math.min(Math.max(missing - 4, 0), 65533);
            segment(pad, 0xFE, new byte[payload]);
            missing -= payload + 4;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(total + pad.size());
        out.write(0xFF);
        out.write(0xD8);
        out.write(app.toByteArray(), 0, app.size());
        out.write(pad.toByteArray(), 0, pad.size());
        out.write(main.toByteArray(), 0, main.size());
        out.write(scan, 0, scan.length);
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    /**
     * Üst boyut sınırına uyan en yüksek kaliteyi bulur: önce [quality] ile yazılır, [maxBytes]'ı aşarsa kalite
     * adım adım düşürülür (en fazla 40'a kadar). Alt sınır ([minBytes]) en son COM bölümleriyle tamamlanır.
     * [maxBytes] 0 ise üst sınır yoktur. Kalite 40'ta bile aşılıyorsa o sonuç döner (çağıran boyutu denetler).
     */
    public static byte[] encodeFit(int[] argb, int w, int h, int quality, int dpi, byte[] icc, int minBytes, int maxBytes) {
        int q = quality;
        byte[] out = encode(argb, w, h, q, dpi, icc, 0);
        while (maxBytes > 0 && out.length > maxBytes && q > 40) {
            q = Math.max(40, q - (out.length > maxBytes * 3 / 2 ? 8 : 3));
            out = encode(argb, w, h, q, dpi, icc, 0);
        }
        return out.length >= minBytes ? out : encode(argb, w, h, q, dpi, icc, minBytes);
    }

    private static byte[] dht(int tcTh, int[] bits, int[] vals) {
        byte[] b = new byte[1 + 16 + vals.length];
        b[0] = (byte) tcTh;
        for (int i = 0; i < 16; i++) b[1 + i] = (byte) bits[i];
        for (int i = 0; i < vals.length; i++) b[17 + i] = (byte) vals[i];
        return b;
    }
}
