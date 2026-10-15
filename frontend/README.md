# Restofy Frontend

Next.js 16 (App Router), React, TypeScript strict mode ve Tailwind CSS 4 kullanılır.
ESLint, React Compiler, Turbopack, `cacheComponents` ve `partialPrefetching`
mevcut yapılandırmayla korunur. `@/*` alias'ı `src/*` dizinine karşılık gelir.

## Yerel geliştirme

Backend ve PostgreSQL'i repository kökünde başlat:

```powershell
.\ops\env.cmd use dev
docker compose up --build -d
```

Frontend'i ayrı bir terminalde çalıştır:

```powershell
cd frontend
npm ci
if (-not (Test-Path .env.local)) { Copy-Item .env.example .env.local }
npm run dev -- --port 5000
```

Frontend: http://localhost:5000. Kod değişiklikleri Next.js Fast Refresh ile
uygulanır. Frontend Docker'da çalışmaz ve image rebuild gerektirmez.
Compose yalnızca PostgreSQL ile 13 backend mikroservisini içerir.

## ENV ve API Gateway

`frontend/.env.local` için gerekli public değişken:

```dotenv
NEXT_PUBLIC_API_BASE_URL=http://localhost:5050
```

Next.js ENV dosyalarını frontend proje kökünden okur; repository kökündeki
`.env.dev` otomatik yüklenmez. `.env.example` Git'te paylaşılır,
`.env.local` ise ignore edilir. `NEXT_PUBLIC_*` değerleri tarayıcıya açıldığından
gizli bilgi içermemelidir. ENV değiştiğinde geliştirme sunucusunu yeniden başlat.
Üretim build'inde public değerler build sırasında belirlenir.

Bütün backend API çağrıları API Gateway (`5050`) üzerinden yapılmalıdır.
Mikroservislerin `5051–5062` portlarına frontend'den doğrudan istek gönderilmez.
Bu iskelette API client, endpoint, interceptor veya token yönetimi bulunmaz.

## Feature-Based yapı

```text
src/
├── app/                  # Mevcut route, layout ve global stiller
├── features/
│   ├── admin/
│   ├── auth/
│   ├── kitchen/
│   ├── menu/
│   ├── notification/
│   ├── order/
│   ├── payment/
│   ├── qr/
│   ├── rating/
│   └── waiter/
└── shared/
    ├── components/ui/
    ├── config/
    ├── hooks/
    ├── lib/
    │   ├── api/
    │   └── i18n/             # TR/EN yapılandırması ve JSON çevirileri
    ├── types/
    └── utils/
```

Her feature ilgili iş alanının kodunu sahiplenir. Feature'lar birbirlerinin
iç implementasyonlarına doğrudan bağlanmaz; ihtiyaç oluştuğunda açık bir public
giriş noktası üzerinden iletişim kurar. `shared/` yalnızca gerçekten ortak
kullanılan kod içindir ve feature'lara bağımlı olmamalıdır. `app/` yönlendirme ve
sayfa birleştirme sorumluluğunu taşır.

`api-gateway` bir iş alanı değildir; `mail` ve `timer` için henüz arayüz ihtiyacı
yoktur. Bu nedenle bunların feature klasörleri oluşturulmamıştır. Boş yaprak
klasörlerde `.gitkeep` bulunur; ilk gerçek dosya eklendiğinde kaldırılır.
Feature alt klasörleri ve yeni modüller yalnızca ihtiyaç doğduğunda eklenir.

## Türkçe / İngilizce (i18n)

`next-intl` App Router ile yapılandırılmıştır. Çeviriler
`src/shared/lib/i18n/messages/tr.json` ve `en.json` dosyalarındadır.
Demo metinleri kaldırıldığı için iki dosya da şimdilik boş JSON nesnesidir.
Gerçek arayüz metinleri eklendiğinde iki dosyada aynı namespace ve anahtarlar
kullanılmalıdır. Ana rota (`src/app/page.tsx`) boş içerik döndürür.

