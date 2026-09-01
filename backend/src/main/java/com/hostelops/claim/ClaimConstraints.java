package com.hostelops.claim;

import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

/**
 * Translates a database constraint violation into the right API error.
 *
 * <p>This class is the seam where "the database refused the write" becomes "that bed was just
 * taken". When Postgres rejects an INSERT it raises SQLSTATE 23505 naming the index that was
 * violated, and Spring wraps that as a {@link DataIntegrityViolationException}. One exception type,
 * several completely different meanings - so the only way to tell them apart is the constraint
 * NAME. That is why the indexes in V3 are named explicitly rather than left to Postgres to
 * auto-name: renaming one silently turns a clear 409 into a generic 500.
 */
public final class ClaimConstraints {

    public static final String LIVE_PER_BED = "uq_claim_live_per_bed";
    public static final String LIVE_PER_STUDENT = "uq_claim_live_per_student";
    public static final String REQUEST_KEY = "uq_claim_request_key";

    private ClaimConstraints() {
    }

    /**
     * @param bedId the bed being requested, for the error's details block
     * @return the DomainException to throw. Never returns normally for an unrecognised constraint -
     *         it rethrows, because an unexpected integrity violation is a bug and should surface as
     *         a 500 with a stack trace rather than be dressed up as a tidy business error.
     */
    public static RuntimeException translate(DataIntegrityViolationException e, Long bedId) {
        String constraint = constraintNameOf(e);

        if (LIVE_PER_BED.equals(constraint)) {
            // Someone else's insert won the race by microseconds.
            return new DomainException(ErrorCode.BED_NOT_AVAILABLE,
                    "That bed was just taken. Pick another one.",
                    Map.of("bedId", bedId));
        }
        if (LIVE_PER_STUDENT.equals(constraint)) {
            return new DomainException(ErrorCode.STUDENT_ALREADY_HAS_CLAIM,
                    "You already have an active request or allocation. "
                            + "Cancel it before requesting a different bed.");
        }
        if (REQUEST_KEY.equals(constraint)) {
            // The same click arriving twice. The caller replays the original row instead of failing.
            return new DuplicateRequestKeyException();
        }
        return e;
    }

    /**
     * Digs the constraint name out of the exception chain.
     *
     * <p>Hibernate usually exposes it directly. When it does not, the name still appears in the
     * driver's message text ("...violates unique constraint \"uq_claim_live_per_bed\""), so the
     * fallback scans for the known names. Matching on our own names rather than parsing arbitrary
     * message text keeps the fallback safe: an unrecognised message simply yields null and the
     * exception is rethrown as a 500, which is the correct outcome for something unexpected.
     */
    private static String constraintNameOf(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hibernateCause) {
                String name = hibernateCause.getConstraintName();
                if (name != null && !name.isBlank()) {
                    return name.toLowerCase();
                }
            }
            cause = cause.getCause();
        }

        String message = e.getMostSpecificCause().getMessage();
        if (message == null) {
            return null;
        }
        String lower = message.toLowerCase();
        for (String known : new String[]{LIVE_PER_BED, LIVE_PER_STUDENT, REQUEST_KEY}) {
            if (lower.contains(known)) {
                return known;
            }
        }
        return null;
    }
}
