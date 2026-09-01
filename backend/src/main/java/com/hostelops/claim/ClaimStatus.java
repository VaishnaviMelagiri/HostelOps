package com.hostelops.claim;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every state a claim on a bed can be in. Matches ck_claims_status in V1__core_tables.sql.
 *
 * <p>Three of these are "live" - they mean the bed is currently spoken for. The rest are terminal
 * history: the row stays in the table for audit but no longer occupies the bed.
 */
public enum ClaimStatus {

    /** A student has asked for this bed; an admin has not decided yet. Expires after a TTL. */
    PENDING,

    /** An admin approved it. The student occupies the bed. */
    ALLOCATED,

    /** An admin took the bed out of circulation. student_id is NULL on these rows. */
    BLOCKED,

    // ---- terminal ----

    /** An admin said no, or a block auto-rejected it. */
    REJECTED,

    /** The student withdrew their own request. */
    CANCELLED,

    /** Nobody answered within the TTL, so the bed was freed automatically. */
    EXPIRED,

    /** An admin returned a blocked bed to circulation. */
    UNBLOCKED;

    /**
     * The statuses that occupy a bed. This set is the Java mirror of the WHERE clause on
     * uq_claim_live_per_bed - if one changes, the other must change with it, which is why they are
     * commented as a pair in both places.
     */
    public static final Set<ClaimStatus> LIVE_ON_BED = EnumSet.of(PENDING, ALLOCATED, BLOCKED);

    /** The statuses that tie up a student. Mirrors uq_claim_live_per_student. */
    public static final Set<ClaimStatus> LIVE_FOR_STUDENT = EnumSet.of(PENDING, ALLOCATED);

    public boolean isTerminal() {
        return !LIVE_ON_BED.contains(this);
    }
}
