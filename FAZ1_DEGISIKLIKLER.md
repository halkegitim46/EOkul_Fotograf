# Faz 1 – Veri güvenilirliği değişiklikleri

Bu sürüm, mevcut `roster.json` ve `photos/` biçimini koruyarak kayıt ve geri yükleme dayanıklılığını artırır.

## Değiştirilen dosyalar

### `app/src/main/java/com/eokul/studio/Output.kt`
- SAF yazımında yeni içerik önce benzersiz geçici dosyaya yazılır.
- Geçici dosya uzunluk ve geri okuma ile doğrulanır.
- Var olan hedef dosya silinmeden önce yedek adına taşınır.
- Sağlayıcı yeniden adlandırmayı desteklemiyorsa eski içerik ayrı dosyaya kopyalanıp doğrulanır; hedef yerinde güncellenir.
- Yeni içerik doğrulanamazsa eski dosya geri yüklenmeye çalışılır; geri yükleme de başarısızsa koruma kopyası silinmez.
- MediaStore yazımında yeni kayıt `IS_PENDING=1` ile hazırlanır; veri doğrulanıp yayımlandıktan sonra önceki kayıt silinir.

### `app/src/main/java/com/eokul/studio/Roster.kt`
- JSON üretimi `encode()` içinde tekleştirildi.
- `prepare()` yeni listeyi ayrı dosyaya yazar, `fsync` uygular ve yeniden parse ederek doğrular.
- `commitPrepared()` mevcut listeyi rollback kopyasında tutar, `.bak` dosyasını günceller, yeni listeyi devreye alır ve içerik/sayı doğrulaması yapar.
- `save()` hazırlama ve commit aşamalarını kullanır.

### `app/src/main/java/com/eokul/studio/RestoreTransaction.kt` (yeni)
- `restore.transaction` işlem günlüğü oluşturur.
- Öğrenci listesi, `.bak` roster kopyası ve fotoğraf klasörü birlikte değiştirilir; geri alma sırasında üçünün de eski hali korunur.
- İşlem tamamlanmadan uygulama kapanırsa açılışta eski liste ve fotoğraf klasörü geri getirilir.
- `COMMITTED` durumunda yeni veriler korunur ve artık gerekmeyen eski kopyalar temizlenir.

### `app/src/main/java/com/eokul/studio/StudioViewModel.kt`
- Öğrenci listesi kayıtları sürüm numarasıyla sıralanır; eski snapshot yeni snapshot'ın üzerine yazamaz.
- `persistAndWait()` son snapshot'ın yazılmasını bekler.
- Uygulama içi 133×171 fotoğraf kopyası geçici dosya, `fsync`, doğrulama ve rollback ile yazılır.
- Fotoğraf dosyası diske başarıyla yazılmadan öğrenci için `captured=true` kalıcı hale getirilmez. Bekleyen çekimlerde kalıcı snapshot eski öğrenci durumunu kullanır.
- Yedek JSON'u mevcut roster dosyasından değil, yedeklenen snapshot'tan üretilir.
- Yedek/geri yükleme işlemleri devam eden fotoğraf yazımlarıyla sıraya alınır.
- Geri yüklemede yinelenen ZIP yolları, yol kaçırma, dosya sayısı, tekil dosya boyutu ve toplam boyut kontrol edilir.
- Yedek manifesti ile sınıf/öğrenci/fotoğraf sayıları karşılaştırılır.
- Başlangıçta yarım kalmış geri yükleme işlemi kurtarılır; kurtarma başarısızsa otomatik roster kaydı engellenir.

### `app/src/main/java/com/eokul/studio/MainActivity.kt`
- Activity arka plana geçerken `persistAndWait()` çağrılır.
- `onCleared()` içinde ana thread üzerinde bloklayıcı dosya yazımı kaldırılmıştır.

### `app/src/main/java/com/eokul/studio/SettingsScreen.kt`
- Geri yükleme sırasında ilerleme göstergesi ve açıklama gösterilir.
- Geri yükleme sürerken tekrar onay verilmesi engellenir.

## Veri uyumluluğu

- `roster.json` şeması değiştirilmedi.
- Öğrenci alanları ve fotoğraf klasörü yapısı değiştirilmedi.
- Mevcut yedek formatı (`yedek.json`, `roster.json`, `photos/`) korunmuştur.
- Yeni geri yükleme manifest kontrolü mevcut uygulamanın kendi yedek formatını bekler.

## Bilinen sınırlamalar

- SAF sağlayıcılarının rename/copy davranışları farklı olabilir. Kod, eski içeriği yedekleyemediği durumda mevcut dosyanın üzerine yazmayı reddeder.
- Bu işlem günlüğü uygulama içindeki `roster.json` ve `photos/` alanlarını kapsar. Kullanıcının seçtiği harici SAF klasöründeki çıktı dosyaları ayrı dosya işlemi olarak korunur.
- Android derlemesi ve cihaz testleri bu çalışma ortamında çalıştırılamadı; aşağıdaki test planı gerçek cihazda ve CI'da uygulanmalıdır.
