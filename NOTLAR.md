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

## Seri çekimde en iyi kare (Akıllı mod, 3 kare)
- Her kare ayrı çözümlenir (ML Kit, sınıflandırma açık): o karenin göz açıklığı, gülümseme, baş açısı, gerçek yüz bölgesinde netlik/ışık.
- `core/Burst.java` cezaları: yüz yok −35, gözler kapalı −25, gülümseme en çok −14 (0.35→0.80 arası), arka ışık/parlama −10, tek yandan ışık −6. Bilinmeyen değer ceza almaz.
- Önceki sürümde canlı önizlemedeki tek göz değeri tüm karelere yazılıyordu; artık yalnızca yüz çözümlenemezse yedek.

## Küçültme sonrası netlik / "kumlu" görüntü, %100 önizleme, ayarlanabilir standart
Ölçüm (sentetik portre, 1200x1600 → 133x171, lekeli telefon gürültüsü + JPEG q88; `sharp/` deneyleri):
- Küçültmeden önceki gürültü azaltma yalnızca kırpma <399 px iken çalışıyordu; gerçek (ilintili) gürültü küçültmeyle yok olmuyor, çıktıda ideale göre 2.5–4× kalıyordu. Keskinleştirme (0.35) gürültüyü ~%30 büyütüyordu.
- Yeni `Finish`: gürültü azaltma kırpma boyutuna değil ölçülen gürültüye bağlı (σ>1.2), keskinleştirme gürültü arttıkça 0'a doğru azalır (σ/6, en çok %80). Sonuç: PSNR +1…3 dB, çıktı gürültüsü ~%30 az, kenar aşırı vurgusu azaldı.
- Çekim: "Yüksek kaliteli çekim" (Ayarlar › Çekim, varsayılan açık): MAXIMIZE_QUALITY, ~2560x1920, JPEG 95; kapalıysa eski hızlı mod (1600x1200, MINIMIZE_LATENCY). Çalışma görüntüsü sınırı 2000 → 2700 (aksi halde 2592 px kare yarıya inip 1296'ya düşerdi).
- İnceleme ekranında "%100 gerçek boyut": çıktı 1 piksel = 1 ekran pikseli, büyütme/yumuşatma yok (e-Okul'da görünecek boyut). Büyütülmüş önizleme 133 px'i 3x+ ölçekler ve doğal olarak kumlu/bulanık görünür.
- Fotoğraf standardı ayarlanabilir: genişlik, yükseklik, min/maks KB (Ayarlar › Gelişmiş çıktı). `JpegEncoder.encodeFit`: maks KB'ı aşarsa kalite otomatik düşer. Standart değişince eski fotoğraflar değişmez; Veri denetimi yanlış boyutluları listeler.
- Java testleri 68/68 (FinishNoiseTest, EncodeFitTest yeni). Kotlin derlenmedi.

## Dayanıklılık taraması (PipelineStressTest, 15 test)
Rastgele/uç girdilerle: JPEG (1x1…600x800, kalite 1–100, düz/gradyan/gürültü) standart çözücüyle açılıyor; 400 rastgele kırpma ve 160 Finish koşusu çökmeden doğru boyut/aralık üretiyor, düz renk düz kalıyor; `Crop.defaultCropBox` 8 görüntü şekli × 5 standart × kenara taşan yüzlerde hep görüntü içinde ve doğru oranda; NaN/sonsuz kutular ölçümleri çökertmiyor; uçtan uca 7 çekim şekli × 3 standart geçerli JPEG ve KB aralığında.
Bulunup düzeltilenler:
- `Score.total`: framingDev (ve roll/yaw) NaN gelirse tüm puan 0'a düşüyordu → bilinmeyen ölçüm artık orta değer alır.
- `JpegEncoder.encodeFit`: kalite tabanı 40'tı; gürültülü/büyük standartta üst KB sınırı aşılabiliyordu → taban 20.
- `ImageOps.decode`: bellek yetmezse (≈5 MP çekim + eşzamanlı iş) hata vermek yerine bir kademe daha küçük örnekleyerek yeniden dener (en fazla 2 kez).
Java testleri 83/83. Kotlin derlenmedi.

## Çekim hızı (araştırma + değişiklikler)
Öğrenci başına bekleme zinciri (akıllı + otomatik çekim): sabit bekleme 1.2 sn + odak kilidi 0.35 sn + 3 kare art arda (HQ'da her biri ~1 sn) + her kareyi puanlama + tam çözünürlüklü çözme/yüz tespiti + inceleme penceresi 1.2 sn + kaydın bitmesini bekleme (SAF yazma, orijinal sıkıştırma). Ölçüm (masaüstü JVM): Finish ~120 ms, JPEG ~10 ms, Quality ~20 ms → Java tarafı darboğaz değil.
Yapılanlar:
- Erken çıkış: kare puanlanır; ≥86 ise kalan kareler çekilmez (çoğu öğrenci tek kare). Daha düşükse sıradaki kare çekilir (en fazla 3), en iyisi seçilir; puanlar yeniden hesaplanmaz (`BurstPick.bestOf`).
- Kare yüz tespiti FAST moda (göz/gülümseme sınıflandırması korunur).
- Bekleme süreleri: otomatik çekim sabit bekleme varsayılanı 1.2→0.9 sn (yeni kurulumlarda; kayıtlı ayar korunur), odak kilidi 350→250 ms, otomatik kabul penceresi 1.2→0.7 sn.
- Arka planda kayıt: öğrenci hemen "çekildi" olur ve sıradakine geçilir; dosyalar sırayla (Mutex) yazılır; hata olursa öğrenci eski haline döner ve uyarı çıkar.
- Orijinal arşiv kopyası: Java Lanczos yerine yerel ölçekleme + JPEG (HQ çekimde her kayıtta çalışıyordu).
- Denenmedi: Zero-Shutter-Lag (CameraX deneysel API; destek cihaza bağlı, flaş kapalı olmalı), kamerayı inceleme sırasında açık tutma.

## "Hepsi tekrar çekilmeli" hatası (kalite puanı / uyarılar)
Nedenler: (1) arka ışık kuralı yalnızca "arka plan yüzden parlak" diyordu; kamera yüze pozlama yaptığı için düz beyaz duvar hep alarm veriyordu (canlıda çekimi 4 sn bekletiyor, incelemede otomatik kaydı engelliyordu). (2) İncelemede herhangi bir uyarı kadraj puanını 25→10 (−15) yapıyor ve fotoğrafı "tekrar çekilmeli" işaretliyordu.
Düzeltme: arka ışık = arka plan parlak VE yüz ortalaması <90 (beyaz duvar + iyi pozlanmış yüz artık sorun değil); uyarılar AĞIR (yeniden çekim işareti + otomatik kaydı durdurur) ve BİLGİ (ışık dengesi, hafif kadraj; işaret koymaz, otomatik kaydı engellemez) olarak ayrıldı; kadraj puanı dereceli (0/0.7/1.3/2.0), ışık için Burst cezası; parlaklık ideal aralığı 95–165; canlıda ışık engeli 2 sn. Sınıf ekranı › Tekrar filtresinde "Tüm işaretleri temizle" eklendi (eski sürümün hatalı işaretleri için).

## Kırpmayı düzenle: kutu ölçekleme hatası
Kayıtlı kırpma kutusu çekim anındaki çalışma görüntüsünün piksel koordinatındadır (HQ çekimde ~2592 px). "Kırpmayı düzenle" ise Orijinaller klasöründeki, gerekirse küçültülmüş (≤1600 px) kopyayı açıyordu ve kutuyu ölçeklemiyordu → yanlış bölge. Düzeltme: Student'a `boxW/boxH` (kutunun tanımlandığı görüntü boyutu) eklendi (roster.json'da "boxW","boxH"), düzenlemede `Crop.scaleBox` ile yeni boyuta ölçeklenir. Eski kayıtlarda boyut yok (0) → kutu olduğu gibi kullanılır (eski davranış); bu kayıtlar yeniden kaydedilince düzelir.

## Çekim sonrası çözmeyi önden başlatma (prefetch)
Önceden: kare seçildikten sonra inceleme ekranı tam çözünürlüklü görüntüyü yeniden çözüp yüz tespiti yapıyordu (~0.5–1 sn bekleme).
Şimdi: her kare kaydedilir kaydedilmez (önce yatay çevirme EXIF'i uygulanır) `StudioViewModel.prefetchPhoto` ile çözme + yüz/segmentasyon arka planda başlar, kare puanlamasıyla paralel çalışır. Seçilen kare buysa `onPhotoReady` hazır sonucu kullanır; değilse iş iptal edilip eski yolla çözülür. En fazla bir bekleyen iş; ekrandan çıkılırsa `dropPrefetch`. Yatay çevirme artık her karede kare kaydedilince yapılır (önceden yalnızca seçilen karede, sonda); `BurstPick` ayna içeren EXIF yönlerinde (2,4,5,7) de doğru döndürür.

## İşlem günlüğü ve tanılama raporu
- `core/OpLog.java`: cihaz üstü JSON-satır günlüğü (`filesDir/oplog.jsonl`, ~600 KB sınır, eski yarısı atılır). Çekim akışını bekletmez (tek iş parçacıklı yazıcı).
- `core/OpLogReport.java`: günlükten özet (süre medyan/%90, erken çıkış, ön çözme isabeti, puanlar, uyarı kodları, hatalar) + otomatik **Bulgular**.
- `Ops.kt`: sarmalayıcı; olaylar: app_start, shutter, frame, capture, review_ready, save_start, save, discard, error, crash, op(backup).
- Gizlilik: öğrenci adı yazılmaz; numara kısa hash ile anılır.
- Ayarlar › "Tanılama ve rapor": "Raporu kaydet" → kayıt klasörüne `Rapor-tarih.txt`; "Kaydı temizle".
- Düzeltilen hata (testle yakalandı): `OpLog.clear()` kilit tutarken flush bekleyip kilitleniyordu.

## Faz 1 / Faz 2 incelemesi ve rötuşlar
- Gelen dosyalar (Output, Roster, RestoreTransaction, StudioViewModel, Crop, Prefs, Live, CameraScreen, SettingsScreen) projedekilerle aynıydı; değişiklikler zaten içerideydi. Hepsi korundu.
- Yedek / rapor dosyaları artık `EOkulStudio_Yedek_YYYY-AA-GG_SS-DD-SS.zip`, `EOkulStudio_GeriYuklemeOncesi_…zip`, `EOkulStudio_Rapor_…txt`. Manifeste `format` ve `appVersion` eklendi. Eski `Yedek-….zip` dosyaları hâlâ geri yüklenir (denetim içeriğe bakar).
- Output.writeStream: taşıma sonrası ikinci tam okuma kaldırıldı (yalnızca varlık+boyut), rename sonrası klasör yeniden taranmıyor, 1 saatten eski `.tmp-` artıkları klasör başına bir kez temizleniyor.
- `Crop.headFraction`: baş oranı dikey ve yatay boşluklardan hangisi belirleyiciyse onu izler; `guideFor` ve `GuideOverlay` aynı kaynağı kullanır (canlı kılavuz = çekim sonrası kırpma). Testli.
- Ayarlar: sol/sağ boşluk "gelişmiş" altına alındı.

## Merkezleme, rapor paylaşımı, orijinal tanılaması
- Hata: `Crop.defaultCropBox` yatay konumu yüzün SOL kenarına göre hizalıyordu (x1 = yüz.sol − solBoşluk·w); dikey sınır belirleyiciyken kutu yüzden geniş olunca yüz sola yaslanıyor, sağ taraf boş kalıyordu. Artık x1 = merkez − w·(1+sol−sağ)/2 (eşit boşlukta tam ortada). Testler eklendi.
- Rapor: "Raporu paylaş" (Android paylaşım) ve "Dosyaya kaydet" (konumu kullanıcı seçer). Kayıt klasörüne gizli yazma kaldırıldı.
- Orijinal saklama: IOException dışındaki hatalar da yakalanır (ana kayıt geri alınmaz), sonuç günlüğe `orig` olayı olarak yazılır; rapor "Orijinal saklanamadı / ayar kapalı" bulgusu verir.

## Veri denetimi: kayıt klasörü kontrolü
- Ana ekran > Araçlar > "🩺 Veri denetimi" artık kayıt klasörünü de okur (değiştirmez): sınıf klasörü var mı, çekilmiş öğrencilerin fotoğrafı/orijinali klasörde mi, yarım kalmış `.tmp-`/`.old-` gizli dosya var mı. Klasör yoksa kökteki adlar listelenir.

## KRİTİK hata: kayıt klasörüne yazılan dosya hemen siliniyordu (Faz 1 Output.writeStream)
- Belirti: Veri denetimi 5G klasöründe 25 çekilmiş öğrenci için 25 fotoğraf + 25 orijinal eksik gösterdi, oysa günlükte tüm `save` olayları ok=true idi (uygulama içi kopya yazıldığı için küçük resimler görünüyordu).
- Neden: `DocumentFile.renameTo` başarılı olunca nesnenin URI'si yeni dosyaya taşınır; ardından çağrılan `tmp.delete()` yeni yazılan nihai dosyayı siliyordu. (Rename desteklenmeyen sağlayıcıda sorun yoktu.)
- Düzeltme: `tmpRenamed` bayrağı; rename sonrası tmp nesnesi hiçbir yolda silinmez.
- Onarım: Ana ekran > Araçlar > "🛠 Eksik klasör dosyalarını onar" eksik fotoğrafları uygulama içi 133x171 kopyadan yeniden yazar. Orijinaller geri getirilemez (yeniden çekim gerekir).
- Ders: SAF yazım davranışı yalnızca cihazda doğrulanabilir; Veri denetimi klasör kontrolü bu hatayı yakaladı.
