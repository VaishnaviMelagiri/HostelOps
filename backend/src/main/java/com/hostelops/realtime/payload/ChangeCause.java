package com.hostelops.realtime.payload;

/**
 * What made a bed change state.
 *
 * <p>Sent so the UI can react differently - a quiet repaint for someone else's request, a toast for
 * your own approval - without a second round trip to work out what happened.
 */
public enum ChangeCause {
    REQUESTED,
    APPROVED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    BLOCKED,
    UNBLOCKED
}
