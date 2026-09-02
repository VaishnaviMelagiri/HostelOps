package com.hostelops.claim.dto;

/**
 * A student's identity, as shown to an ADMIN on the pending queue.
 *
 * <p>This is the ONLY place in the API where one person's identity is shown to another, and it
 * exists because an admin cannot sensibly approve a blank request - they need to know who is asking
 * for which bed. Keeping it to a single endpoint means identity disclosure has exactly one place to
 * review rather than being scattered across the map, the popover and the queue.
 *
 * <p>Four fields, no more: no email, no password hash, no created timestamp. An admin deciding a
 * bed request has no need for a student's email address.
 */
public record StudentSummaryDto(Long id, String fullName, String studentCode, String course) {
}