Varsayılan dil `tr`'dir. `locale` cookie'si `tr` veya `en` olduğunda ilgili
çeviriler yüklenir; eksik/geçersiz değer Türkçeye döner. URL'ler değişmez.
HTML `lang` değeri ve Client Component provider'ı aynı dili kullanır.
Çeviri anahtarları ve dil değerleri TypeScript ile kontrol edilir.

Gerçek çeviri anahtarları eklendiğinde Server Component içinde `getTranslations`
(`next-intl/server`), Client Component içinde `useTranslations` (`next-intl`)
kullanılır.
Dil seçimi için bir Client Component'in event handler'ından
`@/shared/lib/i18n/actions` içindeki `setLocale("tr")` veya `setLocale("en")`
Server Action'ı çağrılabilir. Cookie bir yıl saklanır ve değişince Next.js
mevcut sayfa/layout'u yeniden render eder. Bu kurulumda dil seçici arayüz eklenmedi.

Cookie'ye bağlı HTML dili ilk sunucu yanıtında doğru üretilsin diye root layout
isteğe göre render edilir (`instant = false`); başlangıç sayfası artık statik
olarak üretilmez. `cacheComponents`, `partialPrefetching`, React Compiler ve
Turbopack ayarları korunmuştur. Tarih/saat formatları `Europe/Istanbul` kullanır.

## Kontroller

Frontend dizininde çalıştır:

```powershell
npm run lint
npm run typecheck
npm run test
npm run test:coverage
npm run build
npm run test:e2e:install
npm run test:e2e
```

Vitest ve React Testing Library altyapısı mevcut i18n davranışını test eder;
henüz iş kuralı testleri yoktur. V8 coverage bütün çalıştırılabilir `src/` kodunu
kapsar; declaration ve test dosyaları hariçtir. LCOV raporu `coverage/lcov.info`
konumunda üretilir. Manuel kontroller için [pre-push rehberine](../pre-push/pre-push.md),
bağımsız analiz için [SonarQube rehberine](../sonarqube/sonarqube.md) bak.

## Playwright tarayıcı ve HTTP testleri

İlk kurulumda `npm run test:e2e:install`, Chromium test tarayıcısını indirir.
Linux ortamında sistem kütüphaneleri eksikse `npx playwright install --with-deps chromium`
kullanılır. Tarayıcı kurulumu pre-push içinde otomatik indirilmez.

`npm run test:e2e`, önceden `npm run build` ile üretilmiş production build'ini
`127.0.0.1:5090` üzerinde açar. Test sunucusunu Playwright yönetir ve test sonunda kapatır;
mevcut sunucuya bağlanmaz. Port doluysa kontrol başarısız olur. Farklı bir port için
`PLAYWRIGHT_PORT` ortam değişkenini kullanabilirsiniz (1024–65535).

HTTP testi ana sayfanın HTTP 200 ve HTML yanıtını doğrular. Chromium testleri
sayfanın başlığını, varsayılan Türkçe dilini, TR/EN cookie davranışını, geçersiz
dilin Türkçeye dönmesini, yeniden yüklemede dilin korunmasını ve ilk dört senaryoda
tarayıcı JavaScript hatası olmamasını kontrol eder. İş ekranları eklendikçe onların
gerçek kullanıcı akışları da `e2e/` altına eklenmelidir.

Etkileşimli çalıştırma: `npm run test:e2e:ui`. JSON sonuçları
`test-results/playwright/results.json`, hata ekran görüntüleri ve trace dosyaları
`test-results/playwright/artifacts/`, HTML raporu `playwright-report/` altındadır.
Bu raporlar Git tarafından ignore edilir. Yapılandırma, [Playwright webServer](https://playwright.dev/docs/test-webserver)
ve [reporter](https://playwright.dev/docs/test-reporters) desteğini kullanır.
