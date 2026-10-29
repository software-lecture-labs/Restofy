package com.restofy.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Message.RecipientType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class GreenMailSmokeTests {
    @Test
    void sendsJakartaMimeMessageOnJava25WithoutExternalDelivery() throws Exception {
        ((Logger)LoggerFactory.getLogger("com.icegreen.greenmail")).setLevel(Level.OFF);
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1"); sender.setPort(smtp.getSmtp().getPort());
            var message = sender.createMimeMessage();
            message.setFrom("sender@example.test");
            message.setRecipients(RecipientType.TO, "admin@example.test");
            message.setSubject("SMTP compatibility", "UTF-8");
            message.setText("Test message", "UTF-8");
            sender.send(message);
            assertThat(Runtime.version().feature()).isEqualTo(25);
            assertThat(smtp.getReceivedMessages()).hasSize(1);
            assertThat(smtp.getReceivedMessages()[0].getContent()).isEqualTo("Test message");
        } finally { smtp.stop(); }
    }
}
