package com.hostelops.room.dto;

import java.util.List;

/**
 * One room as the floor map needs it: what it is, where to draw it, and the state of its beds.
 *
 * @param roomId       database id, used by later phases to request a bed
 * @param roomNumber   the number on the actual door
 * @param roomType     "Single" or "Double"
 * @param bathroomType "Attached" or "Common"
 * @param capacity     1 or 2 - equals beds.size()
 * @param cell         grid position, not pixels
 * @param roomStatus   colour hint for the whole cell
 * @param beds         the authoritative per-bed truth
 */
public record RoomCellDto(
        Long roomId,
        Integer roomNumber,
        String roomType,
        String bathroomType,
        int capacity,
        CellDto cell,
        RoomStatus roomStatus,
        List<BedStateDto> beds) {
}
