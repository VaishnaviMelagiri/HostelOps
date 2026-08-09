package com.hostelops.security;

import com.hostelops.user.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Phase 0 permission matrix.
 *
 * <p>These assertions look almost too simple to be worth writing, but they are the executable form
 * of a security decision. A permission accidentally added to the wrong role is the kind of change
 * that reviews easily and breaks quietly - nothing fails, a user simply gains an ability nobody
 * granted them. Here it fails a build instead.
 */
class RolePermissionsTest {

    @Test
    @DisplayName("GUEST can only read rooms - nothing else, at all")
    void guestIsReadOnly() {
        Set<Permission> guest = RolePermissions.forRole(Role.GUEST);

        assertThat(guest).containsExactly(Permission.ROOM_READ);
        assertThat(guest).doesNotContain(
                Permission.REQUEST_CREATE,
                Permission.REQUEST_CANCEL_OWN,
                Permission.ALLOCATION_READ_OWN,
                Permission.REQUEST_APPROVE,
                Permission.BED_BLOCK);
    }

    @Test
    @DisplayName("STUDENT can request and cancel, but holds no admin permission")
    void studentCannotActAsAdmin() {
        Set<Permission> student = RolePermissions.forRole(Role.STUDENT);

        assertThat(student).contains(
                Permission.ROOM_READ,
                Permission.REQUEST_CREATE,
                Permission.REQUEST_CANCEL_OWN,
                Permission.ALLOCATION_READ_OWN);
        assertThat(student).doesNotContain(
                Permission.REQUEST_QUEUE_READ,
                Permission.REQUEST_APPROVE,
                Permission.REQUEST_REJECT,
                Permission.BED_BLOCK,
                Permission.BED_UNBLOCK,
                Permission.OCCUPANCY_READ);
    }

    @Test
    @DisplayName("ADMIN resolves requests but is NOT a superset of STUDENT")
    void adminIsNotASupersetOfStudent() {
        Set<Permission> admin = RolePermissions.forRole(Role.ADMIN);

        assertThat(admin).contains(
                Permission.REQUEST_QUEUE_READ,
                Permission.REQUEST_APPROVE,
                Permission.REQUEST_REJECT,
                Permission.BED_BLOCK,
                Permission.BED_UNBLOCK,
                Permission.OCCUPANCY_READ);

        // An admin has no allocation of their own and does not occupy student beds. Modelling admin
        // as "student plus extras" would silently grant these.
        assertThat(admin).doesNotContain(
                Permission.REQUEST_CREATE,
                Permission.REQUEST_CANCEL_OWN,
                Permission.ALLOCATION_READ_OWN);
    }

    @Test
    @DisplayName("only ROOM_READ is shared by all three roles")
    void everyRoleCanBrowseTheMap() {
        for (Role role : Role.values()) {
            assertThat(RolePermissions.forRole(role))
                    .as("%s should be able to browse the floor map", role)
                    .contains(Permission.ROOM_READ);
        }
    }

    @Test
    @DisplayName("the returned set is immutable, so no caller can widen a role at runtime")
    void permissionsCannotBeMutated() {
        assertThatThrownBy(() -> RolePermissions.forRole(Role.GUEST).add(Permission.BED_BLOCK))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
