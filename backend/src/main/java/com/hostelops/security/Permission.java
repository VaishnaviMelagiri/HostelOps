package com.hostelops.security;

/**
 * What a user is allowed to DO, as opposed to what they ARE.
 *
 * <p>Every authorization check in this codebase names one of these, e.g.
 * {@code @PreAuthorize("hasAuthority('REQUEST_APPROVE')")}, and never
 * {@code if (user.getRole() == Role.ADMIN)}.
 *
 * <p>The payoff is concrete rather than theoretical: the GUEST role was added to this project after
 * the API surface was already designed, and it required a single new entry in
 * {@link RolePermissions} - zero authorization call sites changed. Adding a warden role later is
 * the same shape of change: data, not a grep through controllers.
 */
public enum Permission {

    /** Browse wings, floors and rooms. Held by all three roles - the payload carries no identities. */
    ROOM_READ,

    /** Request an available bed. */
    REQUEST_CREATE,

    /** Cancel one's own pending request. */
    REQUEST_CANCEL_OWN,

    /** Read GET /api/me/allocation - one's own allocation and, when earned, a roommate's name. */
    ALLOCATION_READ_OWN,

    /** Read the pending queue, including student identities. */
    REQUEST_QUEUE_READ,

    /** Approve a pending request. */
    REQUEST_APPROVE,

    /** Reject a pending request. */
    REQUEST_REJECT,

    /** Take an available bed out of circulation. */
    BED_BLOCK,

    /** Return a blocked bed to circulation. */
    BED_UNBLOCK,

    /** Read aggregate occupancy counts. */
    OCCUPANCY_READ
}
