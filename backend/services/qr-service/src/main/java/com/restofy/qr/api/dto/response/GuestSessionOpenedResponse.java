package com.restofy.qr.api.dto.response;

/** Public body of a guest open. The credential travels only in the HttpOnly cookie, never in JSON. */
public record GuestSessionOpenedResponse(TableSessionResponse tableSession, GuestSessionResponse guestSession) {
    public static GuestSessionOpenedResponse from(OpenedGuestSessionResponse opened) {
        return new GuestSessionOpenedResponse(opened.tableSession(), opened.guestSession());
    }
}
