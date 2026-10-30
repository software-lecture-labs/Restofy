package com.restofy.auth.api.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthExceptionHandlerTests {
    private static final String SENSITIVE = "staff.person@example.com";
    private final AuthExceptionHandler handler = new AuthExceptionHandler();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AuthExceptionHandler.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void unexpectedFailuresAreLoggedWithoutMessagesAndAnswerAsGenericServerError() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        var cause = new IllegalStateException("duplicate key (email)=(" + SENSITIVE + ")");
        var response = handler.failure(new RuntimeException("wrapper " + SENSITIVE, cause), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(String.valueOf(response.getBody())).doesNotContain(SENSITIVE);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("POST", "/api/v1/auth/login",
                    "java.lang.RuntimeException", "java.lang.IllegalStateException", "AuthExceptionHandlerTests")
                    .doesNotContain(SENSITIVE, "duplicate key");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void causeChainWalkingTerminatesAndNamesTheRootType() {
        var root = new IllegalArgumentException("root");
        var middle = new IllegalStateException("middle", root);
        assertThat(AuthExceptionHandler.safeSummary(new RuntimeException("top", middle)))
                .contains("type=java.lang.RuntimeException", "rootType=java.lang.IllegalArgumentException");
    }
}
