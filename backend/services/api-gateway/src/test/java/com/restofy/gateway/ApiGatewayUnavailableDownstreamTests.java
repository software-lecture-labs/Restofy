package com.restofy.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "logging.level.root=WARN")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiGatewayUnavailableDownstreamTests {

    private static final GatewayDownstreams DOWNSTREAMS = new GatewayDownstreams();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${local.server.port}")
    private int gatewayPort;

    @DynamicPropertySource
    static void downstreamAddresses(DynamicPropertyRegistry registry) {
        DOWNSTREAMS.register(registry);
    }

    @AfterAll
    static void closeHttpResources() {
        CLIENT.close();
        DOWNSTREAMS.close();
    }

    @Test
    void stoppedDownstreamReturnsAnActualFrameworkServerErrorWithoutRerouting() throws Exception {
        String reachableId = UUID.randomUUID().toString();
        var reachableRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(5)).header(GatewayDownstreams.REQUEST_ID, reachableId).GET().build();
        var reachableResponse = CLIENT.send(reachableRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, reachableResponse.statusCode());
        assertEquals(GatewayDownstreams.responseBody("auth-service"), reachableResponse.body());
        var delivered = DOWNSTREAMS.requests(reachableId);
        assertEquals(1, delivered.size());
        assertEquals("auth-service", delivered.getFirst().service());
        DOWNSTREAMS.stop("auth-service");
        String id = UUID.randomUUID().toString();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(5)).header(GatewayDownstreams.REQUEST_ID, id).GET().build();
        var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 500 && response.statusCode() < 600,
                "A closed downstream must produce a gateway/server error; observed " + response.statusCode());
        assertTrue(DOWNSTREAMS.requests(id).isEmpty(), "No fallback to another service is permitted");
        assertTrue(response.headers().firstValue("X-Downstream-Service").isEmpty());
        System.out.println("GATEWAY_UNAVAILABLE_DOWNSTREAM_HTTP_STATUS=" + response.statusCode());
    }
}
