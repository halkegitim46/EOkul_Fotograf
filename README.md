# E-Okul Foto Studio (Android)

Telefonla öğrenci fotoğrafı çek → e-Okul ölçüsüne (133×171) otomatik kırp → öğrenci numarası ve adıyla kaydet.

## Nasıl kullanılır
1. **Kayıt klasörü seç** (ana ekrandaki uyarı veya ⚙ Ayarlar). Tüm çıktılar bu klasörün altına yazılır.
2. **＋ Sınıf ekle** → sınıf adı (ör. 9-A) → listeyi yapıştır. e-Okul/Excel'den kopyalanan
   "numara + ad soyad" sütunları (sekme, ; , | - boşluk ayraçlı) otomatik algılanır; önizlemede hatalı satırlar görünür.
3. Sınıfa gir → öğrenciye dokun (veya **📷 Seri çekim**) → kamerada baş ovalini yerleştir → çek →
   önizlemeyi kontrol et → **Kaydet**. Seri çekimde otomatik sıradaki öğrenciye geçilir.

## Kayıt yapısı
```
<Kayıt klasörü>/9-A/12345-Ayşe Yılmaz.jpg          (133×171, arşiv adı)
<Kayıt klasörü>/9-A/Orijinaller/12345-Ayşe Yılmaz.jpg   (tam çözünürlüklü orijinal)
```
Çıktı: 4:4:4 JPEG, kalite 100, 300 DPI, sRGB profili gömülü, en az 20 KB.

## APK derleme (GitHub Actions)
1. GitHub'da **özel (private)** bir depo oluştur, bu klasörün içeriğini yükle (`.github` klasörü dahil).
2. Her push'ta (veya **Actions → APK Derle → Run workflow**) önce birim testleri çalışır, geçerse release APK derlenir.
3. Bitince çalıştırmanın altındaki **Artifacts → EOkulFotoStudio-apk** dosyasını indir; zip'ten çıkan `app-release.apk`'yı telefona kur
   ("bilinmeyen kaynaklardan yükleme" izni gerekir).
4. Sürüm kodu her çalıştırmada otomatik artar (`GITHUB_RUN_NUMBER`), sürüm adı `1.0.<çalıştırma no>`.

### Sabit imza anahtarı (güncellemelerin üstüne kurulması için, bir kez yapılır)
İmza anahtarı ve parolaları **kaynak kodda tutulmaz**. Anahtar yoksa APK debug anahtarıyla imzalanır (kurulur, fakat her
derlemede imza değiştiği için yeni sürüm eskisinin üstüne kurulmaz; önce yedek alıp eskisini kaldırmak gerekir).
1. **Actions → "Imza anahtari uret (bir kez)" → Run workflow** (yalnızca özel depoda).
2. Bitince çalıştırmanın **Artifacts → imza-anahtari** dosyasını indir (`eokul-anahtar.b64.txt`).
3. **Settings → Secrets and variables → Actions** altında şu gizli değişkenleri oluştur (değerleri dosyadan kopyala):
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. Anahtar üretme çalıştırmasını ve yüklenen artefaktı sil. Anahtarı güvenli bir yerde ayrıca sakla: kaybedersen
   uygulama güncellemeleri eski kurulumların üstüne kurulamaz.

## Önemli not
Kotlin/Compose/CameraX kodu bu ortamda derlenemedi (Android SDK'ya erişim yok). Çekirdek algoritmalar (kırpma, yeniden
boyutlandırma, JPEG kodlayıcı, liste ayrıştırıcı) Java ile birebir test edildi. İlk derlemede hata çıkarsa Actions
günlüğünü (hata satırlarını) yapıştır, düzeltelim.

