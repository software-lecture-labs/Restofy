package com.restofy.menu.api.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A failure nobody planned for: the client learns nothing but a fixed message and an incident id, and the server log has
 * the incident id, the kinds of exception, the route template and the application frames, but never the exception message,
 * the real path or any submitted value.
 */
class UnexpectedErrorHandlingTests {

    private static final String SECRET = "secret-value-from-a-failing-statement";

    @RestController
    static class FailingController {

        @GetMapping("/boom/{reference}")
        String boom(@PathVariable String reference) {
            throw new IllegalStateException("boom " + SECRET + " for " + reference);
        }

        @GetMapping("/wrapped/{reference}")
        String wrapped(@PathVariable String reference) {
            throw new IllegalStateException("outer " + reference, new SQLException("detail Key (name)=(" + SECRET + ") already exists"));
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);

    @BeforeEach
    void captureLog() {
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        handlerLogger.detachAppender(appender);
    }

    private String logged() {
        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), "exactly one error line per failure");
        assertEquals("ERROR", events.get(0).getLevel().toString());
        assertNull(events.get(0).getThrowableProxy(), "the exception itself (and its message) is not attached to the log");
        return events.get(0).getFormattedMessage();
    }

    @Test
    void theClientGetsAFixedMessageAndAnIncidentIdAndNothingFromTheException() throws Exception {
        String body = mvc.perform(get("/boom/abc-ref")).andExpect(status().isInternalServerError()).andReturn().getResponse()
                .getContentAsString();

        assertEquals("An unexpected error occurred.", JsonPath.read(body, "$.detail"));
        assertEquals("Internal error", JsonPath.read(body, "$.title"));
        assertEquals(500, (int) JsonPath.read(body, "$.status"));
        UUID.fromString(JsonPath.read(body, "$.incidentId"));
        assertFalse(body.contains(SECRET), body);
        assertFalse(body.contains("IllegalStateException"), body);
        assertFalse(JsonPath.<String>read(body, "$.detail").contains("abc-ref"), "the message never repeats the requested value");
    }

    @Test
    void theLogHasTheIncidentTheKindsOfExceptionAndTheRouteButNoMessageAndNoRealPath() throws Exception {
        String body = mvc.perform(get("/boom/abc-ref")).andReturn().getResponse().getContentAsString();
        String line = logged();

        assertTrue(line.contains(JsonPath.<String>read(body, "$.incidentId")), "the log line can be found from the incident id");
        assertTrue(line.contains("java.lang.IllegalStateException"), line);
        assertTrue(line.contains("GET /boom/{reference}"), "the route template, not the real path: " + line);
        assertFalse(line.contains(SECRET), line);
        assertFalse(line.contains("abc-ref"), line);
    }

    @Test
    void theRootCauseKindIsLoggedWithoutItsMessage() throws Exception {
        mvc.perform(get("/wrapped/abc-ref")).andExpect(status().isInternalServerError());
        String line = logged();
        assertTrue(line.contains("root cause java.sql.SQLException"), line);
        assertFalse(line.contains(SECRET), "a database detail can hold a value and is never logged: " + line);
        assertFalse(line.contains("already exists"), line);
    }

    @Test
    void theApplicationFramesThatPointAtTheFailureAreLogged() throws Exception {
        mvc.perform(get("/boom/x")).andExpect(status().isInternalServerError());
        assertTrue(logged().contains("UnexpectedErrorHandlingTests$FailingController.boom:"), "where it happened, without any message");
    }

    @Test
    void everyFailureGetsItsOwnIncidentId() throws Exception {
        String first = JsonPath.read(mvc.perform(get("/boom/a")).andReturn().getResponse().getContentAsString(), "$.incidentId");
        String second = JsonPath.read(mvc.perform(get("/boom/a")).andReturn().getResponse().getContentAsString(), "$.incidentId");
        assertNotEquals(first, second);
    }
}
