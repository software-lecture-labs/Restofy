# Auth entegrasyon paketi

[Güncel kapsam kapanışı](completion-review.md) · [Yerel PR açıklaması](pr-message.md)

Sahip: Kerem Kalyoncu (Auth/Mail). Güncelleme: 29 Ekim 2026.

[30 Ekim PR öncesi hoca yorumları denetimi](pre-pr-review.md): tüm PR'lar, tekrar bulunan
hatalar, yerel düzeltmeler ve doğrulama sınırları. [Yeni anahtar kurulumu](key-setup.md).

Auth personel kimliğini üretir; servisler JWTyi yerelde doğrular. Auth diğer servislerin bütün clientlarını veya Sagalarını yönetmez. Cookie/JWT protokolü **staff-v1** olarak belgelenir; bu etiket yeni bir JWT claimi veya endpoint değildir.

| Servis | Sahip | Statik inceleme sonucu |
|---|---|---|
| [api-gateway](api-gateway/README.md) | Ortak altyapı; Alper koordinasyonu | MEVCUT yönlendirme; güvenlik entegrasyonu DOĞRULANMADI |
| [order-service](order-service/README.md) | Hamza | UYUMSUZ personel taşıması; EKSİK gerçek adapter |
| [kitchen-service](kitchen-service/README.md) | Nisa | EKSİK production personel kimliği ve dış teslim adapterları |
| [menu-service](menu-service/README.md) | Merve | EKSİK CurrentActorProvider; iç Kitchen kimliği ayrı |
| [qr-service](qr-service/README.md) | Alper | MEVCUT QR-01..04 domain/HTTP sınırları; EKSİK güven adapterları |
| [waiter-service](waiter-service/README.md) | Umut | EKSİK AuthenticatedStaff üreticisi; gerçek downstream bağlantıları tamamlanmamış |
| [mail-service](mail-service/README.md) | Kerem | MEVCUT Auth→Mail iç gönderim; genel servis modeliyle UYUMSUZ ayrıntılar |
| [admin-service](admin-service/README.md) | Umut | Servis İSKELET; Auth personel yönetimi HAZIR |
| [notification-service](notification-service/README.md) | Hamza | Servis İSKELET; Auth aktif alıcı sorgusu HAZIR |
| [payment-service](payment-service/README.md) | Alper | İSKELET; çalışan business API/adapter yok |
| [rating-service](rating-service/README.md) | Nisa | İSKELET; çalışan business API/adapter yok |
| [timer-service](timer-service/README.md) | Merve | İSKELET; çalışan business API/adapter yok |

- [Personel sözleşmesi ve uyumluluk](staff-contract.md)
- [Hazır personel yönetimi ve Notification alıcı API'si](staff-directory-api.md)
- [Frontend bağlantısı ve marka renkleri](frontend/README.md)
- [14 dalın SHA kaydı](branch-snapshot.md)
- [Hocanın PR yorumları ve ayrı işler](review-follow-up.md)
- [Hocanın 20 mesajının güncel kodla karşılaştırması](teacher-review-audit.md)
- [Kalite kapısı](quality.md)
- [Auth/Mail sağlamlaştırma ve geçiş notları](hardening-status.md)
- [Doğrulama ve teslim durumu](status.md)
- [Bağımsız son inceleme ve gerçek client durumu](delivery-review.md)

MEVCUT kod varlığıdır; EKSİK uygulanmamıştır; UYUMSUZ sözleşme farkıdır; ÖNERİ ekipçe uygulanacak karardır; DOĞRULANMADI çalıştırılmamış davranıştır. Bu paket diğer servislerin tamamlandığını veya productiona hazır olduğunu iddia etmez.

Personel yönetimi ve Notification alıcı API'si Auth'ta uygulandı; bağlantı ayrıntıları yukarıdaki sözleşmededir.
Diğer ihtiyaçlar çoğunlukla consumer adapterlarıdır; introspection veya varsayımsal Saga uçları eklenmedi.
Guest browser taşıması, çok restoranlı üyelik ve online anahtar rotasyonu ayrı kapsamdır.
