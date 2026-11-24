package com.restofy.qr.api.controller;

import com.restofy.qr.api.dto.request.TableCleanRequest;
import com.restofy.qr.api.dto.response.TableCleanResponse;
import com.restofy.qr.application.service.TableCleanService;
import com.restofy.qr.domain.exception.TableCleanException;
import com.restofy.qr.domain.exception.TableCleanException.Code;
import com.restofy.qr.infrastructure.security.WaiterLifecycleAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Table cleaning for the Waiter service. Closed service identity AND an active same-tenant WAITER/ADMIN staff
 * delegation, enforced by the Waiter chain; body IDs are expectations, never identity.
 */
@RestController
@Profile("qr-order-inbound")
@RequestMapping(InternalTableController.BASE)
@Tag(name = "Waiter table cleaning contract", description = "Closed service identity AND staff delegation boundary; not a Gateway public API.")
public class InternalTableCleanController {
    /** Route templates, also used by the Waiter security chain. */
    public static final String CLEAN_ROUTE = "/{tableId}/cleaned";
    public static final String STATUS_ROUTE = "/{tableId}/clean-commands/{commandId}";

    private final TableCleanService service;
    private final WaiterLifecycleAccess access;

    public InternalTableCleanController(TableCleanService service, WaiterLifecycleAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping(CLEAN_ROUTE)
    @Operation(summary = "Mark a cleaning table available (idempotent by command ID)")
    public ResponseEntity<TableCleanResponse> clean(@AuthenticationPrincipal UUID restaurantId, @PathVariable UUID tableId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TableCleanRequest request) {
        if (!UUID.fromString(key).equals(request.commandId()) || !tableId.equals(request.tableId())) {
            throw new IllegalArgumentException("Clean target or idempotency key does not match.");
        }
        var staff = access.requireStaff();
        if (!staff.restaurantId().equals(restaurantId) || !restaurantId.equals(request.restaurantId())
                || !staff.userId().equals(request.requestedByUserId())) {
            throw new AccessDeniedException("Table cleaning context does not match.");
        }
        var result = service.clean(restaurantId, request);
        if ("REJECTED".equals(result.status())) {
            throw new TableCleanException(Code.valueOf(result.rejectionCode()));
        }
        return noStore(result);
    }

    @GetMapping(STATUS_ROUTE)
    @Operation(summary = "Read the durable cleaning status for Waiter reconciliation")
    public ResponseEntity<TableCleanResponse> status(@AuthenticationPrincipal UUID restaurantId, @PathVariable UUID tableId,
            @PathVariable UUID commandId, @RequestParam(name = "restaurantId") UUID requestedRestaurantId) {
        if (!restaurantId.equals(requestedRestaurantId)) {
            throw new IllegalArgumentException("Restaurant does not match the verified service credential.");
        }
        return noStore(service.status(restaurantId, tableId, commandId));
    }

    private static ResponseEntity<TableCleanResponse> noStore(TableCleanResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
