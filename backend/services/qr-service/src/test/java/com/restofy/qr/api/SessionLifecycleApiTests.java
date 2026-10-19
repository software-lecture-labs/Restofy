package com.restofy.qr.api;

import com.restofy.qr.api.controller.TableSessionLifecycleController;
import com.restofy.qr.api.exception.ApiExceptionHandler;
import com.restofy.qr.application.service.TableSessionClosureService;
import com.restofy.qr.domain.SessionClosure.Result;
import com.restofy.qr.domain.enums.*;
import com.restofy.qr.domain.exception.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone DTO/HTTP mapping only, never real Auth or production success E2E. */
class SessionLifecycleApiTests {
    private static MockMvc mvc(TableSessionClosureService service) {
        return MockMvcBuilders.standaloneSetup(new TableSessionLifecycleController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @ParameterizedTest @ValueSource(strings = {"close", "cleaned"})
    void mapsNoStoreLifecycleViewAndUsesTheCorrectVersion(String operation) throws Exception {
        var service = mock(TableSessionClosureService.class); UUID id = UUID.randomUUID(); UUID table = UUID.randomUUID();
        var result = new Result(id, table, TableSessionStatus.CLOSED, 2, Instant.EPOCH, Instant.EPOCH, UUID.randomUUID(), TableOperationalStatus.CLEANING, 7);
        if (operation.equals("close")) { when(service.close(id, 1)).thenReturn(result); }
        else { when(service.cleaned(id, 1)).thenReturn(result); }
        mvc(service).perform(post("/api/v1/qr/table-sessions/{id}/{operation}", id, operation)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.sessionId").value(id.toString())).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.tableVersion").value(7));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"version\":null}", "{\"version\":-1}", "{\"version\":\"invalid\"}", "", "{broken"})
    void invalidOrMissingVersionsNeverReachTheService(String body) throws Exception {
        var service = mock(TableSessionClosureService.class);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(new TableSessionLifecycleController(service))
                    .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator).build();
            for (String operation : new String[]{"close", "cleaned"}) {
                mvc.perform(post("/api/v1/qr/table-sessions/{id}/{operation}", UUID.randomUUID(), operation)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(strings = {"conflict", "unavailable"})
    void reportsSafeConflictOrUnavailableInsteadOfClaimingClosure(String failure) throws Exception {
        var service = mock(TableSessionClosureService.class); var id = UUID.randomUUID();
        when(service.close(id, 0)).thenThrow(failure.equals("conflict") ? new SessionClosureConflictException() : new SessionClosureUnavailableException());
        mvc(service).perform(post("/api/v1/qr/table-sessions/{id}/close", id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().is(failure.equals("conflict") ? 409 : 503))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
