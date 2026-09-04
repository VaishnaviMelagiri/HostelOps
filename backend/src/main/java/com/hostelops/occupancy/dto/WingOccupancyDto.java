package com.hostelops.occupancy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Bed counts for one wing, by status.
 *
 * <p>Counts only - no identities anywhere, so this endpoint discloses nothing about who is where.
 */
public record WingOccupancyDto(
        String wing,
        String block,
        String roomType,
        String bathroomType,
        int totalBeds,
        int available,
        int pending,
        int allocated,
        int blocked) {

    /**
     * Share of beds actually occupied, 0-100. Excludes blocked beds - they are not lettable, so
     * counting them as "not yet occupied" would understate real occupancy.
     *
     * <p>{@code @JsonProperty} is required, and its absence is an easy thing to miss. Jackson
     * serialises a record's COMPONENTS plus anything shaped like a bean getter; a plain
     * {@code occupancyPercent()} is neither, so it was silently left out of the JSON and the
     * frontend rendered "undefined%".
     *
     * <p>Worth contrasting with HealthDto.isUp() in Phase 1, which had the opposite problem: an
     * {@code isX()} method DOES look like a getter, so it was auto-included when it should not have
     * been and needed {@code @JsonIgnore}. Two surprises in opposite directions from the same rule -
     * which is why both are annotated explicitly rather than left to inference.
     */
    @JsonProperty("occupancyPercent")
    public int occupancyPercent() {
        int lettable = totalBeds - blocked;
        return lettable <= 0 ? 0 : Math.round((allocated * 100f) / lettable);
    }
}