## Eklenenler (Faz 2)
- Kalite uyarıları: bulanık, çok karanlık / parlak, baş eğik, yüz dönük (inceleme ekranında).
- Saç tepesi ölçümü: ML Kit kişi maskesi (yedek: arka plan analizi), saç üstü boşluk varsayılan %3.
- Sınıf menüsü (⋮): eksik listesini paylaş, e-Okul ZIP (içinde `12345.jpg` adlı dosyalar), dosya adlarını güncelle.
- Ayarlar: Yedekle / Geri yükle (sınıf listeleri + küçük fotoğraflar).

## Faz 3
- Kamerada anlık yüz tespiti: yeşil/sarı yüz kutusu ve yönlendirme ("Biraz yaklaşın", "Başı dik tutun"…).
- Otomatik odak/pozlama: yüze sürekli, ayrıca ekrana dokunarak.
- Çoklu sınıf listesi (Ana ekran ⋮ menüsü): şubeleri alt alta olan tek e-Okul Excel dosyasından 6A, 6B, 6C… sınıfları tek seferde eklenir. Şube; S.No'nun yeniden 1'e dönmesinden ve "Öğrenci Sayısı" alt satırından tanınır, alt satırdaki kız/erkek sayısı Cinsiyeti sütunuyla doğrulanır.
- Akıllı çekim (Ayarlar'dan kapatılabilir): göz açıklığı (ML Kit sınıflandırma), canlı netlik/ışık ölçümü, jiroskopla telefon sabitliği; hazır olunca odak/pozlama yüze kilitlenir, 3 kare çekilip en iyisi seçilir. Göz çizgisinden hassas baş eğikliği. Köşede 100 üzerinden canlı kalite puanı (kadraj 25, netlik 25, açı 20, ışık 20, göz 10).
- Otomatik çekim (anahtar): yüz kılavuza oturup sabit durunca 1,2 sn geri sayım ve çekim; deklanşör sesi + titreşim.
  Seri çekimde bir önceki öğrenci çerçevedeyken yanlışlıkla çekmemek için yüz çerçeveden çıkıp girince silahlanır.
- PIN kilidi (Ayarlar → PIN): açılışta ve 30 sn'den uzun arka planda kalınca sorulur.
- Listeyi CSV/TXT dosyadan alma (UTF-8 ve Windows-1254 desteklenir).

## Kontrol sayfası ve otomatik çekim ayarı
- Sınıf menüsü (⋮) → "Kontrol sayfası": tüm küçük fotoğraflar ızgarada; dokununca büyür, yeniden çek / kırpmayı düzenle. Eksikler kırmızı çerçeveli.
- Ayarlar → "Otomatik çekim": konum toleransı (sıkı ↔ gevşek) ve sabit bekleme süresi.

## Testler
`app/src/test` altındaki JUnit testleri (liste ayrıştırma, Excel okuma, kırpma / kadraj, JPEG, kalite ölçümü) her derlemede
otomatik çalışır; başarısız olursa APK üretilmez ve `test-raporu` yüklenir.

## Güvenilirlik notları
- Liste dosyası (`roster.json`) atomik yazılır, her kayıtta doğrulanır, son sağlam kopya `.bak` olarak tutulur; bozuksa yedekten
  okunur ve bozuk dosya silinmeden saklanır.
- Kayıt klasörüne yazarken önce geçici dosya, sonra yer değiştirme yapılır; eski dosya yeni içerik doğrulanmadan silinmez.
- Yedek / ZIP akış olarak yazılır; geri okunup manifest ve roster snapshot'ı doğrulanır. Geri yükleme geçici klasöre açılır; `restore.transaction` günlüğüyle roster ve fotoğraf klasörü birlikte devreye alınır. Uygulama işlem ortasında kapanırsa açılışta eski veri geri yüklenir.
- Öğrenci listesi kayıtları sürümlüdür; eski bir arka plan kaydı daha yeni listeyi ezemez. Fotoğraf dosyası doğrulanmadan öğrenci `çekildi` olarak kalıcı kaydedilmez.
- Ana ekran menüsünde "Veri denetimi" kayıtlarla dosyalar arasındaki tutarsızlıkları raporlar.
