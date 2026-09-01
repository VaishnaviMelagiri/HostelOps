package com.hostelops.claim.dto;

import com.hostelops.claim.ClaimStatus;

/**
 * Outcome of a cancel.
 *
 * @param alreadyHandled true when the request was already cancelled before this call - not a
 *                       failure, because the caller's intent is satisfied either way. A retried
 *                       request or a double-click gets a truthful 200 rather than an error.
 */
public record CancelResultDto(Long requestId, ClaimStatus status, boolean alreadyHandled) {
}
