# ADR-MENU-004 — Kalıcılık erişim standardı, sabit SQL ve ürün arama yaklaşımı

- **Durum:** Kabul edildi (geliştirme aşaması), katalog büyüyünce gözden geçirilecek
- **Tarih:** 2026-10-30
- **Sahip:** Menu (Merve)
- **İlgili kod:** `PublicProductSearchRepository`, `ProductAvailabilityCommandRepository`, `ProductSearchTerm`, `PublicProductSort`,
  `ProductOptionRepository`

## Bağlam

Servis içinde iki erişim biçimi yan yana büyümeye başladı: Spring Data JPA depoları ve elle yazılmış SQL. İnceleme geri bildirimi,
depolardan bazılarının JPA, bazılarının `JdbcTemplate` olmasının bir standartla bağlanmasını istemişti. Ayrıca dinamik biçimde
birleştirilen SQL (Sonar S2077) ve SQL ile Java'da tekrarlanan metin değerleri (hata riski) vardı.

## Karar

1. **Standart: Spring Data JPA.** Tablolar entity ve `JpaRepository` ile okunur/yazılır. `JdbcTemplate` ana kodda kullanılmaz.
2. **Yalnız JPA'nın ifade edemediği yerde native SQL, ve o da JPA katmanından:**
   - Müşteri ürün araması (Türkçe katlama, `strpos`, koşullu sıralama): `EntityManager` ile native sorgu.
   - Komut kimliği sahiplenme (`INSERT .. ON CONFLICT DO NOTHING`): `@Modifying @Query(nativeQuery = true)`.
3. **Native SQL derleme zamanı sabitidir.** Çalışma anında metne hiçbir şey eklenmez; isteğe bağlı filtreler ve sıralama bayrak +
   değer çiftleriyle (`:hasTerm`, `:term`, `:sortKey`, `:ascending`) açılıp kapanır, hiçbir parametre `NULL` olmaz. İstemci metni
   SQL'e girmez; sıralama alanı enum'dur ve izin verilen küme `PublicProductPageParameters` içindedir.
4. **Durum değerleri enum'dur.** Komut sonucu ve kaynağı `CommandOutcome` / `CommandSource` ile eşlenir, SQL'e parametre olarak geçer
   (komut INSERT'ünde sabit metin yok). Arama sorgusundaki sıralama anahtarları (`'NAME'`, `'BASE_PRICE'`, `'CREATED_AT'`) SQL'de metin
   olarak durur; üç alan ve iki yön de testle kapsanır, bir yazım hatası testte yakalanır.
5. **Arama:** yalnız ürün adı, 2–50 karakter, boş arama normal liste. Türkçe harfler tabanlarına katlanır (`ı İ → i`, `ş ğ ü ö ç`),
   birleşik işaretler atılır, katlama saklanan ad ve aranan metin için aynı SQL ifadesidir (veritabanı collation'ına bağlı değildir).
   LIKE joker karakteri yoktur (`strpos`). **Yeni indeks veya `pg_trgm`/full-text yoktur** (`docs/database.md`: arama indeksi
   ölçülmeden seçilmez).
6. **Sıralama:** ürünler katlanmış ad anahtarıyla sıralanır ("Şiş", "Sütlaç" ile aynı harf kümesinde ikinci harfe göre durur);
   seçenekler ise bellekte `tr-TR` `Collator` ile Türkçe alfabe sırasına göre sıralanır (ürün başına en çok 50 aktif seçenek).
   Her sıralamanın son anahtarı `id`'dir, sayfalar tekrar veya kayıp üretmez.

## Seçenekler ve ödünleşimler

| Seçenek | Artı | Eksi |
|---|---|---|
| **JPA + sabit native SQL** | Tek erişim biçimi, SQL enjeksiyon yüzeyi yok, statik analiz temiz | Koşullu sıralama `CASE` ile yapılır, indeks sırasından yararlanılmaz |
| `JdbcTemplate` yanında | `ON CONFLICT` ve null tiplemesi basit | İki erişim biçimi, farklı hata ve işlem davranışı |
| Dinamik SQL birleştirme | Esnek | S2077, bakım ve güvenlik riski |
| `pg_trgm`/full-text | Hızlı arama | Eklenti ve yetki gereksinimi, ölçülmeden erken karar |

## Sonuç ve izleme

- Bir restoranın kataloğu büyüdüğünde arama ve sıralama planı (`EXPLAIN`) ölçülür; gerekirse indeks bir sonraki boş migration
  numarasıyla eklenir.
- Ürün adı sıralamasında Türkçe sözlük sırası istenirse katlanmış anahtar yerine ICU collation değerlendirilir (veritabanı ICU
  desteğine bağlıdır).
