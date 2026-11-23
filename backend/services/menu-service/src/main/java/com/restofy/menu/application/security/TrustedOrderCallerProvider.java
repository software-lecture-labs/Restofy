package com.restofy.menu.application.security;

import java.util.Optional;

/**
 * Source of the verified caller of the current Order catalog lookup. The production implementation is fail-closed: it
 * never returns a caller, so no lookup can be answered until a real service-to-service identity (Auth decision D-09) is
 * implemented and a provider backed by it replaces the default. Do not make this read a header.
 */
public interface TrustedOrderCallerProvider {

    Optional<TrustedOrderCaller> current();
}
