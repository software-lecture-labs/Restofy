package com.restofy.kitchen.infrastructure.client;

import java.util.UUID;

/**
 * Supplies the credential for ONE call to the Order service: the value sent after {@code Authorization: Service }.
 * There is NO production implementation. How Kitchen proves to the Order service who it is has no agreed mechanism yet
 * (D-09): outside its dev and test profiles the Order service accepts no Kitchen credential. While no implementation
 * exists the {@code kitchen-order} profile refuses to start, so nothing is ever sent without one.
 *
 * <p>The arguments describe exactly the request that is about to be sent, so an implementation can bind its credential
 * to it. {@code body} is a copy. The result must never be logged.
 */
@FunctionalInterface
public interface OrderServiceCredentials {

	String credentialFor(UUID restaurantId, String path, byte[] body);
}
