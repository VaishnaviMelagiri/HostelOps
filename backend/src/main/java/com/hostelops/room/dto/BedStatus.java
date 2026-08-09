package com.hostelops.room.dto;

/**
 * The four states one bed can be in - the state machine at the centre of this project.
 *
 * <pre>
 *   AVAILABLE --(student requests)--> PENDING --(admin approves)--> ALLOCATED
 *                                        |
 *                                        +--(admin rejects / student cancels / expiry)--> AVAILABLE
 *   AVAILABLE --(admin blocks)--> BLOCKED --(admin unblocks)--> AVAILABLE
 * </pre>
 *
 * <p>These are never stored on the bed. They are read from the {@code bed_status} view, which
 * derives them from whether a live claim exists.
 */
public enum BedStatus {
    AVAILABLE,
    PENDING,
    ALLOCATED,
    BLOCKED
}
