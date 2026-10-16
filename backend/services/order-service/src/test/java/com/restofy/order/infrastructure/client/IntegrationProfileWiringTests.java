package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.client.mock.InMemoryMenuCatalogAdapter;
import com.restofy.order.infrastructure.client.mock.InMemoryQrSessionAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Production safety: the MOCK QR/Menu adapters may only exist in the dev and test profiles. In every other profile
 * (including no profile at all) the fail-closed stand-ins are wired and nothing can be validated.
 */
class IntegrationProfileWiringTests {

    /**
     * The Maven build sets {@code spring.profiles.active=test} as a JVM property, so the environment here is a
     * {@link MockEnvironment} that ignores system properties and only knows the profiles given explicitly.
     */
    private ApplicationContextRunner contextFor(String... profiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(InMemoryQrSessionAdapter.class, InMemoryMenuCatalogAdapter.class,
                        UnavailableQrSessionAdapter.class, UnavailableMenuCatalogAdapter.class)
                .withInitializer(context -> {
                    MockEnvironment environment = new MockEnvironment();
                    if (profiles.length > 0) {
                        environment.setActiveProfiles(profiles);
                    }
                    context.setEnvironment(environment);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "staging", "qa"})
    void nonDevProfilesGetFailClosedAdaptersOnly(String profile) {
        contextFor(profile).run(context -> {
            assertEquals(1, context.getBeansOfType(QrSessionPort.class).size());
            assertEquals(1, context.getBeansOfType(MenuCatalogPort.class).size());
            assertInstanceOf(UnavailableQrSessionAdapter.class, context.getBean(QrSessionPort.class));
            assertInstanceOf(UnavailableMenuCatalogAdapter.class, context.getBean(MenuCatalogPort.class));
            assertEquals(0, context.getBeansOfType(InMemoryQrSessionAdapter.class).size());
            assertEquals(0, context.getBeansOfType(InMemoryMenuCatalogAdapter.class).size());
        });
    }

    @Test
    void noProfileAtAllAlsoGetsFailClosedAdapters() {
        contextFor().run(context -> {
            assertInstanceOf(UnavailableQrSessionAdapter.class, context.getBean(QrSessionPort.class));
            assertInstanceOf(UnavailableMenuCatalogAdapter.class, context.getBean(MenuCatalogPort.class));
        });
    }

    @Test
    void failClosedAdaptersNeverValidateAnythingAndReportUnavailable() {
        contextFor("prod").run(context -> {
            BusinessException qr = assertThrows(BusinessException.class,
                    () -> context.getBean(QrSessionPort.class).validateGuestSession("dev-mock-guest-token-a1"));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, qr.code());
            BusinessException menu = assertThrows(BusinessException.class,
                    () -> context.getBean(MenuCatalogPort.class).findProducts(UUID.randomUUID(), Set.of(UUID.randomUUID())));
            assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, menu.code());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void devAndTestProfilesGetTheMockAdapters(String profile) {
        contextFor(profile).run(context -> {
            assertInstanceOf(InMemoryQrSessionAdapter.class, context.getBean(QrSessionPort.class));
            assertInstanceOf(InMemoryMenuCatalogAdapter.class, context.getBean(MenuCatalogPort.class));
            assertEquals(0, context.getBeansOfType(UnavailableQrSessionAdapter.class).size());
        });
    }
}
