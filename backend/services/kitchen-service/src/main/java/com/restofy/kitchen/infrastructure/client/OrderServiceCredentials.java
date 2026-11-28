package com.restofy.kitchen.infrastructure.client;

import java.util.UUID;

/**
 * Supplies the credential for ONE call to the Order service: the value sent after {@code Authorization: Service }.
 * The production implementation is {@link OrderRequestSigner}.
 *
 * <p>The arguments describe exactly the request that is about to be sent, so an implementation can bind its credential
 * to it. {@code body} is a copy. The result must never be logged.
 */
@FunctionalInterface
public interface OrderServiceCredentials {

	String credentialFor(UUID restaurantId, String path, byte[] body);
}
