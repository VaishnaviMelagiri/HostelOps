package com.hostelops.claim.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * One row of the admin pending queue.
 *
 * @param pendingAgeSeconds how long this request has been waiting. Surfaced explicitly so an admin
 *                          can see a request approaching its expiry rather than being surprised
 *                          when the Phase 6 sweep frees the bed underneath them.
 * @param expiresAt         when the sweep will expire it if nobody decides
 */
public record PendingQueueRowDto(
        Long requestId,
        StudentSummaryDto student,
        String wing,
        String floor,
        Integer roomNumber,
        String bedLabel,
        Instant createdAt,
        Instant expiresAt,
        long pendingAgeSeconds) {

    public static PendingQueueRowDto from(PendingQueueProjection row, Instant now) {
        return new PendingQueueRowDto(
                row.requestId(),
                new StudentSummaryDto(row.studentId(), row.fullName(), row.studentCode(), row.course()),
                row.wing(),
                row.floor(),
                row.roomNumber(),
                row.bedLabel(),
                row.createdAt(),
                row.expiresAt(),
                Math.max(0, Duration.between(row.createdAt(), now).toSeconds()));
    }
}
