package com.restofy.qr.api.dto.response;

/** The value the frontend sends as X-Guest-CSRF. Not a credential; the guest token stays in the HttpOnly cookie. */
public record GuestCsrfResponse(String csrfToken) { }
