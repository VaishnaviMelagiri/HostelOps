package com.hostelops.room.dto;

import java.util.List;

/**
 * One wing, for the picker at the top of the map.
 *
 * <p>Built entirely from the {@code rooms} table rather than read from wings-summary.json. The JSON
 * file seeded the database and its job is done; deriving the summary from the rows themselves means
 * the counts shown can never disagree with the rooms actually stored.
 *
 * @param floors floor labels ordered bottom to top, e.g. [BAS, GF, FF, SF, TF]
 */
public record WingSummaryDto(
        String wing,
        String block,
        String roomType,
        String bathroomType,
        List<String> floors,
        int roomsPerFloor,
        int totalRooms,
        int totalBeds) {
}
