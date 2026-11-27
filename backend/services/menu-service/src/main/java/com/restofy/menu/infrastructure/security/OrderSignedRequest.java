package com.restofy.menu.infrastructure.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A request whose bounded body was already read to verify the signature, replayed unchanged to the controller. */
final class OrderSignedRequest extends HttpServletRequestWrapper {

    static final int MAX_BODY_BYTES = 8 * 1024;
    private final byte[] body;

    OrderSignedRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        var bytes = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return bytes.read(); }
            @Override public int read(byte[] buffer, int offset, int length) { return bytes.read(buffer, offset, length); }
            @Override public boolean isFinished() { return bytes.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Internal requests use synchronous bodies");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
