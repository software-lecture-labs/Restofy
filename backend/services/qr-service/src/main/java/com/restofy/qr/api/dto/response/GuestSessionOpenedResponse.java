package com.restofy.qr.api.dto.response;

/**
 * Public body of a guest open. The credential travels only in the HttpOnly cookie, never in JSON. csrfToken is the
 * value the frontend sends as X-Guest-CSRF; it is not a credential.
 */
public record GuestSessionOpenedResponse(TableSessionResponse tableSession, GuestSessionResponse guestSession,
                                         String csrfToken) {
    public static GuestSessionOpenedResponse from(OpenedGuestSessionResponse opened, String csrfToken) {
        return new GuestSessionOpenedResponse(opened.tableSession(), opened.guestSession(), csrfToken);
    }
}
