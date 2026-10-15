package com.restofy.order.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("restofy.rest-client")
public record RestClientProperties(int connectTimeoutMs, int readTimeoutMs) {

    public RestClientProperties {
        if (connectTimeoutMs <= 0) throw new IllegalArgumentException("connect-timeout-ms must be greater than zero");
        if (readTimeoutMs <= 0) throw new IllegalArgumentException("read-timeout-ms must be greater than zero");
    }
}
