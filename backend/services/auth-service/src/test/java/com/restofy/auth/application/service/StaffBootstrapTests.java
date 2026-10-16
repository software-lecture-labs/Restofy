package com.restofy.auth.application.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.config.StaffBootstrapConfiguration;
import com.restofy.auth.infrastructure.config.StaffBootstrapProperties;
import com.restofy.auth.infrastructure.config.StaffBootstrapProperties.Account;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StaffBootstrapTests {
    private static final String TEST_PASSWORD = "Test-only-password-01";
    private static StaffPasswords passwords;
    private StaffUserRepository users;
    private JdbcTemplate jdbc;
    private StaffBootstrapService bootstrap;
    private StaffBootstrapProperties properties;
    private Map<String, StaffUser> stored;

    @BeforeAll
    static void passwordFixture() { passwords = new StaffPasswords(); }

    @BeforeEach
    void setup() {
        users = mock(StaffUserRepository.class);
        jdbc = mock(JdbcTemplate.class);
        bootstrap = new StaffBootstrapService(users, passwords, jdbc);
        properties = new StaffBootstrapProperties();
        stored = new HashMap<>();
        when(users.findByEmailNormalized(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(users.existsByRole(StaffRole.ADMIN)).thenAnswer(call -> stored.values().stream().anyMatch(user -> user.getRole() == StaffRole.ADMIN));
        when(users.saveAndFlush(any(StaffUser.class))).thenAnswer(call -> {
            StaffUser user = call.getArgument(0);
            stored.put(user.getEmailNormalized(), user);
            return user;
        });
    }

    @Test
    void disabledByDefaultAndDoesNotTouchDatabase() {
        bootstrap.initialize(properties, new String[]{"prod"});
        verifyNoInteractions(users, jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "staging", "unknown", ""})
    void refusesDevelopmentUsersOutsideAllowedProfiles(String profile) {
        enableDevelopment();
        String[] profiles = profile.isEmpty() ? new String[0] : new String[]{profile};
        assertThatThrownBy(() -> bootstrap.initialize(properties, profiles))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, jdbc);
    }

    @Test
    void refusesMixedProductionProfileOrProductionEnvironment() {
        enableDevelopment();
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"test", "prod"}))
                .isInstanceOf(IllegalStateException.class);
        properties.setEnvironment("prod");
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"dev"}))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "local", "test"})
    void createsThreeDistinctRolesOnlyOnExplicitOptIn(String profile) {
        enableDevelopment();
        bootstrap.initialize(properties, new String[]{profile});
        assertThat(stored.values()).extracting(StaffUser::getRole)
                .containsExactlyInAnyOrder(StaffRole.ADMIN, StaffRole.WAITER, StaffRole.KITCHEN);
        assertThat(stored.values()).allSatisfy(user -> {
            assertThat(user.isActive()).isTrue();
            assertThat(passwords.matches(TEST_PASSWORD, user.getPasswordHash())).isTrue();
        });
    }

    @Test
    void preservesExistingPasswordRoleIdentityAndInactiveState() {
        enableDevelopment();
        bootstrap.initialize(properties, new String[]{"dev"});
        StaffUser admin = stored.get("admin@example.com");
        admin.deactivate();
        String originalHash = admin.getPasswordHash();
        properties.getDevUsers().getAdmin().setPassword("Another-test-only-password");
        bootstrap.initialize(properties, new String[]{"dev"});
        assertThat(stored).hasSize(3);
        assertThat(stored.get("admin@example.com")).isSameAs(admin);
        assertThat(admin.getPasswordHash()).isEqualTo(originalHash);
        assertThat(admin.isActive()).isFalse();
    }

    @Test
    void rejectsMissingAndDuplicateConfigurationBeforeWriting() {
        properties.getDevUsers().setEnabled(true);
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"dev"})).isInstanceOf(IllegalArgumentException.class);
        enableDevelopment();
        properties.getDevUsers().getWaiter().setEmail(" ADMIN@example.com ");
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"dev"})).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, jdbc);
    }

    @Test
    void rejectsSimultaneousInitialAndDevelopmentBootstrap() {
        enableDevelopment();
        properties.getInitialAdmin().setEnabled(true);
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"dev"})).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, jdbc);
    }

    @Test
    void createsIndependentInitialAdminOnceAndPreservesItOnRestart() {
        enableInitial("owner@example.com");
        bootstrap.initialize(properties, new String[]{"prod"});
        StaffUser admin = stored.get("owner@example.com");
        String originalHash = admin.getPasswordHash();
        admin.deactivate();
        properties.getInitialAdmin().setPassword("Changed-test-only-password");
        bootstrap.initialize(properties, new String[]{"prod"});
        assertThat(stored).hasSize(1);
        assertThat(admin.getRole()).isEqualTo(StaffRole.ADMIN);
        assertThat(admin.getPasswordHash()).isEqualTo(originalHash);
        assertThat(admin.isActive()).isFalse();
    }

    @Test
    void refusesToPromoteAnExistingUserOrCreateAnotherInitialAdmin() {
        String hash = passwords.hash(TEST_PASSWORD);
        stored.put("waiter@example.com", new StaffUser("waiter@example.com", hash, StaffRole.WAITER));
        enableInitial("waiter@example.com");
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"prod"})).isInstanceOf(IllegalStateException.class);
        assertThat(stored.get("waiter@example.com").getRole()).isEqualTo(StaffRole.WAITER);
        stored.put("owner@example.com", new StaffUser("owner@example.com", hash, StaffRole.ADMIN));
        properties.getInitialAdmin().setEmail("another@example.com");
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"prod"})).isInstanceOf(IllegalStateException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void developmentRoleConflictFailsInsteadOfPromoting() {
        enableDevelopment();
        stored.put("admin@example.com", new StaffUser("admin@example.com", passwords.hash(TEST_PASSWORD), StaffRole.WAITER));
        assertThatThrownBy(() -> bootstrap.initialize(properties, new String[]{"dev"})).isInstanceOf(IllegalStateException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void bindsOptInConfigurationAndRunnerUsesActiveProfiles() {
        StaffBootstrapService mockService = mock(StaffBootstrapService.class);
        new ApplicationContextRunner().withUserConfiguration(StaffBootstrapConfiguration.class)
                .withBean(StaffBootstrapService.class, () -> mockService)
                .withPropertyValues("spring.profiles.active=local", "auth.bootstrap.dev-users.enabled=true",
                        "auth.bootstrap.dev-users.admin.email=admin@example.com")
                .run(context -> {
                    var bound = context.getBean(StaffBootstrapProperties.class);
                    assertThat(bound.getDevUsers().isEnabled()).isTrue();
                    assertThat(bound.getDevUsers().getAdmin().getEmail()).isEqualTo("admin@example.com");
                    context.getBean(org.springframework.boot.ApplicationRunner.class).run(new DefaultApplicationArguments());
                    verify(mockService).initialize(bound, new String[]{"local"});
                });
    }

    private void enableDevelopment() {
        var dev = properties.getDevUsers();
        dev.setEnabled(true);
        fill(dev.getAdmin(), "admin@example.com");
        fill(dev.getWaiter(), "waiter@example.com");
        fill(dev.getKitchen(), "kitchen@example.com");
    }

    private void enableInitial(String email) {
        properties.getInitialAdmin().setEnabled(true);
        fill(properties.getInitialAdmin(), email);
    }

    private void fill(Account account, String email) {
        account.setEmail(email);
        account.setPassword(TEST_PASSWORD);
    }
}
