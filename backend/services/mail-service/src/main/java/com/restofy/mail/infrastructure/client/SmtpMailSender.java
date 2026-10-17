package com.restofy.mail.infrastructure.client;

import java.nio.charset.StandardCharsets;
import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.infrastructure.config.MailSettings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender {
    private final JavaMailSender sender;
    private final MailSettings settings;
    public SmtpMailSender(JavaMailSender sender, MailSettings settings) { this.sender = sender; this.settings = settings; }

    public void send(PasswordResetMailRequest request) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(settings.from()); helper.setTo(request.recipient());
            boolean reset = "ADMIN_PASSWORD_RESET".equals(request.templateKey());
            boolean english = "en".equals(request.locale());
            String subject;
            if (reset) { subject = english ? "Restofy password reset code" : "Restofy parola sıfırlama kodu"; }
            else { subject = english ? "Your Restofy password was changed" : "Restofy parolanız değiştirildi"; }
            helper.setSubject(subject);
            String name = reset ? "password-reset" : "password-changed";
            helper.setText(render(name, request, "txt"), render(name, request, "html"));
            message.setHeader("X-Restofy-Message-Id", request.messageId().toString());
            sender.send(message);
        } catch (jakarta.mail.MessagingException | java.io.IOException _) {
            throw new MailPreparationException("Mail preparation unavailable");
        }
    }

    private static String render(String name, PasswordResetMailRequest request, String extension) throws java.io.IOException {
        try (var stream = new ClassPathResource("templates/mail/" + name + "-" + request.locale() + "." + extension).getInputStream()) {
            String template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return template.replace("{{code}}", request.code() == null ? "" : request.code());
        }
    }
}
