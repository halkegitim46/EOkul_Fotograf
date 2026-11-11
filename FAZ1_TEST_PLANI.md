# Faz 1 – Zorunlu test planı

## A. SAF fotoğraf yazımı (`Output.writeStream`)

1. Var olmayan dosyaya yeni JPEG yaz: dosya açılmalı ve boyut eşleşmeli.
2. Var olan JPEG üzerine yeni JPEG yaz: yeni dosya açılmalı, eski dosya yalnızca doğrulamadan sonra kalkmalı.
3. Geçici dosya yazımı sırasında depolama bağlantısını kes: eski dosya değişmemeli.
4. Eski hedefi yeniden adlandırmayı desteklemeyen SAF sağlayıcısı kullan: yedek kopyası oluşturulmalı, yazım başarısızsa eski içerik geri gelmeli.
5. Yedek kopyası oluşturulamayan sağlayıcı kullan: işlem eski hedefe dokunmadan hata vermeli.
6. Yeni dosyaya kopyalama sırasında hata oluştur: eski dosya korunmalı veya koruma kopyası yerinde kalmalı.
7. Aynı dosya adına arka arkaya 20 kayıt yap: bozuk/yarım JPEG oluşmamalı.
8. MediaStore çıktısında var olan adın üzerine yaz: yeni dosya doğrulanmadan eski satır silinmemeli.

## B. Roster kaydı (`RosterStore`)

1. 1, 100, 500 ve 1.000 öğrenci kaydı oluşturup kaydet ve yeniden aç.
2. Her kayıtta JSON'u yeniden parse et; sınıf ve öğrenci sayıları ile alanlar aynı olmalı.
3. Ana dosyayı boz, sağlam `.bak` bulunduğunda açılışın yedekten döndüğünü doğrula.
4. Ana ve yedek dosyayı boz: bozuk ana dosyanın `.bozuk-*` kopyasının korunduğunu doğrula.
5. Kaydetme sırasında depolama alanını doldur: eski roster veya `.bak` korunmalı.
6. Art arda hızlı ekleme/silme/yeniden adlandırma yap: en son sürüm yeniden açılışta görünmeli.
7. Fotoğraf kaydı sürerken uygulamayı arka plana al: roster'da fotoğraf henüz tamamlanmadıysa öğrenci çekilmemiş görünmeli.

## C. Geri yükleme işlem günlüğü (`RestoreTransaction`)

Aşağıdaki her kesinti noktasında uygulamayı zorla durdurup yeniden aç:

1. `PREPARED` günlüğü yazılmadan önce.
2. `PREPARED` yazıldıktan sonra, eski roster kopyalanmadan önce.
3. Eski roster kopyalandıktan sonra.
4. Eski `photos/` klasörü `photos.restore.old` adına taşındıktan sonra.
5. Yeni `photos/` klasörü etkinleştirildikten sonra.
6. Yeni roster yazıldıktan sonra, günlük `COMMITTED` olmadan önce.
7. `COMMITTED` yazıldıktan sonra, temizlik tamamlanmadan önce.

Beklenen sonuç: ilk altı durumda eski veri bütünüyle geri gelmeli; son durumda yeni veri korunup eski geçici dosyalar temizlenmeli.

## D. ZIP doğrulama

1. Bozuk ZIP, eksik `roster.json`, eksik `yedek.json` reddedilmeli.
2. Manifest sınıf/öğrenci/fotoğraf sayıları uyuşmuyorsa geri yükleme başlamamalı.
3. Yinelenen ZIP girdileri reddedilmeli.
4. `photos/../../...` ve mutlak yol girdileri reddedilmeli.
5. Tek fotoğraf 5 MB sınırını, roster 30 MB sınırını, toplam içerik 800 MB sınırını aşarsa işlem iptal edilmeli.
6. Geçersiz JPEG'ler atlanmalı ve sayıları kullanıcıya bildirilmeli.
7. Yedek geri yükleme öncesi güvenlik yedeği alınamazsa mevcut veri değişmemeli.
8. Geri yükleme sırasında kamera kaydı/backup işlemiyle eşzamanlılık oluşmamalı.

## E. Gerçek cihaz testi

- Android 10, 12, 14 ve 15/16 cihazları.
- Dahili depolama ve USB/SAF klasörü.
- Google Files ve üretici dosya yöneticisi sağlayıcıları.
- 500+ öğrenci ve 500+ fotoğraf içeren yedek.
- Depolama alanı az, bağlantısı kesilen USB, uygulama arka plana alınması ve zorla durdurma senaryoları.

## Kabul kriteri

Hiçbir hata senaryosunda doğrulanmış eski roster/fotoğraf seti sessizce silinmemeli. Geri yükleme tamamlanmadıysa uygulama açılışta işlem günlüğünü kullanarak eski veri setini geri getirmeli veya kurtarma dosyalarını koruyarak otomatik kayıt işlemlerini durdurmalıdır.
