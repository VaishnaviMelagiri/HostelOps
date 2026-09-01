package com.hostelops.claim.dto;

import com.hostelops.claim.BedClaim;
import com.hostelops.claim.ClaimStatus;

import java.time.Instant;

/**
 * A student's own claim, as returned to that student.
 *
 * <p>Contains no identity of any kind - not even the student's own id, which they already know.
 * A student only ever sees their own claim through this DTO, and other students' beds only as an
 * anonymous status on the map.
 */
public record ClaimDto(
        Long requestId,
        Long bedId,
        Integer roomNumber,
        String bedLabel,
        String wing,
        String floor,
        String roomType,
        String bathroomType,
        ClaimStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant decidedAt,
        String decisionReason) {

    public static ClaimDto from(BedClaim claim) {
        var bed = claim.getBed();
        var room = bed.getRoom();
        return new ClaimDto(
                claim.getId(),
                bed.getId(),
                room.getRoomNumber(),
                bed.getBedLabel(),
                room.getWing(),
                room.getFloor(),
                room.getRoomType(),
                room.getBathroomType(),
                claim.getStatus(),
                claim.getCreatedAt(),
                claim.getExpiresAt(),
                claim.getDecidedAt(),
                claim.getDecisionReason());
    }
}
