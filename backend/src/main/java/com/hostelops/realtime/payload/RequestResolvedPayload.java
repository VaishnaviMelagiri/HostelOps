package com.hostelops.realtime.payload;

import com.hostelops.claim.ClaimStatus;

import java.time.Instant;

/**
 * Private per-student message: your own request was decided.
 *
 * <p>Sent only to the student who owns the request, over {@code /user/queue/requests}.
 *
 * <p>Note it carries no roommate name even in Phase 8, where roommate visibility arrives. When both
 * beds in a Double become ALLOCATED this message will carry a {@code roommateVisible} flag telling
 * the client to refetch {@code /api/me/allocation} - names travel over an authenticated REST call,
 * never over a transport whose whole job is broadcasting.
 *
 * @param reason why, in the student's words: the admin's rejection text, the system reason when a
 *               bed was blocked, or the expiry message. A decision is never delivered unexplained.
 */
public record RequestResolvedPayload(
        String event,
        Long requestId,
        Long bedId,
        Integer roomNumber,
        String bedLabel,
        String wing,
        String floor,
        ClaimStatus status,
        String reason,
        Instant at) {

    public static final String EVENT = "REQUEST_RESOLVED";

    public static RequestResolvedPayload of(Long requestId, Long bedId, Integer roomNumber,
                                            String bedLabel, String wing, String floor,
                                            ClaimStatus status, String reason, Instant at) {
        return new RequestResolvedPayload(
                EVENT, requestId, bedId, roomNumber, bedLabel, wing, floor, status, reason, at);
    }
}
