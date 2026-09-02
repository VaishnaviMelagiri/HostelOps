package com.hostelops.claim;

import com.hostelops.claim.dto.BedActionResultDto;
import com.hostelops.claim.dto.BlockBedDto;
import com.hostelops.claim.dto.PendingQueueDto;
import com.hostelops.claim.dto.RejectRequestDto;
import com.hostelops.claim.dto.ResolveResultDto;
import com.hostelops.security.AppUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin half of the workflow.
 *
 * <p>Each endpoint names the specific permission it needs rather than "is an admin". Approving and
 * blocking are separate permissions even though one role currently holds both, so a future
 * maintenance-staff role could block beds without also gaining the power to allocate them.
 *
 * <p>The acting admin's id always comes from the authenticated principal, never the request body -
 * an audit trail nobody can forge by editing JSON.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminClaimController {

    private final AdminClaimService adminClaimService;

    public AdminClaimController(AdminClaimService adminClaimService) {
        this.adminClaimService = adminClaimService;
    }

    /**
     * The pending queue, oldest first, with student identities.
     *
     * <p>This is the only endpoint in the whole API that shows one person's identity to another.
     * An admin cannot approve a blank request, so the disclosure is necessary - and confining it
     * here means there is exactly one place to review rather than several.
     */
    @GetMapping("/requests")
    @PreAuthorize("hasAuthority('REQUEST_QUEUE_READ')")
    public PendingQueueDto pendingQueue(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "25") int size) {
        return adminClaimService.pendingQueue(page, size);
    }

    @PostMapping("/requests/{requestId}/approve")
    @PreAuthorize("hasAuthority('REQUEST_APPROVE')")
    public ResolveResultDto approve(@AuthenticationPrincipal AppUserPrincipal admin,
                                    @PathVariable Long requestId) {
        return adminClaimService.approve(admin.getId(), requestId);
    }

    /** Body is optional; a default reason is recorded when none is given. */
    @PostMapping("/requests/{requestId}/reject")
    @PreAuthorize("hasAuthority('REQUEST_REJECT')")
    public ResolveResultDto reject(@AuthenticationPrincipal AppUserPrincipal admin,
                                   @PathVariable Long requestId,
                                   @Valid @RequestBody(required = false) RejectRequestDto body) {
        RejectRequestDto reason = body != null ? body : new RejectRequestDto(null);
        return adminClaimService.reject(admin.getId(), requestId, reason.reasonOrDefault());
    }

    @PostMapping("/beds/{bedId}/block")
    @PreAuthorize("hasAuthority('BED_BLOCK')")
    public BedActionResultDto block(@AuthenticationPrincipal AppUserPrincipal admin,
                                    @PathVariable Long bedId,
                                    @Valid @RequestBody(required = false) BlockBedDto body) {
        BlockBedDto reason = body != null ? body : new BlockBedDto(null);
        return adminClaimService.blockBed(admin.getId(), bedId, reason.reasonOrDefault());
    }

    @PostMapping("/beds/{bedId}/unblock")
    @PreAuthorize("hasAuthority('BED_UNBLOCK')")
    public BedActionResultDto unblock(@AuthenticationPrincipal AppUserPrincipal admin,
                                      @PathVariable Long bedId,
                                      @Valid @RequestBody(required = false) BlockBedDto body) {
        String reason = body != null && body.reason() != null ? body.reason().trim() : "Returned to service";
        return adminClaimService.unblockBed(admin.getId(), bedId, reason);
    }
}
