package com.restofy.order.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata. The docs endpoints themselves are disabled unless SWAGGER_ENABLED=true. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "Restofy Order Service", version = "v1"))
@SecurityScheme(name = "staff", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE, paramName = "restofy_access",
        description = "Auth staff access cookie in order-auth. Writes require an allowed Origin, restofy_csrf and "
                + "the matching X-CSRF-TOKEN header. Dev/test mock requests use Authorization: Staff <credential>.")
@SecurityScheme(name = "service", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER, paramName = "Authorization",
        description = "Service <credential>: an opaque credential of a calling service (Kitchen, Payment); internal endpoints only")
@SecurityScheme(name = "guestSession", type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "Opaque QR guest session token issued by the QR Service")
public class OpenApiConfiguration {
}
