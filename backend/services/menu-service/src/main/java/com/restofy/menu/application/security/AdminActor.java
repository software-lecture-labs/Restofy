package com.restofy.menu.application.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller parameter of type {@link AuthenticatedActor} that must be a verified ADMIN. It is
 * resolved BEFORE the request body or any other parameter is read, so an unauthenticated caller gets 401 and
 * a caller without the ADMIN role gets 403 without ever learning which validation rules the body would face.
 * Put it first in the parameter list.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminActor {
}
