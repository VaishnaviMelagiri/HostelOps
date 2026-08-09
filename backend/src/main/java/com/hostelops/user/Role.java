package com.hostelops.user;

/**
 * The three roles in HostelOps. Matches ck_users_role in V1__core_tables.sql.
 *
 * <p>A role is only ever a label on a user row. Authorization decisions are made against
 * {@link com.hostelops.security.Permission}s, never by comparing this enum - see
 * {@link com.hostelops.security.RolePermissions} for why that distinction earns its keep.
 */
public enum Role {

    /** Can browse the map, request a bed, cancel their own request, and see their own allocation. */
    STUDENT,

    /** Can see the pending queue with student identities, approve/reject, and block/unblock beds. */
    ADMIN,

    /**
     * Read-only. Can browse the map and open room popovers - nothing else.
     *
     * <p>Exists so a reviewer can explore the deployed app without creating state: no pending
     * request left on a bed, no cleanup between demos. It is a read-only lens on the same system,
     * not a fourth code path - a GUEST authenticates through exactly the same login as everyone.
     */
    GUEST
}
