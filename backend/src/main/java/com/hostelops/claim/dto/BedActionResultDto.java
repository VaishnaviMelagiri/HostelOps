package com.hostelops.claim.dto;

import com.hostelops.room.dto.BedStatus;

/**
 * Outcome of a block or unblock.
 *
 * @param autoRejectedRequestId the pending request that was auto-rejected to make the block
 *                              possible, or null if the bed had none. Returned so the admin can see
 *                              that blocking this bed cost a student their request - a consequence
 *                              worth surfacing rather than performing quietly.
 * @param alreadyHandled        true when the bed was already in the requested state
 */
public record BedActionResultDto(
        Long bedId,
        Integer roomNumber,
        String bedLabel,
        BedStatus status,
        Long autoRejectedRequestId,
        boolean alreadyHandled,
        String reason) {
}
