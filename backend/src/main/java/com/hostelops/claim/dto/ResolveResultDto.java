package com.hostelops.claim.dto;

import com.hostelops.claim.ClaimStatus;

/**
 * Outcome of an approve or a reject.
 *
 * @param alreadyHandled true when the request was ALREADY in this exact state before the call.
 *                       That is a success, not a failure: the caller's intent is satisfied either
 *                       way, so a retry, a double-click and a second admin all get a truthful 200.
 *                       A request in a DIFFERENT terminal state is a real conflict and returns 409
 *                       naming the actual status instead - the admin needs to know that the student
 *                       cancelled, not be told "done" about something that never happened.
 */
public record ResolveResultDto(
        Long requestId,
        ClaimStatus status,
        boolean alreadyHandled,
        String decisionReason) {
}
