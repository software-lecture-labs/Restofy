package com.restofy.menu.infrastructure.security;

import com.restofy.menu.application.security.AdminAccess;
import com.restofy.menu.application.security.AdminActor;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.security.CurrentActorProvider;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link AdminActor} parameters from the trusted {@link CurrentActorProvider} only (never from a
 * header, query or body value) and enforces the ADMIN role. Spring resolves arguments in parameter order, so
 * with this parameter first, authentication (401) and authorization (403) run before request-body parsing
 * and Bean Validation (400). The production provider stays fail-closed: it never returns an actor.
 */
public class AdminActorArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentActorProvider actors;

    public AdminActorArgumentResolver(CurrentActorProvider actors) {
        this.actors = actors;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AdminActor.class)
                && AuthenticatedActor.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return AdminAccess.requireAdmin(actors.current().orElseThrow(UnauthenticatedException::new));
    }
}
