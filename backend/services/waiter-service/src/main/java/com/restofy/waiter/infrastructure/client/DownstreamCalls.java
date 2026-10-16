package com.restofy.waiter.infrastructure.client;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs one downstream call and translates every failure into a {@link DependencyException}. A request is
 * never repeated here: automatic retries of non-idempotent calls are forbidden by docs/SECURITY.md.
 */
public final class DownstreamCalls {

    private static final Logger log = LoggerFactory.getLogger(DownstreamCalls.class);

    private DownstreamCalls() {
    }

    /** The call must produce a body; an empty body or a 404 is a failure. */
    public static <T> T execute(String dependency, Supplier<T> call) {
        try {
            return requireBody(dependency, call.get());
        } catch (RestClientException error) {
            throw translate(dependency, error);
        }
    }

    /** A 404 is a legitimate "not found" answer for lookups by id. */
    public static <T> Optional<T> executeOptional(String dependency, Supplier<T> call) {
        try {
            return Optional.of(requireBody(dependency, call.get()));
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) return Optional.empty();
            throw translate(dependency, error);
        } catch (RestClientException error) {
            throw translate(dependency, error);
        }
    }

    private static <T> T requireBody(String dependency, T body) {
        if (body == null) {
            throw new DependencyException(dependency, DependencyFailure.BAD_RESPONSE,
                    dependency + " service returned an empty body");
        }
        return body;
    }

    private static DependencyException translate(String dependency, RestClientException error) {
        DependencyFailure failure = classify(error);
        log.warn("Downstream '{}' call failed: {} ({})", dependency, failure, error.getClass().getSimpleName());
        String message = switch (failure) {
            case TIMEOUT -> dependency + " service did not answer in time";
            case UNAVAILABLE -> dependency + " service is unavailable";
            case REJECTED -> dependency + " service rejected the request from the waiter service";
            case BAD_RESPONSE -> dependency + " service returned an unusable response";
        };
        return new DependencyException(dependency, failure, message, error);
    }

    private static DependencyFailure classify(RestClientException error) {
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 401 || status == 403) return DependencyFailure.REJECTED;
            return status >= 500 ? DependencyFailure.UNAVAILABLE : DependencyFailure.BAD_RESPONSE;
        }
        if (hasCause(error, HttpTimeoutException.class) || hasCause(error, SocketTimeoutException.class)) {
            return DependencyFailure.TIMEOUT;
        }
        if (error instanceof ResourceAccessException) return DependencyFailure.UNAVAILABLE;
        if (hasCause(error, IOException.class) && !hasCause(error, HttpMessageNotReadableException.class)) {
            return DependencyFailure.UNAVAILABLE;
        }
        return DependencyFailure.BAD_RESPONSE;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }
}
