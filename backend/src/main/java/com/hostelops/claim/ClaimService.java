package com.hostelops.claim;

import com.hostelops.claim.dto.CancelResultDto;
import com.hostelops.claim.dto.ClaimDto;
import com.hostelops.claim.dto.MyAllocationDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The student side of the workflow: request a bed, cancel your own request, see what you hold.
 *
 * <p>Note there is no {@code @Transactional} on this class. It orchestrates; each call into
 * {@link ClaimTransactions} opens its own transaction. That is what makes the duplicate-key replay
 * below possible - see the comment there.
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private final ClaimTransactions transactions;

    public ClaimService(ClaimTransactions transactions) {
        this.transactions = transactions;
    }

    /** Whether a request created a new row or replayed an existing one, so the controller can pick 201 vs 200. */
    public record RequestOutcome(ClaimDto claim, boolean created) {
    }

    /**
     * Requests a bed for a student.
     *
     * @param requestKey the client's Idempotency-Key, or null if it did not send one
     */
    public RequestOutcome requestBed(Long studentId, Long bedId, UUID requestKey) {
        // Fast path: this exact click already produced a row, so return that row rather than
        // attempting a second insert that would be rejected anyway.
        if (requestKey != null) {
            var replay = transactions.findByRequestKey(requestKey);
            if (replay.isPresent()) {
                log.debug("Replaying request {} for idempotency key {}",
                        replay.get().requestId(), requestKey);
                return new RequestOutcome(replay.get(), false);
            }
        }

        try {
            ClaimDto created = transactions.createPending(studentId, bedId, requestKey);
            log.info("Student {} requested bed {} (request {})", studentId, bedId, created.requestId());
            return new RequestOutcome(created, true);

        } catch (DuplicateRequestKeyException e) {
            // Two copies of the same click arrived close enough together that both passed the fast
            // path above before either committed. The database refused the second, which is exactly
            // right - one click, one row. Now read back the row that did win.
            //
            // This lookup MUST happen in a new transaction: the one that just hit the constraint is
            // aborted, and Postgres will not run another statement inside it. That is the whole
            // reason ClaimTransactions is a separate bean.
            return transactions.findByRequestKey(requestKey)
                    .map(existing -> new RequestOutcome(existing, false))
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency key " + requestKey + " was rejected as duplicate but no "
                                    + "row with that key exists"));
        }
    }

    /** Cancels the student's own pending request, returning the bed to AVAILABLE. */
    public CancelResultDto cancelOwnRequest(Long studentId, Long requestId) {
        CancelResultDto result = transactions.cancelOwnRequest(studentId, requestId);
        if (!result.alreadyHandled()) {
            log.info("Student {} cancelled request {}", studentId, requestId);
        }
        return result;
    }

    /** What this student currently holds: nothing, a pending request, or an allocation. */
    public MyAllocationDto myAllocation(Long studentId) {
        return transactions.findLiveClaimForStudent(studentId)
                .map(MyAllocationDto::of)
                .orElseGet(MyAllocationDto::none);
    }

    /** Guards against a caller passing a bed id that is not a number, before it reaches the DB. */
    static void requireBedId(Long bedId) {
        if (bedId == null || bedId <= 0) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "A valid bedId is required.");
        }
    }
}
