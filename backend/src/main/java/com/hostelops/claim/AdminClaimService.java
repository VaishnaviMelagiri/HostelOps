package com.hostelops.claim;

import com.hostelops.claim.dto.BedActionResultDto;
import com.hostelops.claim.dto.PendingQueueDto;
import com.hostelops.claim.dto.PendingQueueRowDto;
import com.hostelops.claim.dto.ResolveResultDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.room.Bed;
import com.hostelops.room.BedRepository;
import com.hostelops.room.dto.BedStatus;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The admin half of the workflow: resolve requests, and take beds in and out of circulation.
 *
 * <p>Every state change here is a CONDITIONAL UPDATE - "change this row only if it is still in the
 * state I think it is in" - and then a branch on how many rows changed. Nothing reads a row,
 * decides, and writes back, because between the read and the write another admin, the student, or
 * the expiry sweep can change it. The condition is evaluated by the database at the moment of
 * writing, which is the only moment that can be authoritative.
 */
@Service
@Transactional
public class AdminClaimService {

    private static final Logger log = LoggerFactory.getLogger(AdminClaimService.class);

    /** Given to the student whose request is auto-rejected because their bed was blocked. */
    static final String BLOCKED_SYSTEM_REASON = "Bed taken out of circulation for maintenance";

    private final BedClaimRepository claimRepository;
    private final BedRepository bedRepository;
    private final UserRepository userRepository;

    public AdminClaimService(BedClaimRepository claimRepository,
                             BedRepository bedRepository,
                             UserRepository userRepository) {
        this.claimRepository = claimRepository;
        this.bedRepository = bedRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PendingQueueDto pendingQueue(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var found = claimRepository.findPendingQueue(pageable);
        Instant now = Instant.now();

        return new PendingQueueDto(
                found.getTotalElements(),
                pageable.getPageNumber(),
                pageable.getPageSize(),
                found.getContent().stream()
                        .map(row -> PendingQueueRowDto.from(row, now))
                        .toList());
    }

    /** PENDING to ALLOCATED. Idempotent. */
    public ResolveResultDto approve(Long adminId, Long requestId) {
        return resolve(adminId, requestId, ClaimStatus.ALLOCATED, null);
    }

    /** PENDING to REJECTED, which returns the bed to AVAILABLE. Idempotent. */
    public ResolveResultDto reject(Long adminId, Long requestId, String reason) {
        return resolve(adminId, requestId, ClaimStatus.REJECTED, reason);
    }

    /**
     * The shared body of approve and reject - they differ only in the target status and whether a
     * reason is recorded, so the concurrency handling exists once rather than twice.
     */
    private ResolveResultDto resolve(Long adminId, Long requestId,
                                     ClaimStatus target, String reason) {
        User admin = userRepository.getReferenceById(adminId);
        Instant now = Instant.now();

        int updated = (target == ClaimStatus.ALLOCATED)
                ? claimRepository.approveIfStillPending(requestId, admin, now)
                : claimRepository.rejectIfStillPending(requestId, admin, now, reason);

        if (updated == 1) {
            log.info("Admin {} set request {} to {}", adminId, requestId, target);
            return new ResolveResultDto(requestId, target, false, reason);
        }

        // Zero rows changed. Read the row back to say WHY, because the reasons are genuinely
        // different and an admin needs to be able to tell them apart.
        BedClaim claim = claimRepository.findByIdWithBed(requestId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                        "No such request.", Map.of("requestId", requestId)));

        if (claim.getStatus() == target) {
            // Already in exactly the state asked for - a second admin clicking the same button, or
            // a retried request. The intent is satisfied, so this is a 200, not an error. Crucially
            // it does NOT re-record the decision or fire a second notification.
            log.debug("Request {} was already {}", requestId, target);
            return new ResolveResultDto(requestId, target, true, claim.getDecisionReason());
        }

        // A DIFFERENT terminal state: the student cancelled, the sweep expired it, or the other
        // admin made the opposite decision. Telling the admin "done" here would be a lie about
        // something that never happened.
        throw new DomainException(ErrorCode.REQUEST_ALREADY_RESOLVED,
                "That request was already " + claim.getStatus().name().toLowerCase()
                        + " and cannot be " + target.name().toLowerCase() + ".",
                Map.of("requestId", requestId, "actualStatus", claim.getStatus().name()));
    }

