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

## 8 Kas — büyük sürüm yükseltmesi (DERLENMEDİ, ilk derleme log'u bekleniyor)
Eski → yeni: AGP 8.5.2→9.3.1 · Gradle 8.9→9.5.0 · JDK 17→21 · Kotlin 2.0.20→2.4.20 · Compose BOM 2024.09.03→2026.08.00
CameraX 1.3.4→1.6.2 · activity-compose 1.9.2→1.13.0 · core-ktx 1.13.1→1.19.1 · coroutines 1.8.1→1.11.0
exifinterface 1.3.7→1.4.2 · documentfile 1.0.1→1.1.0 · compileSdk 34→37 · targetSdk 34→35. ML Kit: en yenisi zaten kullanılıyor.
AGP 9: org.jetbrains.kotlin.android eklentisi kaldırıldı (yerleşik Kotlin), kotlinOptions kaldırıldı. Kotlin sürümü kök build.gradle.kts'te buildscript ile sabit.
Geri dönüş: EOkulFotoStudio-android-eski-surum-yedek.zip (8.5.2 sürümü, derlenip çalıştığı doğrulanmış).

## 8 Kas — sağ-sol çevir artık kamera başına
Önceki ayar ön ve arka kamera için ortaktı; arka kamera için açılınca ön kamera fotoğrafı da ters çevriliyordu.
Şimdi `flipCapture` (arka) ve `flipFront` (ön) ayrı. EXIF yönü kendi tablomuzla yazılıp geri okunarak doğrulanıyor (logcat: etiket "EOkul").

## 8 Kas — sınıf raporu
Student.score (kalite puanı) kaydedilir (JSON "score"); sınıf ekranında ortalama + düşük puanlı (<70) sayısı, "⚠ Düşük" süzgeç çipi, satırda puan.
Kırpma düzenleme (inceleme ekranı atlandığı için) puanı yeniden hesaplamaz; eski puan korunur. Galeriden/eski kayıtlarda puan -1 (gösterilmez).

## 8 Kas — kapanış özeti
Seri çekimde son öğrenci de bitince özet mesajı: tamamlanan sayı, kalite ortalaması, düşük puanlı sayısı.

## 8 Kas — profesyonel ışık denetimi + ana ekran düzeltmesi
- core/Lighting.java (+LightingTest): yüz ile arka plan parlaklık farkından arka ışık, yüzün sol/sağ farkından yan ışık, patlak piksellerden parlama.
  Eşikler: arka ışık fark>40 ve arka plan>130; yan ışık |sol-sağ|/ort>0.35; parlama patlak>%10 veya yüz>205. (Tahmin; gerçek ortamda ayarlanmalı.)
- Kamerada "Işık: iyi / arka ışık / yan ışık / parlama" çipi; sorun varsa yönlendirme metni ve otomatik çekim bekler, 4 sn sonra uyarıyla vazgeçer.
- Sonuç 3 ölçüm art arda aynı çıkınca kabul edilir (titreşim yok). Yalnızca "Akıllı" modda çalışır.
- Ana ekran: "Sınıf ekle" düğmesi telif yazısının üstüne alındı.

## 8 Kas — düz liste (No · Ad Soyad · Sınıf) içe aktarma
- core/FlatList.java: sınıf sütunlu (başlıklı/başlıksız), sınıf+şube ayrı sütun, birleştirilmiş boş sınıf hücreleri (üsttekiyle dolar),
  sınıf başlık satırları, sayfa adı = sınıf (xls ve xlsx çok sayfalı; SheetReader.readSheets). Sınıf adı "6/A","6-A","6. Sınıf A Şubesi" → "6A".
- FlatList.detect(): açık sınıf sütunu / sayfa adı → düz liste; aksi halde e-Okul blokları ile düz liste karşılaştırılıp çok sınıf bulan seçilir.
- Arayüz: Çoklu sınıf listesi ekranı aynı; "Biçim: …" satırı ve uyarı notları gösterir; sınıf adları dosyadan, elle düzenlenebilir.
- Testler: FlatListTest + test/resources (sinif_sutunlu, basliksiz_sira_ayri, sayfa_sinif, birlesik_sinif; .xls/.xlsx). Tüm veriler uydurma.
- Bilinmeyenler: gerçek okul dosyalarında eşikler/başlık adları farklı olabilir; tanınmayan başlık adı eklemek için FlatList.roleOf.

## İnceleme arka ışığı, yedek/geri yükleme, toplu dışa aktarma
- İnceleme ekranı: çekimden sonra da arka ışık / tek yandan ışık / parlama uyarısı (`Lighting.analyzeScaled`, canlı kamerayla aynı eşikler).
- Yedek: `yedek.json` özetli ZIP, yazıldıktan sonra geri okunup doğrulanır; "Son yedek" tarihi Ayarlar'da.
- Geri yükleme: önce içerik önizlemesi (sınıf/öğrenci sayısı, tarih; geçersiz dosya reddedilir), değiştirmeden önce otomatik `Geri-yukleme-oncesi-….zip` güvenlik yedeği (alınamazsa işlem başlamaz). Ayarlar'da sürüm/telefon geçişi rehberi.
- Ana ekran araçlar menüsü: "Tüm sınıflar ZIP (klasörlü + rapor)": `Sınıf/No-Ad Soyad.jpg` + `rapor.csv` (UTF-8 BOM, ; ayraçlı).
- Kotlin kısmı derlenmedi; Java çekirdek testleri 57/57 geçti.
