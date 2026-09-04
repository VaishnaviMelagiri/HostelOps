package com.hostelops.expiry;

import com.hostelops.claim.BedClaim;
import com.hostelops.claim.BedClaimRepository;
import com.hostelops.claim.ClaimStatus;
import com.hostelops.realtime.ClaimStateChangedEvent;
import com.hostelops.realtime.payload.ChangeCause;
import com.hostelops.room.dto.BedStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Frees beds whose requests nobody answered.
 *
 * <p><strong>Why this exists.</strong> A PENDING request holds a bed: the partial unique index
 * makes that bed unavailable to everyone else for as long as the row is live. Without expiry, one
 * admin going on holiday would take beds out of circulation indefinitely, and no student could
 * request them - the very safety mechanism that prevents double-booking would become the thing
 * freezing the building. The TTL bounds how long a single unanswered request can cost the hostel a
 * bed.
 *
 * <p>The sweep is deliberately separated from the scheduler that triggers it, so the logic can be
 * called directly from a test without waiting for a timer.
 */
@Service
public class ClaimExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ClaimExpiryService.class);

    /** Recorded on the row and shown to the student, so a lapsed request is never unexplained. */
    public static final String EXPIRY_REASON = "No response from the hostel office in time";

    /**
     * Most rows one sweep will touch.
     *
     * <p>A cap rather than "all of them": if a backlog ever built up - the app down for a week, say
     * - an unbounded sweep would lock thousands of rows in a single long transaction. Whatever is
     * left over is simply picked up by the next sweep minutes later, so the work still drains, just
     * in bounded chunks.
     */
    private static final int MAX_PER_SWEEP = 500;

    private final BedClaimRepository claimRepository;
    private final ApplicationEventPublisher events;
    private final Duration requestTtl;

    public ClaimExpiryService(BedClaimRepository claimRepository,
                              ApplicationEventPublisher events,
                              @Value("${hostelops.request.ttl:PT48H}") Duration requestTtl) {
        this.claimRepository = claimRepository;
        this.events = events;
        this.requestTtl = requestTtl;
    }

    /**
     * Expires every PENDING request whose deadline has passed.
     *
     * <p>Two steps, and the second is conditional. First read the candidate ids; then expire each
     * one only if it is still pending. That second predicate is what makes the sweep safe to run on
     * several application instances at once: between the read and the write an admin may have
     * approved a request, or another instance's sweep may have expired it, and in both cases this
     * call simply changes nothing rather than trampling the other outcome.
     *
     * <p>No leader election and no distributed lock - the same conditional-update idea that makes
     * approve and cancel safe, applied to a background job.
     *
     * <p>Nothing "unfreezes" the bed as a separate step. The row leaving the index's WHERE clause
     * IS the bed becoming AVAILABLE again, because status is derived rather than stored.
     */
    @Transactional
    public List<ExpiredClaim> sweep() {
        Instant now = Instant.now();
        List<Long> candidates =
                claimRepository.findExpiredPendingIds(now, PageRequest.of(0, MAX_PER_SWEEP));

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<ExpiredClaim> expired = new ArrayList<>(candidates.size());
        for (Long claimId : candidates) {
            if (claimRepository.expireIfStillPending(claimId, now, EXPIRY_REASON) == 1) {
                // Re-read only the rows this sweep actually changed, so the returned list is exact
                // rather than optimistic - and so each one can be announced individually.
                claimRepository.findByIdWithBed(claimId).ifPresent(claim -> {
                    ExpiredClaim record = toRecord(claim);
                    expired.add(record);

                    // The bed goes back on the map for everyone, and the student is told their
                    // request lapsed rather than being left to wonder why it vanished.
                    events.publishEvent(ClaimStateChangedEvent.affectingStudent(
                            record.requestId(), record.bedId(), record.roomId(),
                            record.roomNumber(), record.wing(), record.floor(), record.bedLabel(),
                            BedStatus.AVAILABLE, ClaimStatus.EXPIRED, ChangeCause.EXPIRED,
                            record.studentId(), EXPIRY_REASON));
                });
            }
        }

        if (!expired.isEmpty()) {
            log.info("Expired {} stale request(s) after {} without a decision",
                    expired.size(), requestTtl);
        }
        return expired;
    }

    private static ExpiredClaim toRecord(BedClaim claim) {
        var bed = claim.getBed();
        var room = bed.getRoom();
        return new ExpiredClaim(
                claim.getId(),
                bed.getId(),
                room.getId(),
                claim.getStudent() != null ? claim.getStudent().getId() : null,
                room.getRoomNumber(),
                room.getWing(),
                room.getFloor(),
                bed.getBedLabel());
    }

    public Duration requestTtl() {
        return requestTtl;
    }
}
