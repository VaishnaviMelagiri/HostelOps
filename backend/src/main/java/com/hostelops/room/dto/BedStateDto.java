package com.hostelops.room.dto;

/**
 * One bed's public state.
 *
 * <p>Deliberately contains NO student id, name, or request id - for every role, including ADMIN.
 * A student browsing a floor learns that bed 113-B is taken, never who took it. The privacy rule is
 * enforced by the shape of this object rather than by remembering to strip fields later: there is
 * no field here to leak. Admins get identities from the pending queue endpoint instead (Phase 5),
 * which is one place to guard rather than two.
 */
public record BedStateDto(Long bedId, String bedLabel, BedStatus status) {
}
