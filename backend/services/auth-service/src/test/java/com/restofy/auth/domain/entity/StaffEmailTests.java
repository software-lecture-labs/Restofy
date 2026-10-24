package com.restofy.auth.domain.entity;

import java.util.Locale;
import java.util.stream.Stream;

import com.restofy.auth.domain.enums.StaffRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StaffEmailTests {
    @Test
    void normalizesIndependentlyOfTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(StaffEmail.normalize("  KITCHEN@EXAMPLE.COM  ")).isEqualTo("kitchen@example.com");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void definesExactlyThreeRoles() {
        assertThat(StaffRole.values()).containsExactly(StaffRole.ADMIN, StaffRole.WAITER, StaffRole.KITCHEN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-email", "@example.com", "a@", ".a@example.com",
            "a.@example.com", "a..b@example.com", "a@-bad.example", "a@bad-.example", "a b@example.com"})
    void rejectsInvalidEmail(String email) {
        assertThatIllegalArgumentException().isThrownBy(() -> StaffEmail.normalize(email))
                .withMessage("Invalid staff email");
    }

    @ParameterizedTest
    @MethodSource("longEmails")
    void rejectsLengthViolations(String email) {
        assertThatIllegalArgumentException().isThrownBy(() -> StaffEmail.normalize(email));
    }

    static Stream<Arguments> longEmails() {
        return Stream.of(Arguments.of("a".repeat(65) + "@example.com"),
                Arguments.of("a".repeat(301)),
                Arguments.of("a@" + "b".repeat(245) + ".example"));
    }
}
