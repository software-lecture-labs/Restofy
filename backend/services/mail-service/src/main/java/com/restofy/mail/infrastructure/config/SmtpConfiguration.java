package com.restofy.mail.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration(proxyBeanMethods=false)
public class SmtpConfiguration {
    @Bean Clock mailClock() { return Clock.systemUTC(); }

    @Bean public JavaMailSender mailSender(Environment env, MailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(env.getRequiredProperty("spring.mail.host"));
        int port = env.getRequiredProperty("spring.mail.port", Integer.class);
        if (port < 1 || port > 65535) { throw new IllegalStateException("Invalid SMTP configuration"); }
        sender.setPort(port);
        String username = env.getRequiredProperty("spring.mail.username");
        String password = env.getRequiredProperty("spring.mail.password");
        sender.setUsername(username); sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.from", settings.from());
        properties.setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        String mode = env.getRequiredProperty("restofy.mail.tls-mode");
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString("STARTTLS".equals(mode)));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString("STARTTLS".equals(mode)));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString("SMTPS".equals(mode)));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        for (String property : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
            int timeout = env.getRequiredProperty("restofy.mail." + property, Integer.class);
            if (timeout <= 0 || timeout > 30000) { throw new IllegalStateException("Invalid SMTP timeout"); }
            properties.setProperty("mail.smtp." + property, Integer.toString(timeout));
        }
        properties.setProperty("mail.debug", "false");
        return sender;
    }
}