    /**
     * Takes an AVAILABLE bed out of circulation.
     *
     * <p>Three things happen in ONE transaction, and the atomicity matters: if the insert failed
     * after the auto-reject committed, a student would have lost their request for a block that
     * never happened.
     * <ol>
     *   <li>Refuse outright if the bed is ALLOCATED - evicting an occupant is a separate workflow
     *       and deliberately out of scope.</li>
     *   <li>Auto-reject any PENDING request on the bed, with a system reason, so the student is
     *       told rather than left waiting on a bed that will never be approved.</li>
     *   <li>Insert the BLOCKED row.</li>
     * </ol>
     */
    public BedActionResultDto blockBed(Long adminId, Long bedId, String reason) {
        Bed bed = bedRepository.findByIdWithRoom(bedId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                        "No such bed.", Map.of("bedId", bedId)));

        User admin = userRepository.getReferenceById(adminId);
        Optional<BedClaim> live = claimRepository.findLiveClaimOnBed(bedId);
        Long autoRejectedRequestId = null;

        if (live.isPresent()) {
            BedClaim existing = live.get();

            switch (existing.getStatus()) {
                case ALLOCATED -> throw new DomainException(
                        ErrorCode.BED_ALLOCATED_CANNOT_BLOCK,
                        "Bed %d-%s is allocated to a student. Removing an occupant is a separate "
                                .formatted(bed.getRoom().getRoomNumber(), bed.getBedLabel())
                                + "eviction workflow and is deliberately out of scope in this build.",
                        Map.of("bedId", bedId, "roomNumber", bed.getRoom().getRoomNumber()));

                case BLOCKED -> {
                    // Already blocked. Same reasoning as a repeat approve: intent satisfied.
                    return result(bed, BedStatus.BLOCKED, null, true, existing.getDecisionReason());
                }

                case PENDING -> {
                    // Conditional again, not a blind update: the student may be cancelling at this
                    // very moment, in which case 0 rows change and there was nothing to reject.
                    int rejected = claimRepository.rejectIfStillPending(
                            existing.getId(), admin, Instant.now(), BLOCKED_SYSTEM_REASON);
                    if (rejected == 1) {
                        autoRejectedRequestId = existing.getId();
                        log.info("Auto-rejected request {} because bed {} is being blocked",
                                existing.getId(), bedId);
                    }
                }

                default -> throw new IllegalStateException(
                        "findLiveClaimOnBed returned a terminal status: " + existing.getStatus());
            }
        }

        try {
            claimRepository.saveAndFlush(BedClaim.maintenanceBlock(bed, admin, reason));
        } catch (DataIntegrityViolationException e) {
            // A student's request landed between the check above and this insert. The index caught
            // it, so the whole transaction rolls back - including the auto-reject, which is exactly
            // right: nothing is half-applied and the admin can simply retry.
            throw ClaimConstraints.translate(e, bedId);
        }

        log.info("Admin {} blocked bed {} ({})", adminId, bedId, reason);
        return result(bed, BedStatus.BLOCKED, autoRejectedRequestId, false, reason);
    }

    /** Returns a blocked bed to circulation. Idempotent. */
    public BedActionResultDto unblockBed(Long adminId, Long bedId, String reason) {
        Bed bed = bedRepository.findByIdWithRoom(bedId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                        "No such bed.", Map.of("bedId", bedId)));

        Optional<BedClaim> live = claimRepository.findLiveClaimOnBed(bedId);

        if (live.isEmpty()) {
            // Nothing live at all: the bed is already available. Not an error - it is the state the
            // caller wanted.
            return result(bed, BedStatus.AVAILABLE, null, true, null);
        }

        BedClaim existing = live.get();
        if (existing.getStatus() != ClaimStatus.BLOCKED) {
            throw new DomainException(ErrorCode.BED_NOT_BLOCKED,
                    "Bed %d-%s is not blocked - it is %s."
                            .formatted(bed.getRoom().getRoomNumber(), bed.getBedLabel(),
                                    existing.getStatus().name().toLowerCase()),
                    Map.of("bedId", bedId, "actualStatus", existing.getStatus().name()));
        }

        User admin = userRepository.getReferenceById(adminId);
        int updated = claimRepository.unblockIfStillBlocked(
                existing.getId(), admin, Instant.now(), reason);

        if (updated == 0) {
            // Another admin unblocked it a moment ago.
            return result(bed, BedStatus.AVAILABLE, null, true, null);
        }

        log.info("Admin {} unblocked bed {}", adminId, bedId);
        return result(bed, BedStatus.AVAILABLE, null, false, reason);
    }

    private BedActionResultDto result(Bed bed, BedStatus status, Long autoRejectedRequestId,
                                      boolean alreadyHandled, String reason) {
        return new BedActionResultDto(
                bed.getId(),
                bed.getRoom().getRoomNumber(),
                bed.getBedLabel(),
                status,
                autoRejectedRequestId,
                alreadyHandled,
                reason);
    }
}
