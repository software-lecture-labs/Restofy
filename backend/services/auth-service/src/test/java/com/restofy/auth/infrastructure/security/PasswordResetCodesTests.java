package com.restofy.auth.infrastructure.security;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class PasswordResetCodesTests {
    @Test void requiresAnIndependentStrongKeyAndSeparatesPurposeAndChallenge() {
        for (String key:new String[]{"", "invalid", Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString(new byte[10])}) {
            MockEnvironment invalid=new MockEnvironment().withProperty("auth.reset.hmac-key",key);
            assertThatThrownBy(() -> new PasswordResetCodes(invalid))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Invalid or missing password reset configuration");
        }
        MockEnvironment missing=new MockEnvironment();
        assertThatThrownBy(() -> new PasswordResetCodes(missing)).isInstanceOf(IllegalStateException.class);
        PasswordResetCodes codes=new PasswordResetCodes(new MockEnvironment().withProperty("auth.reset.hmac-key",
                Base64.getEncoder().encodeToString("0123456789abcdefghijklmnopqrstuv".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        String digest=codes.digest("code:one","123456");
        assertThat(codes.matches(digest,codes.digest("code:one","123456"))).isTrue();
        assertThat(codes.matches(digest,codes.digest("code:two","123456"))).isFalse();
        assertThat(codes.matches(digest,codes.digest("cookie","123456"))).isFalse();
        assertThat(codes.token()).hasSize(43).isNotEqualTo(codes.token());
        assertThat(codes.code()).matches("[0-9]{6}");
    }
}
