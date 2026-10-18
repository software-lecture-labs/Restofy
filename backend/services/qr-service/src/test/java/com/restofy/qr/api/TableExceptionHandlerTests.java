package com.restofy.qr.api;

import com.restofy.qr.api.controller.TableController;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.TableManagementService;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

class TableExceptionHandlerTests {
    static Stream<Arguments> failures() {
        return Stream.of(Arguments.of(new IllegalStateException("private-internal-detail"), 500),
                Arguments.of(new AuthenticationCredentialsNotFoundException("private-internal-detail"), 401),
                Arguments.of(new AccessDeniedException("private-internal-detail"), 403),
                Arguments.of(new OptimisticLockingFailureException("private-internal-detail"), 409));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void providesProblemDetailsWithoutLeakingInternalErrors(RuntimeException exception, int expectedStatus) throws Exception {
        var service = mock(TableManagementService.class);
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(exception);
        var mvc = MockMvcBuilders.standaloneSetup(new TableController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/qr/tables/{id}", id)).andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(content().string(not(containsString("private-internal-detail"))));
    }
}
