# mail-service ↔ Auth

Sahip: **Kerem**. Dal: `feature/auth-service`; incelenen SHA: `9e0fd4cd3eaddff8e216a8a12b55d85612b5a103`.
Durum: **MEVCUT Auth→Mail iç gönderim; genel servis modeliyle UYUMSUZ ayrıntılar**. İnceleme: 29 Ekim 2026; kod/dal incelemesi, canlı entegrasyon testi değildir.

## Mevcut sınır

feature/mail-service dalı yalnız iskelettir; gerçek Mail uygulaması Auth dalında ve devde. POST /internal/api/v1/mail/password-reset-jobs, Authorization: Bearer <service-JWT>, aud=restofy-mail, sub=auth-service, token_type=service, scope=password-reset:send, TTL=60 sn.

## Sağladığımız ve beklediğimiz

Auth personel kimliğini [staff-v1](../staff-contract.md) ile sağlar. Mevcut sözleşme korunur; MailSettings/MailServiceTokens ve Auth MailClient birlikte değerlendirilir. Staff access JWT kabul edilmez. Genel Authorization: Service /api/v1/internal/** modeli burada uygulanmış gibi yazılmaz. Ayrı servis anahtarı #6 ertelenmiştir.

## Client / olay / Saga

Auth reset commit → bellek içi, yeniden denemesiz MailClient → Mail iş kabulü → SMTP. Kod payloadı yalnız bellekte, DBde metadata; restart/kesintide kayıp mümkün. En çok 3 SMTP denemesi inbox teslim garantisi değildir; ADR-AUTH-001 geçerli.

## Yetki ve hata davranışı

İç POST yalnız MAIL_RESET_SEND yetkili servis kimliği. Browser CSRF istisnası yalnız bu servis credentiallı uçta; health hariç diğer yollar denyAll. Gerçek SMTP bağlı değil.

Personel sözleşmesinde geçersiz kimlik 401, yetersiz rol 403; dependency 503/UNKNOWN başarıya çevrilmez. Kaynak gizleme ve iş çatışması sahibinin mevcut 404/409 sözleşmesine uyar. Yerel JWT doğrulaması anlık global logout garantisi vermez.

## Kabul ve sorumlu

Auth imzalı servis tokenı; staff/MFA/guest token reddi; issuer/audience/scope/tür/süre; messageId tekrarı; SMTP geçici/kalıcı hata ve payload kaybı.

Tüketici adapterı servis sahibinin; Auth üretici sözleşmesi Kerem'in sorumluluğudur. Başka servisin kodu bu teslimde değiştirilmedi.

## Kaynaklar

- [security/MailSecurityConfiguration.java](https://github.com/software-lecture-labs/Restofy/blob/9e0fd4cd3eaddff8e216a8a12b55d85612b5a103/backend/services/mail-service/src/main/java/com/restofy/mail/infrastructure/security/MailSecurityConfiguration.java)
- [security/MailServiceTokens.java](https://github.com/software-lecture-labs/Restofy/blob/9e0fd4cd3eaddff8e216a8a12b55d85612b5a103/backend/services/mail-service/src/main/java/com/restofy/mail/infrastructure/security/MailServiceTokens.java)

Mevcut test kaynakları (varlıkları PASS kanıtı değildir):

- [MailIntegrationTests.java](https://github.com/software-lecture-labs/Restofy/blob/9e0fd4cd3eaddff8e216a8a12b55d85612b5a103/backend/services/mail-service/src/test/java/com/restofy/mail/MailIntegrationTests.java)
- [MailServiceApplicationTests.java](https://github.com/software-lecture-labs/Restofy/blob/9e0fd4cd3eaddff8e216a8a12b55d85612b5a103/backend/services/mail-service/src/test/java/com/restofy/mail/MailServiceApplicationTests.java)
