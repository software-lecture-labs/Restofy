package com.restofy.mail.infrastructure.config;

import java.util.Base64;
import java.util.stream.IntStream;

import com.restofy.mail.infrastructure.security.EncryptedMailPayloads;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MailPayloadConfiguration {
    private static final int KEY_BYTES = 32;
    private static final int MIN_DISTINCT_BYTES = 16;

    @Bean
    public EncryptedMailPayloads encryptedMailPayloads(Environment environment) {
        try {
            byte[] key = Base64.getDecoder().decode(environment.getRequiredProperty("restofy.mail.payload-key"));
            long distinct = IntStream.range(0, key.length).map(index -> Byte.toUnsignedInt(key[index])).distinct().count();
            if (key.length != KEY_BYTES || distinct < MIN_DISTINCT_BYTES) {
                throw new IllegalArgumentException();
            }
            return new EncryptedMailPayloads(key);
        } catch (RuntimeException _) {
            throw new IllegalStateException("Invalid MAIL_PAYLOAD_ENCRYPTION_KEY: expected Base64 of 32 varied random bytes");
        }
    }
}
