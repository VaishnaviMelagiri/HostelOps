package com.hostelops.claim.dto;

/**
 * A roommate's name and course. Nothing else, ever.
 *
 * <p>Two fields, and the absence of the rest is the point: no email, no student code, no phone, no
 * id. Someone sharing a room needs to know who they are sharing with; they do not need a way to
 * contact them before they have met, and the hostel has no business handing that over.
 *
 * <p>Populated from a repository projection that selects only these two columns, so the {@code User}
 * entity never reaches this layer on this path. The privacy rule is enforced by what the query is
 * capable of returning rather than by remembering to strip fields - a later refactor cannot widen
 * it by accident.
 */
public record RoommateDto(String fullName, String course) {
}
