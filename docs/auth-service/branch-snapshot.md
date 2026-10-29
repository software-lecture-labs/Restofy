# Dal kanıt kaydı

GitHub branches API üzerinden 29 Ekim 2026 oturumunda kontrol edildi. Kaynak: [dal listesi](https://github.com/software-lecture-labs/Restofy/branches). Çalışma başlangıcı Auth HEAD `9e0fd4cd3eaddff8e216a8a12b55d85612b5a103`.

| Dal | SHA |
|---|---|
| dev | `7ae589fda3e6628cc59706a17e3490f3e8f08e28` |
| feature/admin-service | `494b189949ab05d31d2d80aae3202bb61e314c0d` |
| feature/auth-service | `9e0fd4cd3eaddff8e216a8a12b55d85612b5a103` |
| feature/kitchen-service | `3c277496e6b5ff33291bf003e2f2e7daea0273cf` |
| feature/mail-service | `494b189949ab05d31d2d80aae3202bb61e314c0d` |
| feature/menu-service | `c95985839f7f52f8bb0ce49e33da9bcb791bb622` |
| feature/notification-service | `494b189949ab05d31d2d80aae3202bb61e314c0d` |
| feature/order-service | `fcd8c3be8bd2abd407b820fe17917a2515d77560` |
| feature/payment-service | `494b189949ab05d31d2d80aae3202bb61e314c0d` |
| feature/qr-service | `b95371242e017d130940a3ff803cc95ea9867a1f` |
| feature/rating-service | `494b189949ab05d31d2d80aae3202bb61e314c0d` |
| feature/timer-service | `494b189949ab05d31d2d80aae3202bb61e314c0d` |
| feature/waiter-service | `49d5e3c72597130f7d275b1c59b90eab1816c47d` |
| main | `925be7fcb2b28976afab9f6fe83bde4d41d79288` |

- Auth PR #2 24 Ekimde dev'e merge edildi; yerel origin/dev eski kaldığından uzak dev durumu GitHubdan doğrulandı. Ana çalışma dalı değiştirilmedi.
- QR dalı bu inceleme sırasında `016ac23` → `b953712` ilerledi. Karşılaştırma `36e6a93` bakım/CI ve yeni QR entegrasyon rehberi ile dev birleşimini gösterdi; güncel rehberin güven sınırları/admission/outcome/kapanış bölümleri ayrıca okundu. Eski QR güvenlik sınırları açılmamış.
- Mail feature dalı iskelet olsa da gerçek Mail kodu Auth/dev teslimindedir. Bu nedenle Mail sayfası Auth SHA'sını esas alır.
- Diğer feature SHA'ları yerel git nesneleriyle eşleştirildi. Controller/port/client/security ve test dosyaları checkout/merge yapmadan incelendi; consumer testleri çalıştırılmadı.
- Snapshot sonrası uzak dallar değişebilir; satırlar canlı ilerleme garantisi değildir.
