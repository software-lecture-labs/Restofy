package com.restofy.mail;

import java.time.Instant;
import java.util.*;
import com.icegreen.greenmail.util.*;
import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.infrastructure.client.SmtpMailSender;
import com.restofy.mail.infrastructure.config.*;
import com.restofy.mail.support.MailTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mail.MailSendException;
import static org.assertj.core.api.Assertions.*;

class SmtpMailSenderTests {
    private MockEnvironment environment(int port,String mode) {
        MockEnvironment env=new MockEnvironment(); env.setActiveProfiles("test");
        return env.withProperty("restofy.mail.public-key",Base64.getEncoder().encodeToString(MailTestSupport.KEYS.getPublic().getEncoded()))
                .withProperty("restofy.mail.issuer","restofy-auth").withProperty("restofy.mail.from","sender@example.test")
                .withProperty("restofy.mail.tls-mode",mode).withProperty("spring.mail.host","127.0.0.1")
                .withProperty("spring.mail.port",Integer.toString(port)).withProperty("spring.mail.username","")
                .withProperty("spring.mail.password","").withProperty("restofy.mail.connectiontimeout","1000")
                .withProperty("restofy.mail.timeout","1000").withProperty("restofy.mail.writetimeout","1000");
    }
    @ParameterizedTest @CsvSource({"tr,ADMIN_PASSWORD_RESET","en,ADMIN_PASSWORD_RESET","tr,PASSWORD_CHANGED","en,PASSWORD_CHANGED"})
    void rendersBothLanguagesAsTextAndHtmlThroughRealFakeSmtp(String locale,String template) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.icegreen.greenmail")).setLevel(ch.qos.logback.classic.Level.OFF);
        GreenMail smtp=new GreenMail(new ServerSetup(0,"127.0.0.1",ServerSetup.PROTOCOL_SMTP)); smtp.start();
        try {
            var env=environment(smtp.getSmtp().getPort(),"NONE"); var settings=new MailSettings(env);
            var sender=new SmtpConfiguration().mailSender(env,settings);
            boolean reset=template.equals("ADMIN_PASSWORD_RESET");
            new SmtpMailSender(sender,settings).send(new PasswordResetMailRequest(UUID.randomUUID(),template,"admin@example.test",locale,
                    reset?"012345":null,reset?Instant.now().plusSeconds(900):null,Instant.now().plusSeconds(100)));
            assertThat(smtp.getReceivedMessages()).hasSize(1);
            var message=smtp.getReceivedMessages()[0];
            assertThat(message.getContentType()).contains("multipart");
            String content=GreenMailUtil.getBody(message);
            if(reset) {assertThat(content).contains("012345","15");}else{assertThat(content).doesNotContain("012345");}
            assertThat(content).doesNotContain("{{code}}","?");
            assertThat(message.getSubject()).contains(locale.equals("tr")?"parola":"password");
        }finally{smtp.stop();}
    }
    @Test void startTlsIsRequiredAndNeverDowngradesToPlainSmtp() {
        GreenMail smtp=new GreenMail(new ServerSetup(0,"127.0.0.1",ServerSetup.PROTOCOL_SMTP));smtp.start();
        try {
            var env=environment(smtp.getSmtp().getPort(),"STARTTLS");var settings=new MailSettings(env);
            var sender=new SmtpMailSender(new SmtpConfiguration().mailSender(env,settings),settings);
            var request=new PasswordResetMailRequest(UUID.randomUUID(),"PASSWORD_CHANGED","a@example.test","en",null,null,Instant.now().plusSeconds(100));
            assertThatThrownBy(()->sender.send(request))
                    .isInstanceOf(MailSendException.class);
            assertThat(smtp.getReceivedMessages()).isEmpty();
        }finally{smtp.stop();}
    }
    @Test void productionRejectsPlaintextAndMalformedKeysWithoutLeakingValues() {
        var env=environment(25,"NONE");env.setActiveProfiles("prod");
        assertThatThrownBy(()->new MailSettings(env)).hasMessage("Invalid or missing Mail configuration").hasNoCause();
        env.withProperty("restofy.mail.tls-mode","SMTPS").withProperty("restofy.mail.public-key","invalid");
        assertThatThrownBy(()->new MailSettings(env)).hasMessage("Invalid or missing Mail configuration").hasNoCause();
    }
}
