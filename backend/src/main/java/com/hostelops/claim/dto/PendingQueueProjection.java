package com.hostelops.claim.dto;

import java.time.Instant;

/**
 * Flat result row for the pending-queue query.
 *
 * <p>Flat because JPQL constructor expressions ({@code SELECT new ...}) can only build one object
 * per row - they cannot nest a StudentSummaryDto inside a PendingQueueRowDto. So the query produces
 * this flat shape and the service assembles the nested API shape from it.
 *
 * <p>Selecting named fields rather than whole entities also avoids the N+1 problem here: one query
 * returns everything the queue needs, and pagination works normally (a JOIN FETCH with a Pageable
 * would make Hibernate page in memory after loading every row).
 */
public record PendingQueueProjection(
        Long requestId,
        Long studentId,
        String fullName,
        String studentCode,
        String course,
        String wing,
        String floor,
        Integer roomNumber,
        String bedLabel,
        Instant createdAt,
        Instant expiresAt) {
}
