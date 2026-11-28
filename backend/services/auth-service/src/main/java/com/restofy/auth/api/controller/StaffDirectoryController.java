package com.restofy.auth.api.controller;

import java.util.UUID;

import com.restofy.auth.api.dto.request.AdministratorConfirmation;
import com.restofy.auth.api.dto.request.ChangeStaffRoleRequest;
import com.restofy.auth.api.dto.request.CreateStaffRequest;
import com.restofy.auth.api.dto.request.DeactivateStaffRequest;
import com.restofy.auth.api.dto.request.TemporaryPasswordRequest;
import com.restofy.auth.api.dto.response.StaffMemberResponse;
import com.restofy.auth.api.dto.response.StaffPageResponse;
import com.restofy.auth.application.service.StaffDirectoryService;
import com.restofy.auth.application.service.StaffDirectoryService.StepUp;
import com.restofy.auth.application.service.StaffDirectoryTransactions.Outcome;
import com.restofy.auth.infrastructure.security.ClientAddressResolver;
import com.restofy.auth.infrastructure.security.StaffAdministrationLimiter;
import com.restofy.auth.infrastructure.security.StaffJwtTokens.Identity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN staff management. Role changes and deactivations answer 200 once applied, or 202 while QR has not yet
 * acknowledged them; the client repeats a 202 request unchanged, with the same {@code operationId}. Requests that
 * grant ADMIN also carry the caller's own password and a second factor ({@code confirmation}).
 */
@RestController
@RequestMapping("/api/v1/auth/staff")
@PreAuthorize("hasAuthority('STAFF_SESSION') and hasRole('ADMIN')")
public class StaffDirectoryController {
    private final StaffDirectoryService directory;
    private final StaffAdministrationLimiter limiter;
    private final ClientAddressResolver clients;

    public StaffDirectoryController(StaffDirectoryService directory, StaffAdministrationLimiter limiter,
            ClientAddressResolver clients) {
        this.directory = directory;
        this.limiter = limiter;
        this.clients = clients;
    }

    @GetMapping
    public StaffPageResponse list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return StaffPageResponse.from(directory.list(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffMemberResponse create(@AuthenticationPrincipal Identity actor,
            @Valid @RequestBody CreateStaffRequest body, HttpServletRequest request) {
        limiter.check(actor.userId());
        return StaffMemberResponse.from(directory.create(actor, body.email(), body.password(), body.role(),
                stepUp(body.confirmation(), request)));
    }

    @PostMapping("/{id}/role")
    public ResponseEntity<StaffMemberResponse> changeRole(@AuthenticationPrincipal Identity actor,
            @PathVariable UUID id, @Valid @RequestBody ChangeStaffRoleRequest body, HttpServletRequest request) {
        limiter.check(actor.userId());
        return respond(directory.changeRole(actor, id, body.operationId(), body.role(), body.version(),
                stepUp(body.confirmation(), request)));
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<StaffMemberResponse> deactivate(@AuthenticationPrincipal Identity actor,
            @PathVariable UUID id, @Valid @RequestBody DeactivateStaffRequest body) {
        limiter.check(actor.userId());
        return respond(directory.deactivate(actor, id, body.operationId(), body.version()));
    }

    /**
     * Gives a WAITER or KITCHEN member a temporary password. The member's sessions end at once and the member must
     * choose a new password at the next sign-in. Answers 200; a repeat with the old version gets 409 STALE_VERSION.
     */
    @PostMapping("/{id}/password")
    public StaffMemberResponse assignTemporaryPassword(@AuthenticationPrincipal Identity actor, @PathVariable UUID id,
            @Valid @RequestBody TemporaryPasswordRequest body) {
        limiter.check(actor.userId());
        return StaffMemberResponse.from(directory.assignTemporaryPassword(actor, id, body.password(), body.version()));
    }

    private StepUp stepUp(AdministratorConfirmation confirmation, HttpServletRequest request) {
        return confirmation == null ? null : new StepUp(confirmation.password(), confirmation.totpCode(),
                confirmation.recoveryCode(), clients.resolve(request));
    }

    private static ResponseEntity<StaffMemberResponse> respond(Outcome outcome) {
        return ResponseEntity.status(outcome.completed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(StaffMemberResponse.from(outcome.member()));
    }
}
