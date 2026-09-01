package com.hostelops.claim;

/**
 * Signals that an Idempotency-Key has already been used, so the original row should be replayed
 * rather than a second one created.
 *
 * <p>Internal to the claim package - it never reaches an HTTP response. {@link ClaimService}
 * catches it and returns the existing claim with 200.
 */
public class DuplicateRequestKeyException extends RuntimeException {

    public DuplicateRequestKeyException() {
        super("Idempotency-Key already used");
    }
}
