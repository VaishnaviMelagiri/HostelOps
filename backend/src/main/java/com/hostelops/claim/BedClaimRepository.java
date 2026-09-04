package com.hostelops.claim;

import com.hostelops.claim.dto.PendingQueueProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    // ─────────────────────────── Phase 5: admin actions ───────────────────────────

    /**
     * The pending queue, oldest first.
     *
     * <p>Oldest first is FIFO fairness, and it also puts the request closest to expiring at the top
     * - so working down the list naturally handles the most urgent one first.
     *
     * <p>Selects named columns instead of entities: one query, no N+1 on the student, and paging
     * happens in the database. A {@code JOIN FETCH} with a {@code Pageable} would make Hibernate
     * load every matching row and page in memory, which is fine at 3 rows and ruinous at 3,000.
     */
    @Query(value = """
            SELECT new com.hostelops.claim.dto.PendingQueueProjection(
                c.id, s.id, s.fullName, s.studentCode, s.course,
                r.wing, r.floor, r.roomNumber, b.bedLabel, c.createdAt, c.expiresAt)
            FROM BedClaim c
            JOIN c.student s
            JOIN c.bed b
            JOIN b.room r
            WHERE c.status = com.hostelops.claim.ClaimStatus.PENDING
            ORDER BY c.createdAt ASC
            """,
            countQuery = """
            SELECT count(c) FROM BedClaim c
            WHERE c.status = com.hostelops.claim.ClaimStatus.PENDING
            """)
    Page<PendingQueueProjection> findPendingQueue(Pageable pageable);

    /**
     * Approves a request - PENDING to ALLOCATED - only if it is still pending.
     *
     * <p><strong>This one statement is the answer to "how do you stop two admins allocating the
     * same room?"</strong> Both admins run this. The first acquires the row lock and updates one
     * row. The second blocks on that same lock, and when the first commits it re-evaluates its own
     * WHERE clause against the NEW row version under READ COMMITTED, finds status is no longer
     * PENDING, and matches zero rows. No error, no exception - just zero, which the caller reads as
     * "somebody else already did this".
     *
     * <p>Note this is a different mechanism from the partial unique index, and both are needed.
     * The index stops TWO CLAIMS existing on one bed. It does nothing about ONE CLAIM being
     * resolved twice, because approval is an UPDATE of an existing row, not an INSERT. Conflating
     * the two is the most common mistake in explaining this design.
     *
     * @return 1 if this call performed the approval, 0 if it did not
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BedClaim c
               SET c.status = com.hostelops.claim.ClaimStatus.ALLOCATED,
                   c.decidedAt = :now,
                   c.decidedBy = :admin,
                   c.expiresAt = NULL
             WHERE c.id = :claimId
               AND c.status = com.hostelops.claim.ClaimStatus.PENDING
            """)
    int approveIfStillPending(@Param("claimId") Long claimId,
                              @Param("admin") com.hostelops.user.User admin,
                              @Param("now") Instant now);

    /** Rejects a request - PENDING to REJECTED, freeing the bed - only if it is still pending. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BedClaim c
               SET c.status = com.hostelops.claim.ClaimStatus.REJECTED,
                   c.decidedAt = :now,
                   c.decidedBy = :admin,
                   c.decisionReason = :reason,
                   c.expiresAt = NULL
             WHERE c.id = :claimId
               AND c.status = com.hostelops.claim.ClaimStatus.PENDING
            """)
    int rejectIfStillPending(@Param("claimId") Long claimId,
                             @Param("admin") com.hostelops.user.User admin,
                             @Param("now") Instant now,
                             @Param("reason") String reason);

    /** The one live claim on a bed, if any - a student request, an allocation, or a block. */
    @Query("""
            SELECT c FROM BedClaim c
            JOIN FETCH c.bed b
            JOIN FETCH b.room
            WHERE c.bed.id = :bedId
              AND c.status IN (com.hostelops.claim.ClaimStatus.PENDING,
                               com.hostelops.claim.ClaimStatus.ALLOCATED,
                               com.hostelops.claim.ClaimStatus.BLOCKED)
            """)
    Optional<BedClaim> findLiveClaimOnBed(@Param("bedId") Long bedId);

    /** Lifts a maintenance block - BLOCKED to UNBLOCKED - only if the bed is still blocked. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BedClaim c
               SET c.status = com.hostelops.claim.ClaimStatus.UNBLOCKED,
                   c.decidedAt = :now,
                   c.decidedBy = :admin,
                   c.decisionReason = :reason
             WHERE c.id = :claimId
               AND c.status = com.hostelops.claim.ClaimStatus.BLOCKED
            """)
    int unblockIfStillBlocked(@Param("claimId") Long claimId,
                              @Param("admin") com.hostelops.user.User admin,
                              @Param("now") Instant now,
                              @Param("reason") String reason);

    // ─────────────────────────── Phase 6: auto-expiry ───────────────────────────

    /**
     * Ids of PENDING requests whose deadline has passed.
     *
     * <p>Reads only ids, and only from {@code idx_claims_pending_expiry} - a partial index holding
     * nothing but pending rows. However large the history grows, this scan stays proportional to
     * the number of requests currently waiting, not to the size of the table.
     *
     * <p>Limited by the Pageable so one sweep cannot lock thousands of rows at once if a backlog
     * ever builds up; whatever it misses is simply picked up by the next sweep a few minutes later.
     */
    @Query("""
            SELECT c.id FROM BedClaim c
            WHERE c.status = com.hostelops.claim.ClaimStatus.PENDING
              AND c.expiresAt <= :now
            ORDER BY c.expiresAt ASC
            """)
    List<Long> findExpiredPendingIds(@Param("now") Instant now, Pageable limit);

    /**
     * Expires one request, only if it is still pending.
     *
     * <p>Conditional for the same reason approve and cancel are: between selecting the candidates
     * above and updating them, an admin may have approved one, or a second application instance
     * running its own sweep may have expired it. The predicate means each row is claimed by exactly
     * one transaction - no leader election, no distributed lock, no rows expired twice.
     *
     * <p>Note {@code decidedBy} is deliberately left NULL. EXPIRED is the one transition no human
     * performs, and {@code ck_claims_decided} encodes exactly that: EXPIRED requires a decidedAt
     * and forbids a decidedBy. Inventing a "system user" to fill the column would put a fake row in
     * the users table forever.
     *
     * @return 1 if this call expired the request, 0 if someone else resolved it first
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BedClaim c
               SET c.status = com.hostelops.claim.ClaimStatus.EXPIRED,
                   c.decidedAt = :now,
                   c.decisionReason = :reason,
                   c.expiresAt = NULL
             WHERE c.id = :claimId
               AND c.status = com.hostelops.claim.ClaimStatus.PENDING
            """)
    int expireIfStillPending(@Param("claimId") Long claimId,
                             @Param("now") Instant now,
                             @Param("reason") String reason);

    // ─────────────────────────── Phase 8: roommate visibility ───────────────────────────

    /**
     * The name and course of whoever holds the OTHER bed in this room - and only if their claim is
     * ALLOCATED.
     *
     * <p>Three guards, all in the query rather than in Java:
     * <ul>
     *   <li>{@code b.id <> :myBedId} - never returns the caller themselves.</li>
     *   <li>{@code c.status = ALLOCATED} - a merely PENDING neighbour returns nothing, which is the
     *       rule the whole feature turns on. Someone could otherwise request the free bed in a
     *       room, read the occupant's name, and cancel.</li>
     *   <li>The SELECT lists two columns. The User entity never reaches the service layer on this
     *       path, so no later refactor can widen this to an email address by accident - the query
     *       is physically incapable of returning one.</li>
     * </ul>
     *
     * <p>Returns empty for a Single room too, since there is no other bed to match.
     */
    @Query("""
            SELECT new com.hostelops.claim.dto.RoommateDto(s.fullName, s.course)
            FROM BedClaim c
            JOIN c.student s
            JOIN c.bed b
            WHERE b.room.id = :roomId
              AND b.id <> :myBedId
              AND c.status = com.hostelops.claim.ClaimStatus.ALLOCATED
            """)
    Optional<com.hostelops.claim.dto.RoommateDto> findConfirmedRoommate(
            @Param("roomId") Long roomId, @Param("myBedId") Long myBedId);

    /**
     * The live status of the other bed in the room, whatever it is.
     *
     * <p>Separate from the query above on purpose. This one may say "PENDING" - a fact about a bed,
     * which is public on the map anyway - while revealing nobody. Combining the two into one query
     * that returned a name alongside a status would put the identity one careless edit away from
     * the PENDING case.
     */
    @Query("""
            SELECT c.status FROM BedClaim c
            JOIN c.bed b
            WHERE b.room.id = :roomId
              AND b.id <> :myBedId
              AND c.status IN (com.hostelops.claim.ClaimStatus.PENDING,
                               com.hostelops.claim.ClaimStatus.ALLOCATED,
                               com.hostelops.claim.ClaimStatus.BLOCKED)
            """)
    Optional<ClaimStatus> findOtherBedStatus(@Param("roomId") Long roomId,
                                             @Param("myBedId") Long myBedId);
}
