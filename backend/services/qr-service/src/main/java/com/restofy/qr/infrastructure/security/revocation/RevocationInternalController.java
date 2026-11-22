package com.restofy.qr.infrastructure.security.revocation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** No transaction here: apply must commit before the receipt is signed. */
@RestController
public class RevocationInternalController {
    private final StaffRevocationStore store;
    private final RevocationServiceCredentials credentials;

    public RevocationInternalController(StaffRevocationStore store, RevocationServiceCredentials credentials) {
        this.store = store;
        this.credentials = credentials;
    }

    @PostMapping("/internal/q5/revocations")
    public Ack revoke(@RequestBody StaffRevocationStore.Command command, HttpServletRequest request) {
        if (request.getHeader("Cookie") != null || request.getHeader("Origin") != null
                || java.util.Collections.list(request.getHeaders("Authorization")).size() != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        credentials.verify(request.getHeader("Authorization"), command);
        var persisted = store.apply(command); // Returns through the Spring transaction proxy, after commit.
        return new Ack(credentials.receipt(command, persisted));
    }

    public record Ack(String receipt) {
        @Override public String toString() { return "Q5 durable acknowledgement"; }
    }

    @PostMapping("/internal/q5/challenge")
    public StaffRevocationStore.Challenge challenge(@RequestBody ChallengeRequest request, HttpServletRequest http) {
        requireServiceRequest(http);
        credentials.verifyBootstrap(http.getHeader("Authorization"), request.operationId(), request.restaurantId(), request.generation(),
                "staff:bootstrap:challenge", "challenge", null);
        return store.challenge();
    }

    @PostMapping("/internal/q5/bootstrap")
    public Ack bootstrap(@RequestBody StaffRevocationStore.Baseline baseline, HttpServletRequest http) {
        requireServiceRequest(http);
        credentials.verifyBootstrap(http.getHeader("Authorization"), baseline.operationId(), baseline.restaurantId(), baseline.generation(),
                "staff:bootstrap:write", StaffRevocationStore.baselineDigest(baseline), baseline.nonce());
        var receipt = store.installBaseline(baseline);
        return new Ack(credentials.baselineReceipt(baseline, receipt));
    }

    private static void requireServiceRequest(HttpServletRequest request) {
        if (request.getHeader("Cookie") != null || request.getHeader("Origin") != null
                || java.util.Collections.list(request.getHeaders("Authorization")).size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    public record ChallengeRequest(java.util.UUID operationId, java.util.UUID restaurantId, java.util.UUID generation) { }
}
