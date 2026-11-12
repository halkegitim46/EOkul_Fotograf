package com.eokul.studio.core;

/** Test yardımcısı: JPEG'i bölüm bölüm yapısal olarak doğrular ve boyutunu okur (java.awt gerekmez). */
final class JpegInfo {
    final int width, height, components;
    final boolean jfif;

    private JpegInfo(int w, int h, int c, boolean jfif) {
        this.width = w;
        this.height = h;
        this.components = c;
        this.jfif = jfif;
    }

    /** Biçim bozuksa AssertionError benzeri IllegalStateException fırlatır. */
    static JpegInfo parse(byte[] b) {
        if (b.length < 4 || (b[0] & 255) != 0xFF || (b[1] & 255) != 0xD8) throw new IllegalStateException("SOI yok");
        if ((b[b.length - 2] & 255) != 0xFF || (b[b.length - 1] & 255) != 0xD9) throw new IllegalStateException("EOI yok");
        int i = 2, w = -1, h = -1, comps = 0;
        boolean jfif = false, sos = false, dqt = false, dht = false;
        while (i + 4 <= b.length && !sos) {
            if ((b[i] & 255) != 0xFF) throw new IllegalStateException("Bölüm işareti yok @" + i);
            int m = b[i + 1] & 255;
            int len = ((b[i + 2] & 255) << 8) | (b[i + 3] & 255);
            if (len < 2 || i + 2 + len > b.length) throw new IllegalStateException("Bölüm uzunluğu bozuk @" + i);
            if (m == 0xE0 && len >= 7 && b[i + 4] == 'J' && b[i + 5] == 'F') jfif = true;
            if (m == 0xDB) dqt = true;
            if (m == 0xC4) dht = true;
            if (m == 0xC0 || m == 0xC2) {
                h = ((b[i + 5] & 255) << 8) | (b[i + 6] & 255);
                w = ((b[i + 7] & 255) << 8) | (b[i + 8] & 255);
                comps = b[i + 9] & 255;
            }
            if (m == 0xDA) sos = true;
            i += 2 + len;
        }
        if (!sos) throw new IllegalStateException("SOS yok");
        if (w <= 0 || h <= 0) throw new IllegalStateException("SOF yok");
        if (!dqt || !dht) throw new IllegalStateException("DQT/DHT yok");
        // Tarama verisinde 0xFF'ten sonra yalnızca 0x00 (kaçış) gelebilir; bitiş EOI
        for (int k = i; k < b.length - 2; k++)
            if ((b[k] & 255) == 0xFF && (b[k + 1] & 255) != 0x00)
                throw new IllegalStateException("Tarama verisinde beklenmeyen işaret @" + k);
        return new JpegInfo(w, h, comps, jfif);
    }
}
