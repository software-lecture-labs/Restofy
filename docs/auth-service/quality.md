# Auth kalite kapısı

## Normal çalışma

Java 25 ve çalışan Docker gerekir; yalnız geçici Testcontainers PostgreSQL kullanılır:

```powershell
mvn -B -ntp -f backend/pom.xml -pl services/auth-service -am clean verify
```

Auth POM'unda verify fazına bağlı iki kapı vardır. Ortak parent POM, pre-push veya diğer servis POM'ları değiştirilmedi. Dolayısıyla mevcut pre-push Maven verify çağrısı Auth analizlerini de çalıştırır; diğer servislere yeni kural yayılmaz.

Windows'ta Türkçe klasör adı/yerel kodlama nedeniyle agent exec dosyasını yanlış karakterli yola
yazabilir. Bu ortamda gözlendi; normal verify başarısı tek başına coverage raporu kanıtı sayılmadı.
Gerçek execution verisinden ayrı raporlama ve mevcut sınır [status.md](status.md) içinde kayıtlıdır.

| Araç | Sabit sürüm | Kapsam |
|---|---|---|
| Maven Checkstyle Plugin | 3.6.0 | Auth main + test Java |
| Checkstyle engine | 14.3.0 | Java 25; 140 karakter, tek ifade/satır, import düzeni ve gereksiz import |
| Maven PMD Plugin | 3.28.0 | Auth main + test Java, targetJdk=25 |
| PMD engine | Plugin'in sabit 7.17.0 sürümü | Aşağıdaki açık kural listesi |

Checkstyle yapılandırması: backend/services/auth-service/quality/checkstyle.xml.
PMD yapılandırması: backend/services/auth-service/quality/pmd.xml.

PMD kuralları: CloseResource, EmptyCatchBlock, BrokenNullCheck, ReturnFromFinallyBlock,
DoNotThrowExceptionInFinally, UnusedLocalVariable, UnusedPrivateField, UnusedPrivateMethod,
UnnecessaryFullyQualifiedName, AvoidUsingOctalValues, AvoidLiteralsInIfCondition.
Son kural if koşullarındaki açıklanmamış sayıları yakalar; her bağlamdaki her sayıyı yakaladığı iddia edilmez.
DB tek-satır geçiş kontrolü gibi değerler adlandırılmış sabitlerle açıklandı.

Static AssertJ/Mockito test DSL wildcard importları mevcut stil olarak açıkça kabul edilir; normal type wildcard importları kabul edilmez. Paket/import satırları 140 sınırından muaftır; kaynak dosya veya test klasörü analiz dışında değildir. Baseline, failOnViolation=false ve toplu suppression yoktur.

PMD kaynak yönetimi bulgusunda test executor'ü try-with-resources ile kapatıldı; mevcut shutdownNow finally davranışı korundu. HTTP, JWT, ENV, migration ve erişim politikası değişmedi.

## GitHub CI

Workflow: .github/workflows/auth-quality.yml. dev/main hedefli PR, feature/auth-service/dev/main push ve workflow_dispatch için hazırlanmıştır. Testcontainers çalıştıran Ubuntu + Temurin 25; contents:read, SHA sabitli Actions, credentials kalıcı değil. Gerçek ENV/SMTP/Sonar sırrı gerekmez. JUnit/JaCoCo/PMD/Checkstyle sonuçları başarısızlıkta da 7 gün saklanır.

Workflow dosyası yerelde hazırdır; push yapılmadı. **GitHub çalışması ve required-check ayarları DOĞRULANMADI / değiştirilmedi.** QR dalında ayrıca backend-verify workflow'u vardır; bu dal o workflow'u taşımadığı için üzerine yazılmadı. Birleşimde iki kontrol kapsamı ekipçe değerlendirilmelidir.

## Kanıt ve sınır

Geçici mini projede aynı sabit plugin/engine ve gerçek kural dosyalarıyla main/test ihlalleri oluşturulur: ihlalli kaynaklarda iki araç hata kodu ve gerçek violation raporu üretmeli; temiz kaynaklarda ikisi de başarılı olmalıdır. Deney uygulama kaynaklarına hata eklemez. Sonuçlar tmp/auth-quality/gate-proof.json ve probe/* altındadır, Git'e alınmaz.

Auth test toplamı, ArchUnit, coverage ve tam pre-push sonucu [status.md](status.md) içindedir. Tam pre-push başarısızsa yalnız Auth verify başarılı diye SAFE TO COMMIT denmez.

Windows Türkçe klasör yolunda JaCoCo için son yerel koşuda `jacoco.destFile` ve `jacoco.dataFile`
aynı, önceden mevcut olmayan ASCII geçici dosyaya CLI üzerinden yönlendirildi; aynı verify raporu üretti.
Bu parametreler test/kalite kurallarını atlamaz ve CI dosyasını değiştirmez.

```powershell
$coverageFile = Join-Path $env:TEMP ('restofy-auth-' + [guid]::NewGuid().ToString('N') + '.exec')
mvn -B -ntp -f backend/pom.xml -pl services/auth-service -am "-Djacoco.destFile=$coverageFile" "-Djacoco.dataFile=$coverageFile" clean verify
```

Resmî kaynaklar: [Maven PMD](https://maven.apache.org/plugins/maven-pmd-plugin/), [Checkstyle](https://checkstyle.org/), [Maven Checkstyle](https://maven.apache.org/plugins/maven-checkstyle-plugin/).
