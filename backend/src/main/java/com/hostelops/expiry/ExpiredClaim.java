package com.hostelops.expiry;

/**
 * One request that a sweep actually expired.
 *
 * <p>Returned so the caller knows precisely which rows changed rather than just how many - the
 * sweep turns each entry into two messages: the bed going back to AVAILABLE on the public floor
 * topic, and a private notification to the student whose request lapsed.
 */
public record ExpiredClaim(
        Long requestId,
        Long bedId,
        Long roomId,
        Long studentId,
        Integer roomNumber,
        String wing,
        String floor,
        String bedLabel) {
}
