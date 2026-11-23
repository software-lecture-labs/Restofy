package com.restofy.order.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata. The docs endpoints themselves are disabled unless SWAGGER_ENABLED=true. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(info = @Info(title = "Restofy Order Service", version = "v1",
        description = "Multiple Authorization credentials return 400. A 401 challenge names GuestSession, Staff or "
                + "Service as appropriate. GuestSession uses the restofy_guest cookie. Guest mutations require "
                + "Origin and X-Guest-CSRF. Read-only GET and HEAD do not require a CSRF proof."))
@SecurityScheme(name = "staff", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE, paramName = "restofy_access",
        description = "Auth staff access cookie in order-auth. Writes require an allowed Origin, restofy_csrf and "
                + "the matching X-CSRF-TOKEN header. Dev/test mock requests use Authorization: Staff <credential>.")
@SecurityScheme(name = "service", type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER, paramName = "Authorization",
        description = "Service <credential>: credential of the service authorised for the internal endpoint (Kitchen, Payment or QR)")
@SecurityScheme(name = "guestSession", type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.COOKIE,
        paramName = "restofy_guest",
        description = "QR guest cookie. Mutations require an allowed Origin and matching X-Guest-CSRF proof.")
public class OpenApiConfiguration {
}
