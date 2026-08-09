package com.hostelops.auth.dto;

import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.Permission;
import com.hostelops.security.RolePermissions;
import com.hostelops.user.Role;
import com.hostelops.user.User;

import java.util.List;

/**
 * Who the caller is, as returned by {@code POST /api/auth/login} and {@code GET /api/auth/me}.
 *
 * <p>Both endpoints return the same shape on purpose, so the frontend has one type for "the signed
 * in user" rather than two that drift apart.
 *
 * <p>{@code permissions} is an addition to the Phase 0 contract, which listed only id/fullName/role.
 * It is here because the frontend has to decide what to render - a GUEST must not be shown a
 * "Request this bed" button it will only ever get a 403 from. Sending the resolved permission list
 * means the UI applies exactly the same rules as the server, from the same source
 * ({@link RolePermissions}), instead of reimplementing "what can a guest do" in TypeScript where it
 * could silently drift. It is a UI hint only: the server re-checks every request regardless, so a
 * tampered client gains nothing.
 *
 * <p>Note what is NOT here: no password hash, no {@code active} flag, no created timestamp. A DTO
 * is a deliberate, minimal projection of an entity - never the entity itself.
 */
public record UserDto(
        Long id,
        String email,
        String fullName,
        Role role,
        String studentCode,
        String course,
        List<String> permissions) {

    public static UserDto from(User user) {
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getStudentCode(),
                user.getCourse(),
                permissionNames(user.getRole()));
    }

    /** Built from the authenticated principal, which is what the /me endpoint already holds. */
    public static UserDto from(AppUserPrincipal principal, String studentCode, String course) {
        return new UserDto(
                principal.getId(),
                principal.getEmail(),
                principal.getFullName(),
                principal.getRole(),
                studentCode,
                course,
                permissionNames(principal.getRole()));
    }

    private static List<String> permissionNames(Role role) {
        return RolePermissions.forRole(role).stream()
                .map(Permission::name)
                .sorted()
                .toList();
    }
}
