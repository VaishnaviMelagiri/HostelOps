package com.hostelops.claim;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BedClaimRepository extends JpaRepository<BedClaim, Long> {

    /** The one live claim a student may hold, if any. */
    @Query("""
            SELECT c FROM BedClaim c
            JOIN FETCH c.bed b
            JOIN FETCH b.room
            WHERE c.student.id = :studentId
              AND c.status IN (com.hostelops.claim.ClaimStatus.PENDING,
                               com.hostelops.claim.ClaimStatus.ALLOCATED)
            """)
    Optional<BedClaim> findLiveClaimForStudent(@Param("studentId") Long studentId);

    /** Used to replay a repeated Idempotency-Key instead of creating a second row. */
    @Query("""
            SELECT c FROM BedClaim c
            JOIN FETCH c.bed b
            JOIN FETCH b.room
            WHERE c.requestKey = :requestKey
            """)
    Optional<BedClaim> findByRequestKey(@Param("requestKey") UUID requestKey);

    @Query("""
            SELECT c FROM BedClaim c
            JOIN FETCH c.bed b
            JOIN FETCH b.room
            WHERE c.id = :id
            """)
    Optional<BedClaim> findByIdWithBed(@Param("id") Long id);

    /**
     * Cancels a student's own pending request.
     *
     * <p>This is a CONDITIONAL UPDATE, and the condition is the whole point. It does not read the
     * row, decide, then write - it asks the database to change the row <em>only if</em> it is still
     * PENDING and still belongs to this student. If an admin approved it a millisecond earlier, the
     * WHERE clause matches nothing and this returns 0, so two people acting at once can never both
     * "win". No lock, no retry loop, no read-then-write gap.
     *
     * <p>{@code @Modifying} tells Spring Data this is a write. {@code clearAutomatically} discards
     * Hibernate's cached copy of the row afterwards, so a later read in the same transaction sees
     * the new value rather than the stale one it had loaded.
     *
     * @return 1 if this call performed the cancellation, 0 if it did not
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BedClaim c
               SET c.status = com.hostelops.claim.ClaimStatus.CANCELLED,
                   c.decidedAt = :now,
                   c.decidedBy = c.student,
                   c.expiresAt = NULL
             WHERE c.id = :claimId
               AND c.student.id = :studentId
               AND c.status = com.hostelops.claim.ClaimStatus.PENDING
            """)
    int cancelIfStillPending(@Param("claimId") Long claimId,
                             @Param("studentId") Long studentId,
                             @Param("now") Instant now);
}
