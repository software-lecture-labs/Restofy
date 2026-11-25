package com.restofy.menu.domain.exception;

/**
 * The customer menu cannot be served because the restaurant could not be verified: the verification source did not
 * answer usefully. Not a "not found": the customer's session may be fine, so the answer says "try again" and shows
 * nothing, and it is never served from an earlier answer.
 */
public class PublicMenuUnavailableException extends RuntimeException {

    public PublicMenuUnavailableException() {
        super("The menu is temporarily unavailable.");
    }
}
