package com.restofy.order.infrastructure.security;

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

/** Supplies Spring MVC with the same bounded bytes covered by the QR signature. */
final class QrSignedRequest extends HttpServletRequestWrapper {
    static final int MAX_BODY_BYTES = 256 * 1024;

    private final ServletInputStream stream;

    QrSignedRequest(HttpServletRequest request, byte[] body) {
        super(request);
        var bytes = new ByteArrayInputStream(body);
        stream = new ServletInputStream() {
            @Override
            public int read() {
                return bytes.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return bytes.read(buffer, offset, length);
            }

            @Override
            public boolean isFinished() {
                return bytes.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Lifecycle endpoints use synchronous request bodies");
            }
        };
    }

    @Override
    public ServletInputStream getInputStream() {
        return stream;
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
