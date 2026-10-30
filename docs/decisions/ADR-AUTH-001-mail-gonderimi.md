# ADR-AUTH-001 — Auth→Mail dayanıklı teslim

- Durum: 29 Ekim 2026 kullanıcı onayıyla uygulandı; yerel doğrulama sonuçları hardening raporunda.
- Sahip: Auth/Mail, Kerem Kalyoncu. Önceki 20 Ekim best-effort kararı bu revizyonla değişti.
- Kapsam: yeni Auth V6, Mail V12; önceki migration'lar değişmez. Canlıya uygulanmadı.

## Karar ve transaction sınırı

Parola sıfırlama kodu/grant/parola değişimiyle Auth outbox kaydı aynı transaction'dadır.
Kuyruk doluysa veya kayıt yazılamazsa değişiklik rollback olur; başarılıymış gibi cevap verilmez.
Relay commit sonrasında HTTP gönderir. 1000 aktif mesaj sınırı, 8 deneme, 2–30 sn backoff,
30 sn claim lease ve son teslim süresi vardır. Restart'ta süresi geçen lease yeniden alınır.
Aynı messageId her denemede korunur; Mail'in kayıp HTTP cevabı ikinci bir iş oluşturmaz.
Bozuk şifreli kayıt FAILED olur, içerik temizlenir.

Mail kabulü metadata ve şifreli payload'ı aynı transaction'da yazar. İlk messageId'nin içeriği
kazanır; tekrar gelen farklı body üzerine yazılmaz. Worker yeniden başlayınca PENDING ve vadesi
gelen RETRY işleri 1 sn arayla okur; atomik claim yalnız bir worker'ın SMTP çağrısını başlatır.
Yerel executor doluluğu işi kaybettirmez: kabul edilen şifreli kayıt recovery için kalır.
En fazla 100 açık Mail işi, 3 SMTP denemesi ve geçici bağlantı hatasında 5 sn ara korunur.

## Güvenlik

İçerik iki servisin kendi DB'sinde kısa süreli AES-256-GCM ile şifrelidir; her kayıt rastgele
12 byte nonce ve messageId'ye bağlı AAD kullanır. Auth/Mail amaç etiketleri farklıdır.
Kod/recipient loglanmaz. Başarı, son hata veya süre dolumunda payload silinir/NULL yapılır;
süresi dolan kayıt bir sonraki çalışan sweep'te temizlenir. Servis kapalıyken fiziksel temizleme
çalışmaz; kapalı sürede de ciphertext ve anahtarın erişim güvenliği işletimin sorumluluğundadır.
DB yedekleri için saklama/silme politikası ayrıca gerekir.

Auth→Mail JWT ayrı RSA anahtarı kullanır: AUTH_MAIL_PRIVATE_KEY; MAIL_AUTH_PUBLIC_KEY bunun
public karşılığıdır. Session RSA'sıyla aynı modulus Auth başlangıcında reddedilir. Sessiz fallback yok.
JWT issuer/audience/scope/Bearer yolu ve HTTP DTO değişmez. Bu, genel Service JWT önerisine geçiş değildir.

## Sınırlar

SMTP kabulü ve DB commit'i atomik değildir. SMTP çağrısından sonra süreç kapanırsa veya metadata
commit'i kaybolursa SENDING işi kör tekrar gönderilmez; deadline'da UNKNOWN olur. SMTP kabulü,
kullanıcının inbox'ına teslim kanıtı değildir. Bu çözüm tam-bir-kez e-posta garantisi vermez.
Bekleyen eski reset kodu pasifleştirme/yeni reset sonrası ulaşabilir; Auth grant doğrulaması eski
kodu kabul etmez. Teslim penceresi 2 dk; kodun geçerliliği ayrı 15 dk sınırıdır.

## Yerelden dağıtıma geçiş (uygulanmadı)

1. Yetkili operatör ayrı RSA çiftini ve birbirinden bağımsız 32 random byte AES anahtarlarını üretir;
   gerçek değerler yalnız secret/ignored ENV'de tutulur. AUTH_MAIL_OUTBOX_KEY ve
   MAIL_PAYLOAD_ENCRYPTION_KEY birbirine veya mevcut MFA/CSRF/HMAC sırrına kopyalanmaz.
2. Trafik kontrollü durdurulur; eski Mail'in kabul ettiği işler tamamlanana veya süreleri dolana
   kadar eski süreç açık tutulur. Eski sürümün yalnız bellekte tuttuğu içerik V12 ile kurtarılamaz.
   Ardından önce Mail V12 ve yeni public/encryption ayarı, sonra Auth V5/V6
   ve yeni signing/outbox ayarıyla birlikte geçilir. Mail'in eski tek-key doğrulayıcısı nedeniyle
   eski/yeni signer'ın karıştığı rolling geçiş desteklenmez; koordineli geçiş gerekir.
3. Sağlık, negatif session-key JWT reddi ve gerçek servis iletişimi operatörce doğrulanır.
4. AES anahtarı, o anahtarla şifreli aktif işler bitmeden değiştirilmez. Keyring/online rotasyon bu turda yok.
   Kaybolan anahtar içerik kurtarmayı imkânsız kılar; bozuk kayıt güvenli terminal duruma taşınır.
5. Geri dönüşte yeni tablolar bırakılabilir; eski migration'lar geri alınmaz. Eski uygulama yeni
   outbox'ı işlemez: bekleyen işlerin bitmesi/expiry ve signer ayarlarının birlikte geri dönmesi gerekir.
   Çalışan DB'ye rollback/migration bu çalışma tarafından uygulanmadı.
