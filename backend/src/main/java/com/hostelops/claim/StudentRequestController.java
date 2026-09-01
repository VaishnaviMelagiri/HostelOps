package com.hostelops.claim;

import com.hostelops.claim.dto.CancelResultDto;
import com.hostelops.claim.dto.ClaimDto;
import com.hostelops.claim.dto.CreateRequestDto;
import com.hostelops.claim.dto.MyAllocationDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.security.AppUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The student's half of the workflow.
 *
 * <p>Every endpoint takes the student's identity from the authenticated principal, never from the
 * request body. A student cannot request a bed "as" someone else, because there is no field in
 * which to say who they are - the server already knows.
 */
@RestController
@RequestMapping("/api")
public class StudentRequestController {

    private final ClaimService claimService;

    public StudentRequestController(ClaimService claimService) {
        this.claimService = claimService;
    }

    /**
     * Requests a bed.
     *
     * <p>Returns 201 when this call created the request, 200 when it replayed one an identical
     * Idempotency-Key had already created. A double-clicked button is not an error.
     *
     * @param idempotencyKey optional; the frontend generates one UUID per click
     */
    @PostMapping("/requests")
    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    public ResponseEntity<ClaimDto> requestBed(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateRequestDto body) {

        UUID requestKey = parseKey(idempotencyKey);
        ClaimService.RequestOutcome outcome =
                claimService.requestBed(principal.getId(), body.bedId(), requestKey);

        return ResponseEntity
                .status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.claim());
    }

    /** Cancels the caller's own pending request. */
    @DeleteMapping("/requests/{requestId}")
    @PreAuthorize("hasAuthority('REQUEST_CANCEL_OWN')")
    public CancelResultDto cancel(@AuthenticationPrincipal AppUserPrincipal principal,
                                  @PathVariable Long requestId) {
        return claimService.cancelOwnRequest(principal.getId(), requestId);
    }

    /** What the caller currently holds. */
    @GetMapping("/me/allocation")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_OWN')")
    public MyAllocationDto myAllocation(@AuthenticationPrincipal AppUserPrincipal principal) {
        return claimService.myAllocation(principal.getId());
    }

    private static UUID parseKey(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            // Rejected rather than ignored. Silently dropping a malformed key would turn a
            // double-click into two real requests - the exact thing the header exists to prevent.
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Idempotency-Key must be a UUID.");
        }
    }
}
