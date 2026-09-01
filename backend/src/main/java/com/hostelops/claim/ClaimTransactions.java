package com.hostelops.claim;

import com.hostelops.claim.dto.CancelResultDto;
import com.hostelops.claim.dto.ClaimDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.room.Bed;
import com.hostelops.room.BedRepository;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The database operations behind a bed request, each in its own transaction.
 *
 * <p><strong>Why this is a separate class from {@link ClaimService}.</strong> Spring's
 * {@code @Transactional} works by wrapping a bean in a proxy: calls that arrive from outside go
 * through the proxy and get a transaction, but a call from one method of a class to another method
 * of the SAME class does not - it is a plain Java call that never touches the proxy, so the second
 * method silently runs with no transaction of its own. That is the "self-invocation" trap.
 *
 * <p>It matters here specifically. When an insert violates a unique index, Postgres marks the whole
 * transaction as aborted: no further query can run inside it, not even a SELECT. So recovering from
 * a duplicate Idempotency-Key requires a genuinely NEW transaction to look up the original row. Put
 * both in one class and the recovery lookup would either fail or quietly run outside a transaction.
 * Splitting them means each call from ClaimService crosses a proxy boundary and gets a real, fresh
 * transaction.
 */
@Service
public class ClaimTransactions {

    private final BedClaimRepository claimRepository;
    private final BedRepository bedRepository;
    private final UserRepository userRepository;
    private final Duration requestTtl;

    public ClaimTransactions(BedClaimRepository claimRepository,
                             BedRepository bedRepository,
                             UserRepository userRepository,
                             @Value("${hostelops.request.ttl:PT48H}") Duration requestTtl) {
        this.claimRepository = claimRepository;
        this.bedRepository = bedRepository;
        this.userRepository = userRepository;
        this.requestTtl = requestTtl;
    }

    /**
     * Inserts a PENDING claim, letting the database decide whether it is allowed.
     *
     * <p>There is deliberately NO "is this bed free?" check before the insert. Such a check would
     * be worse than useless: between the SELECT and the INSERT another transaction can insert its
     * own row, so the check would pass and the insert would still fail - it would simply move the
     * failure somewhere less obvious while creating the illusion of safety. The unique index is
     * evaluated at the moment of writing, which is the only moment that can be authoritative.
     */
    @Transactional
    public ClaimDto createPending(Long studentId, Long bedId, UUID requestKey) {
        Bed bed = bedRepository.findByIdWithRoom(bedId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                        "No such bed.", Map.of("bedId", bedId)));

        // getReferenceById returns a lazy proxy - enough to set the foreign key, with no SELECT.
        User student = userRepository.getReferenceById(studentId);

        BedClaim claim = BedClaim.pendingRequest(
                bed, student, Instant.now().plus(requestTtl), requestKey);

        try {
            // saveAndFlush, not save: flush sends the INSERT to Postgres NOW, so any constraint
            // violation is thrown here where it can be translated. A plain save() would defer the
            // INSERT to commit time, and the exception would surface after this method returned -
            // too late for this try/catch, and reported as a generic 500.
            BedClaim saved = claimRepository.saveAndFlush(claim);
            return ClaimDto.from(saved);
        } catch (DataIntegrityViolationException e) {
            throw ClaimConstraints.translate(e, bedId);
        }
    }

    @Transactional(readOnly = true)
    public Optional<ClaimDto> findByRequestKey(UUID requestKey) {
        return claimRepository.findByRequestKey(requestKey).map(ClaimDto::from);
    }

    @Transactional(readOnly = true)
    public Optional<ClaimDto> findLiveClaimForStudent(Long studentId) {
        return claimRepository.findLiveClaimForStudent(studentId).map(ClaimDto::from);
    }

    /**
     * Cancels the student's own pending request.
     *
     * <p>One conditional UPDATE decides everything. If it changes a row, this call did the
     * cancelling. If it changes nothing, we read the row back to explain why - and the reasons are
     * genuinely different things that must not be conflated: no such request, someone else's
     * request, or a request an admin resolved a moment ago.
     */
    @Transactional
    public CancelResultDto cancelOwnRequest(Long studentId, Long claimId) {
        int updated = claimRepository.cancelIfStillPending(claimId, studentId, Instant.now());
        if (updated == 1) {
            return new CancelResultDto(claimId, ClaimStatus.CANCELLED, false);
        }

        BedClaim claim = claimRepository.findByIdWithBed(claimId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                        "No such request.", Map.of("requestId", claimId)));

        // Not this student's request. FORBIDDEN rather than NOT_FOUND is a deliberate choice: the
        // student already learned the id exists by being told nothing about it either way, and a
        // truthful 403 is clearer than pretending the row is missing.
        if (claim.getStudent() == null || !studentId.equals(claim.getStudent().getId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "That request is not yours.");
        }

        // Already cancelled: the caller's intent is satisfied, so this is a success, not an error.
        if (claim.getStatus() == ClaimStatus.CANCELLED) {
            return new CancelResultDto(claimId, ClaimStatus.CANCELLED, true);
        }

        // A different terminal state - an admin approved or rejected it while the student clicked.
        // Whoever committed first wins; this call reports honestly what actually happened.
        throw new DomainException(ErrorCode.REQUEST_ALREADY_RESOLVED,
                "That request was already " + claim.getStatus().name().toLowerCase() + ".",
                Map.of("requestId", claimId, "actualStatus", claim.getStatus().name()));
    }
}
