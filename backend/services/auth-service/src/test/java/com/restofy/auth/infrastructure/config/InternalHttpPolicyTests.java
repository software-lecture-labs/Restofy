package com.restofy.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

/** The eight cases ADR-ORTAK-001 asks every service to cover, plus the address checks that were already in place. */
@ExtendWith(OutputCaptureExtension.class)
class InternalHttpPolicyTests {
    private static final String PROPERTY = "test.service-url";

    private static MockEnvironment environment(String url, String allowed, String appEnv, String... profiles) {
        var environment = new MockEnvironment().withProperty(PROPERTY, url);
        if (allowed != null) {
            environment.setProperty(InternalHttpPolicy.ALLOWED_PROPERTY, allowed);
        }
        if (appEnv != null) {
            environment.setProperty(InternalHttpPolicy.ENVIRONMENT_PROPERTY, appEnv);
        }
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static URI address(MockEnvironment environment) {
        return InternalHttpPolicy.serviceAddress(environment, PROPERTY, "Test service");
    }

    // 1. Setting off: a Compose address over HTTP is refused, as before.
    @Test
    void httpIsRefusedWhileTheSettingIsOff() {
        assertThatThrownBy(() -> address(environment("http://auth-service:8080", null, "dev", "dev")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS is required");
        assertThatThrownBy(() -> address(environment("http://auth-service:8080", "false", "dev", "dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    // 2. Setting on and APP_ENV dev, local or test: accepted.
    @ParameterizedTest
    @ValueSource(strings = {"dev", "local", "test"})
    void httpToAComposeServiceIsAcceptedInADevelopmentEnvironment(String appEnv) {
        assertThat(address(environment("http://auth-service:8080", "true", appEnv, "dev")))
                .isEqualTo(URI.create("http://auth-service:8080"));
    }

    // 3. No dev profile, only a feature profile, but APP_ENV=dev: accepted.
    @Test
    void anIntegrationProfileWithoutDevIsAcceptedWhenAppEnvIsDev() {
        assertThat(address(environment("http://qr-service:8080", "true", "dev", "order-qr")))
                .isEqualTo(URI.create("http://qr-service:8080"));
        assertThat(address(environment("http://mail-service:8080", "true", "dev")))
                .isEqualTo(URI.create("http://mail-service:8080"));
    }

    // 4. Setting on in production: the service does not start, even for an HTTPS address.
    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "PROD"})
    void theSettingStopsTheServiceWhenAppEnvIsProduction(String appEnv) {
        assertThatThrownBy(() -> address(environment("https://mail.example.com", "true", appEnv, "dev")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed")
                .hasMessageNotContaining("mail.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production"})
    void theSettingStopsTheServiceWhenAProductionProfileIsActiveEvenWithAppEnvDev(String profile) {
        assertThatThrownBy(() -> address(environment("http://mail-service:8080", "true", "dev", "dev", profile)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed");
    }

    @Test
    void productionWithTheSettingOffStillUsesHttps() {
        assertThat(address(environment("https://mail.example.com", "false", "prod", "prod")))
                .isEqualTo(URI.create("https://mail.example.com"));
        assertThatThrownBy(() -> address(environment("http://mail-service:8080", null, "prod", "prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS is required");
    }

    // 5. Setting on but APP_ENV missing or not a development name: refused, as before.
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "staging", "Dev"})
    void httpIsRefusedWithoutANamedDevelopmentEnvironment(String appEnv) {
        assertThatThrownBy(() -> address(environment("http://auth-service:8080", "true", appEnv, "dev")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS is required");
        assertThatThrownBy(() -> address(environment("http://auth-service:8080", "true", null, "dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    // 6. Only Compose names and loopback qualify: IPs and dotted names are refused over HTTP.
    @ParameterizedTest
    @ValueSource(strings = {"http://10.0.0.5:8080", "http://auth.example.com", "http://2130706433:8080",
            "http://0x7f000001:8080", "http://Mail-Service:8080", "http://-mail:8080", "http://mail-:8080",
            "http://[fe80::1]:8080"})
    void httpIsRefusedForHostsThatAreNotComposeNamesOrLoopback(String url) {
        assertThatThrownBy(() -> address(environment(url, "true", "dev", "dev")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9", "http://127.0.0.1:9", "http://[::1]:9"})
    void loopbackIsAcceptedOverHttp(String url) {
        assertThat(address(environment(url, "true", "test", "test"))).isEqualTo(URI.create(url));
    }

    // 7. Any value other than true or false stops the service.
    @ParameterizedTest
    @ValueSource(strings = {"yes", "TRUE", "1", "", " true"})
    void anInvalidSettingStopsTheService(String value) {
        assertThatThrownBy(() -> address(environment("https://mail.example.com", value, "dev", "dev")))
                .isInstanceOf(IllegalStateException.class).hasMessage(InternalHttpPolicy.SETTING + " must be true or false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user:secret@mail-service:8080", "http://mail-service:8080?x=1",
            "http://mail-service:8080#x", "ftp://mail-service:8080", "mail-service:8080", "http://mail service"})
    void malformedAddressesAreRefusedWithoutEchoingThem(String url) {
        assertThatThrownBy(() -> address(environment(url, "true", "dev", "dev")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
    }

    @Test
    void httpsNeedsNoSettingAndKeepsCertificateChecksToTheClient() {
        assertThat(address(environment("https://mail.example.com", null, null))).isEqualTo(URI.create("https://mail.example.com"));
    }

    @Test
    void anAcceptedHttpAddressIsAnnouncedOnceWithTheSettingNameAndOrigin(CapturedOutput output) {
        address(environment("http://mail-service:8080", "true", "dev", "dev"));
        assertThat(output).contains("internal_http=ENABLED setting=INTERNAL_HTTP_ALLOWED address=http://mail-service:8080");
        address(environment("https://mail.example.com", "true", "dev", "dev"));
        assertThat(output.toString().split("internal_http=ENABLED", -1)).hasSize(2);
    }
}
