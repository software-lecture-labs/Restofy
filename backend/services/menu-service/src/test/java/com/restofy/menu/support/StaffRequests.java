package com.restofy.menu.support;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Test-only request helpers for the browser proof of staff mutations (lives in src/test, never packaged). They imitate
 * what the Restofy UI sends after Auth issued its CSRF cookie: the cookie {@code restofy_csrf}, the same value in the
 * header {@code X-CSRF-TOKEN} and an allowlisted {@code Origin}. Nothing here relaxes a check; a test that wants a
 * refused mutation builds the broken part by hand.
 */
public final class StaffRequests {

    public static final String CSRF_COOKIE = "restofy_csrf";
    public static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PROOF_PART_BYTES = 32;
    private static final long PROOF_LIFETIME_SECONDS = 600;

    private StaffRequests() { }

    /** A well-formed, live proof in Auth's {@code expiry.nonce.signature} shape (the signature is not Menu's to check). */
    public static String proof() {
        return proofExpiringAt(Instant.now().plusSeconds(PROOF_LIFETIME_SECONDS));
    }

    public static String proofExpiringAt(Instant expiry) {
        return expiry.getEpochSecond() + "." + part() + "." + part();
    }

    private static String part() {
        byte[] bytes = new byte[PROOF_PART_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Drop-in for Spring's {@code csrf()} on a staff path: the complete, valid browser proof of a mutation. */
    public static RequestPostProcessor csrf() {
        return request -> {
            String proof = proof();
            request.setCookies(withExtra(request.getCookies(), new Cookie(CSRF_COOKIE, proof)));
            request.addHeader(CSRF_HEADER, proof);
            request.addHeader("Origin", TestStaffTokens.ORIGIN);
            return request;
        };
    }

    /** The staff access cookie Auth would have set. */
    public static RequestPostProcessor accessCookie(String token) {
        return request -> {
            request.setCookies(withExtra(request.getCookies(), new Cookie(TestStaffTokens.COOKIE, token)));
            return request;
        };
    }

    private static Cookie[] withExtra(Cookie[] existing, Cookie added) {
        int size = existing == null ? 0 : existing.length;
        Cookie[] all = new Cookie[size + 1];
        if (size > 0) {
            System.arraycopy(existing, 0, all, 0, size);
        }
        all[size] = added;
        return all;
    }
}
