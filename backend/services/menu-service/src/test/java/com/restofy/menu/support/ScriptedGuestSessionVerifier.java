package com.restofy.menu.support;

import com.restofy.menu.application.security.GuestSessionVerification;
import com.restofy.menu.application.security.GuestSessionVerifier;
import com.restofy.menu.application.security.GuestToken;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only stand-in for QR's guest-session verification (lives in src/test, never packaged). It answers what a test
 * scripted per credential and records what it was asked, so a test can prove QR was not asked at all, or asked with
 * exactly the cookie value. It is not a model of QR: no real QR contract exists yet (qr-request-01).
 */
public final class ScriptedGuestSessionVerifier implements GuestSessionVerifier {

    private final Map<String, GuestSessionVerification> answers = new ConcurrentHashMap<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private volatile boolean failing;
    private volatile boolean silent;

    public void answer(String token, GuestSessionVerification verification) {
        answers.put(token, verification);
    }

    /** Every call throws, like a client that lost its connection. */
    public void fail() {
        failing = true;
    }

    /** Every call returns {@code null}, a contract violation Menu must not read as a success. */
    public void answerNothing() {
        silent = true;
    }

    public void reset() {
        answers.clear();
        asked.clear();
        failing = false;
        silent = false;
    }

    /** The credentials the verifier was asked about, in order. */
    public List<String> asked() {
        return List.copyOf(asked);
    }

    @Override
    public GuestSessionVerification verify(GuestToken token) {
        asked.add(token.value());
        if (failing) {
            throw new IllegalStateException("QR connection lost for " + token.value());
        }
        if (silent) {
            return null;
        }
        return answers.getOrDefault(token.value(), new GuestSessionVerification.Rejected());
    }
}
