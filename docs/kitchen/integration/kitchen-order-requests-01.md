# Kitchen → Order entegrasyonu: Order'dan beklenenler (01)

- **Güncelleme:** 27 Kasım 2026, Order PR #47 `00bb2aa` incelendikten sonra
- **Talep eden:** Kitchen (KITCHEN-INT-03)
- **İlgili:** Order sorumlusu `@HmzT270`

İki talep de [PR #47](https://github.com/software-lecture-labs/Restofy/pull/47)'de karşılandı. PR henüz `dev`'e birleşmedi. Bu yüzden durum "karşılandı, birleşme bekleniyor"dur. Yeni bir Order talebi yok. Kitchen tarafı ve ortam hazırlığı [entegrasyon rehberinde](../entegrasyon-rehberi.md).

| No | Talep | Durum | Kanıt |
|---|---|---|---|
| KO3-01 | Kitchen'ın imzasının Order'da doğrulanması | #47'de karşılandı | Kitchen'ın imzası #47'nin `KitchenServiceIdentityVerifier` ve `ServiceAuthenticationFilter` sınıflarından geçti; başka restoran ve değiştirilmiş gövde reddedildi (geçici, izole deneme) |
| KO3-02 | Order'ın onay isteğinin (`OrderCancellationRequested`) Kitchen'a gönderilmesi | #47'de karşılandı | #47'nin Kitchen projeksiyonunun ürettiği istek, kesin iptal ve ret gövdeleri Kitchen'ın katı okuyucusundan geçti; Kitchen'ın cevabı Order'da isteği sonuçlandırdı (geçici deneme, gerçek Order kodu ve PostgreSQL) |

Bu denemeler çalışan iki servis arasında gerçek HTTPS ile yapılmadı. Ortam hazırlığı (anahtar ve `kid` değişimi, HTTPS, saat eşitlemesi) rehberin §9'undadır ve kod değişikliği istemez.

**Not (talep değil):** Kitchen, `READY` bir siparişin kesin iptalini ve kısmi iptali uygulamaz (D-02). Bu olaylar Order'da `FAILED` olur ve aynı siparişin sonraki teslimlerini bekletir. Bu, D-02 kararlaşana kadar bilinçli bir uzlaştırma noktasıdır.
