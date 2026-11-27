package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.TrustedOrderCaller;
import com.restofy.menu.application.security.TrustedOrderCallerProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * The production {@link TrustedOrderCallerProvider}: the Order service the signature filter verified for the CURRENT request
 * (its key, a restaurant that key is authorized for, and the same restaurant in the signed credential). The filter records it as
 * a server-side request attribute; a client cannot: request attributes are not headers, cookies or parameters, and only
 * {@link OrderCallerFilter} can write this one. Outside such a request, or when the filter refused the request, there is no
 * caller and every lookup is unauthenticated (fail-closed). Reading it is memory access only.
 */
@Component
class RequestOrderCallerProvider implements TrustedOrderCallerProvider {

    private static final String ATTRIBUTE = RequestOrderCallerProvider.class.getName() + ".caller";

    static void bind(jakarta.servlet.http.HttpServletRequest request, TrustedOrderCaller caller) {
        request.setAttribute(ATTRIBUTE, caller);
    }

    @Override
    public Optional<TrustedOrderCaller> current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof TrustedOrderCaller caller) {
            return Optional.of(caller);
        }
        return Optional.empty();
    }
}
