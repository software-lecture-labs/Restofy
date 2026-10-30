# Frontend bağlantısı ve mevcut marka paleti

Durum: 30 Ekim 2026 ilk `/login` sayfası uygulandı; landing page henüz yapılmadı.
Görsel referans: iki sütunlu Epidemic Sound giriş düzeni; Restofy paleti ve özgün servis illüstrasyonu.
Üst marka yazısı Signal Type fikrinden esinlenen kısa CSS reveal kullanır; üçüncü taraf bileşen
kodu/paketi eklenmedi. Sağ alan kullanıcı videosuyla sonraki adımda değiştirilebilir.

Sorumlu: Auth sözleşmesi ve ilk login UI Kerem. Önceki incelemenin kaynak SHA'sı:
`9e0fd4cd3eaddff8e216a8a12b55d85612b5a103`; güncel UI durumu aşağıdadır.

## İlk sayfanın kapsamı

- `app/(auth)/login/page.tsx`: route/çevirili metadata ve noindex. Landing için `/` değiştirilmedi.
- `features/auth`: giriş görünümü, dekoratif sahne, Auth DTO/client ve ilgili testler.
- `shared/lib/api`: Gateway transport, no-store, credentials include, timeout ve güvenli HTTP hata türü.
- `shared/components/brand`: tekrar kullanılabilir Restofy yazısı; `globals.css`: marka tokenları.
- TR/EN metinler mevcut next-intl cookie/action düzeninde. Mobilde sahne gizlenir, marka küçülür.
- Parola görünürlüğü, çift gönderim engeli, odaklanan hata, 401/403/409/429/kesinti ayrımı vardır.
- CSRF yanıt gövdesinden alınır; login sonrası binding yenilenir, session sunucudan tekrar okunur.
  Logout taze CSRF kullanır. JWT tarayıcı deposuna yazılmaz; mutasyon otomatik tekrarlanmaz.
- MFA_PENDING tam giriş olarak sunulmaz. MFA kurulum/kod, parola sıfırlama, operasyon paneli,
  refresh single-flight ve activity yaşam döngüsü henüz bu ilk sayfaya eklenmedi.
- Var olan `NEXT_PUBLIC_API_BASE_URL` Gateway adresi kullanılır; boşsa aynı origin `/api/v1` beklenir.
  Yerel gerçek bağlantıda frontend/Gateway aynı site host adını kullanmalı ve frontend origin'i
  backend'in mevcut CORS allowlist'inde bulunmalıdır. Yeni ENV veya backend ayarı eklenmedi.
- Next config'teki Tailwind loader yalnız `globals.css` dosyasına daraltıldı; `*.css` kuralının
  CSS Modules sınıflarını kaybettirmesi gerçek tarayıcıda görüldü ve düzeltildi.

Son toplu doğrulama: TypeScript, hedefli ESLint ve Next üretim build başarılı;
38 Vitest testi, 8 Playwright testi başarılı. Coverage satır %95,45 / branch %92,79 /
statement %93,54 / function %88,88; mevcut %80 eşikleri korunur.
Playwright gerçek tarayıcı kullanır; Auth yanıtları stub'dır, gerçek backend/SMTP E2E değildir.
İlk tarayıcı koşusundaki CSS loader ve Next route-announcer locator sorunları giderildi.
Loglar ve masaüstü/mobil ekran görüntüleri Git dışı `tmp/auth-quality/` ve
`frontend/test-results/playwright/artifacts/` altındadır. Uzak işlem yapılmadı.

## Auth akışı

Gateway /api/v1/auth/csrf → login → gerekirse MFA enroll/verify/recover → session. credentials: include; CSRF değerini yanıt gövdesinden al, login/refresh/MFA sonrasında tazele. Dar cookie path nedeniyle UI document.cookie okuyabiliyor varsayma. Ayrıntılar: [staff-v1](../staff-contract.md).

Refresh sekmeler arası single-flight olmalı; 10 sn grace ikinci credential seti vermez. Activity polling değil kullanıcı etkileşimidir. 401 giriş/refresh; 403 rol/CSRF; 409 conflict; 429 Retry-After; timeout sonucu bilinmiyorsa kör POST retry yok. Parola sıfırlama yalnız MFA kurulmuş ADMIN; recovery kodları yalnız bir kez gösterilir.

Giriş client'ı ve login ekranı vardır; MFA/reset ekranları sonraki adımdır. Guest QR fragmenti
staff auth akışından ayrıdır; transport/policy onayı gerekir. Backend olmadan sahte başarılı işlem gösterilmez.

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

Kalan kabul: gerçek backend cookie/CSRF, iki sekme refresh, 15/60 dk idle, gerçek MFA ve
ekran okuyucu/cihaz taraması. İlk sayfanın masaüstü/mobil reflow, dil kalıcılığı,
reduced-motion, parola görünürlüğü ve stub hata davranışı Playwright ile doğrulandı.
