package com.hostelops.security;

import com.hostelops.user.Role;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.hostelops.security.Permission.*;

/**
 * The single source of truth for "which role may do what" - the Phase 0 permission matrix, in code.
 *
 * <p>This is the ONLY place in the application where a {@link Role} is mentioned in an
 * authorization context. Everything downstream - the JWT's authorities, {@code @PreAuthorize}
 * expressions, the frontend's permission list - is derived from this map. Change a role's
 * capabilities here and every check in the system follows, with nothing to hunt down.
 */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> BY_ROLE = new EnumMap<>(Role.class);

    static {
        // GUEST: read-only. Exactly one permission. Anything else -> 403 at the permission check,
        // before a service method or the database is ever touched.
        BY_ROLE.put(Role.GUEST, EnumSet.of(
                ROOM_READ));

        // STUDENT: browse, request one bed, cancel their own request, read their own allocation.
        // Note the absence of any admin permission - a student cannot see another student's
        // identity anywhere in the API.
        BY_ROLE.put(Role.STUDENT, EnumSet.of(
                ROOM_READ,
                REQUEST_CREATE,
                REQUEST_CANCEL_OWN,
                ALLOCATION_READ_OWN));

        // ADMIN: resolve requests and manage bed availability. Deliberately NOT a superset of
        // STUDENT - an admin has no ALLOCATION_READ_OWN because an admin has no allocation, and
        // no REQUEST_CREATE because staff do not occupy student beds. Modelling admin as
        // "student plus extras" would quietly grant permissions nobody intended.
        BY_ROLE.put(Role.ADMIN, EnumSet.of(
                ROOM_READ,
                REQUEST_QUEUE_READ,
                REQUEST_APPROVE,
                REQUEST_REJECT,
                BED_BLOCK,
                BED_UNBLOCK,
                OCCUPANCY_READ));
    }

    private RolePermissions() {
        // Utility class - never instantiated.
    }

    /**
     * @return the permissions granted to a role; an empty set for a role with no entry, so a role
     *         added to the enum without a decision here grants nothing rather than everything.
     *         Failing closed is the only safe default for authorization.
     */
    public static Set<Permission> forRole(Role role) {
        return Collections.unmodifiableSet(
                BY_ROLE.getOrDefault(role, EnumSet.noneOf(Permission.class)));
    }
}
