package com.hostelops.expiry;

/**
 * One request that a sweep actually expired.
 *
 * <p>Returned so the caller knows precisely which rows changed rather than just how many. Phase 6
 * only logs them; Phase 7 turns each one into a WebSocket event telling that student their request
 * lapsed, and the map that the bed is free again.
 */
public record ExpiredClaim(Long requestId, Long bedId, Long studentId, Integer roomNumber, String bedLabel) {
}
