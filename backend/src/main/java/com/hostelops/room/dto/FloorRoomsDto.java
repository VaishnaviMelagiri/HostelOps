package com.hostelops.room.dto;

import java.util.List;

/**
 * Everything needed to draw one floor of one wing.
 *
 * @param floorLevel ordering within this wing only - never compare it across wings, because A-D
 *                   start at GF=0 while E-F have a basement and start at BAS=0
 */
public record FloorRoomsDto(
        String wing,
        String floor,
        int floorLevel,
        GridDto grid,
        List<RoomCellDto> rooms) {
}
