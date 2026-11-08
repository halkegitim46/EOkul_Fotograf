# Geliştirme notları ve bilinen sorunlar

## Açık sorunlar
- **Bellek yetersiz hatası (2 kez, 2 Kas 2026):** Telefonda kullanım sırasında "Bellek yetersiz" hatası alındı.
  Android çıkış kaydı: "1 Kas 19:11 ve 19:34 — bellek yetersiz" (REASON_LOW_MEMORY). Bu bir Java OutOfMemory çökmesi değil;
  sistem (lmkd) uygulamayı bellek baskısı nedeniyle kapattı. Telefon 8+8 GB (8 GB gerçek + 8 GB sanal RAM).
  Önlem olarak: analiz hızı ~12 kare/sn'ye sınırlandı, inceleme sırasında 17 MB'lık bitmap tutulmuyor (düzenleyici açılınca
  piksellerden üretiliyor), çalışma görüntüsü 2400→2000 px. Hâlâ olursa: uygulamanın ön planda mı arka planda mı olduğu,
  ve çekim sayısıyla ilişkisi sorulacak.
  İncelenecek şüpheli noktalar:
  - Çekim sonrası bellekte tutulan iki büyük kopya (2400 px ARGB bitmap ≈ 17 MB + piksel dizisi ≈ 17 MB) ve
    arka arkaya çekimlerde eski oturumların geç serbest kalması.
  - Kamerada Preview + ImageCapture + ImageAnalysis birlikte; 3 kareli seri çekim ve her karenin değerlendirilmesi.
  - Toplu işlemler (ZIP / yedek / toplu kırpma) sırasında büyük listeler.
- **Fotoğraf çekimden sonra sağ-sol ters görünüyor (arka kamera):** Neden bulunamadı; geçici çözüm olarak
  Kamera panelinde / Ayarlar'da "Sağ-sol çevir" eklendi. İnceleme ekranında yön (EXIF) sayısı gösteriliyor.

## Çözülenler
- Çekimden hemen sonra uygulamanın kapanması: kamera ekranı kapanırken biten yüz algılama işinin teslim edilememesi
  olası neden olarak düzeltildi (senkron bekleme); kamera arayüzü 30 Hz yerine 10 Hz güncelleniyor. Telefonda doğrulama bekleniyor.

## 7 Kas 2026 notu
- Kayıtlı "bellek yetersiz" çıkışları: 6 Kas 06:52 ve 7 Kas 00:37 — kullanıcı o saatlerde uygulamayı kullanmıyordu. Yani uygulama
  arka planda (son uygulamalarda beklerken) Android tarafından bellek için kapatıldı; bu normal bir davranıştır, veri kaybı yoktur.
  Bilgi penceresi artık yalnızca uygulama açıkken olan düşük bellek kapanmalarını gösterir (importance <= VISIBLE).

## 8 Kas — düğme standardı ve kayıtlı kırpma
- `Buttons.kt`: tek düğme standardı (AppButton: Birincil/İkincil/Tehlike/Üçüncül, 52 dp, köşe 14 dp). Tüm yeni düğmeler bunu kullanmalı.
- "Kırpmayı düzenle" artık inceleme ekranını atlar: düzenleyici doğrudan açılır, "Uygula ve kaydet" hemen kaydeder, İptal hiçbir şeyi değiştirmez.
- Kayıtlı orijinal bulunamazsa (taşınmış/silinmiş/yeniden adlandırılmış) klasörde numaraya göre aranır; yoksa açık mesaj verilir.
