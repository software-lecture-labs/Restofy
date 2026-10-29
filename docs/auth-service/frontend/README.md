# Frontend bağlantısı ve mevcut marka paleti

Sorumlu: UI iş dağılımı ekipçe belirlenecek; Auth sözleşmesi Kerem. Frontend/Auth kaynak SHA: `9e0fd4cd3eaddff8e216a8a12b55d85612b5a103`. UI bu çalışmada geliştirilmedi.

## Auth akışı

Gateway /api/v1/auth/csrf → login → gerekirse MFA enroll/verify/recover → session. credentials: include; CSRF değerini yanıt gövdesinden al, login/refresh/MFA sonrasında tazele. Dar cookie path nedeniyle UI document.cookie okuyabiliyor varsayma. Ayrıntılar: [staff-v1](../staff-contract.md).

Refresh sekmeler arası single-flight olmalı; 10 sn grace ikinci credential seti vermez. Activity polling değil kullanıcı etkileşimidir. 401 giriş/refresh; 403 rol/CSRF; 409 conflict; 429 Retry-After; timeout sonucu bilinmiyorsa kör POST retry yok. Parola sıfırlama yalnız MFA kurulmuş ADMIN; recovery kodları yalnız bir kez gösterilir.

AuthClient, giriş/MFA/reset ekranları ve tarayıcı E2E henüz yok. Guest QR fragmenti staff auth akışından ayrıdır; transport/policy onayı gerekir. Backend olmadan sahte başarılı işlem gösterilmez.

## Marka görsellerinden doğrulanan renkler

| Kullanım | Renk |
|---|---|
| Turuncu vurgu / gradient | #FFA63D / #F0460A |
| Koyu arka plan | #0B1220 / #1B2038 |
| Panel | #141A2E |
| İkincil yüzey | #1E2542 |
| Sınır | #2A3150 |
| Ana metin | #F8FAFC |
| İkincil metin | #CBD5E1 |
| Soluk metin | #94A3B8 |

Kaynak: assets/git-images/restofy-ekip.svg ve restofy-teknoloji.svg gradient/fill/stroke değerleri. frontend/src/app/globals.css yalnız Tailwind importu içeriyor; bu renkler uygulanmış CSS tokenları veya onaylı light/dark UI sistemi değildir.

Öneri: frontend fazında bu değerleri semantik tokenlara dönüştür; hata/başarı/durum renklerini ayrı, kontrastı ölçülmüş ve metinle destekli tasarla. Marka turuncusu her zeminde erişilebilir metin rengi varsayılmaz. TR/EN, WCAG 2.2 AA, reduced-motion ve noindex kuralları docs/web-rules.md ile korunmalı.

Kabul: browser cookie/CSRF, iki sekme refresh, 15/60 dk idle, yanlış rol/MFA, ağ kopması, klavye/ekran okuyucu ve renk kontrastı. Bu teslimde tarayıcı akışı DOĞRULANMADI.
