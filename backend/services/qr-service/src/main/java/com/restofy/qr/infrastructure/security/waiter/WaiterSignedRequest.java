package com.restofy.qr.infrastructure.security.waiter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A request whose bounded body was already read for signature verification, replayed to the controller. */
final class WaiterSignedRequest extends HttpServletRequestWrapper {
    static final int MAX_BODY_BYTES = 16 * 1024;
    private final ServletInputStream stream;

    WaiterSignedRequest(HttpServletRequest request, byte[] body) {
        super(request);
        var bytes = new ByteArrayInputStream(body);
        stream = new ServletInputStream() {
            @Override public int read() { return bytes.read(); }
            @Override public int read(byte[] buffer, int offset, int length) { return bytes.read(buffer, offset, length); }
            @Override public boolean isFinished() { return bytes.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Waiter internal requests use synchronous bodies");
            }
        };
    }

    @Override public ServletInputStream getInputStream() { return stream; }

    @Override public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    static String digest(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
