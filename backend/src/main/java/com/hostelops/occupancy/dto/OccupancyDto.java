package com.hostelops.occupancy.dto;

import java.util.List;

/**
 * Occupancy across the whole building, and per wing.
 *
 * <p>Every number is computed from the {@code bed_status} view at request time, so it cannot
 * disagree with the map. Phase 10 (stretch) puts a Redis cache in front of exactly this - which is
 * only safe because the uncached answer is already the authoritative one.
 */
public record OccupancyDto(
        int totalBeds,
        int available,
        int pending,
        int allocated,
        int blocked,
        List<WingOccupancyDto> byWing) {
}
