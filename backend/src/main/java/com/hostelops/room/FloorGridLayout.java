package com.hostelops.room;

import com.hostelops.room.dto.CellDto;
import com.hostelops.room.dto.GridDto;

/**
 * Turns a room's position on a floor into grid coordinates.
 *
 * <p><strong>An honest note about what this is.</strong> The source data gives each room a
 * {@code position_in_floor} - a sequence number, 1..n along the corridor. It does not give real
 * coordinates, because the floor-plan posters they were read from do not provide any. So any 2D
 * arrangement is a <em>presentation convention</em>, not the literal geometry of the building.
 *
 * <p>The convention chosen: two rows, like rooms facing each other across a corridor. The first
 * half of the sequence runs along the top row, the second half along the bottom. That reads as a
 * building rather than as a list, without pretending to know more than the data supports.
 *
 * <p>Isolated in its own class with no Spring or database involvement, so it can be unit tested
 * directly - the layout arithmetic is exactly the sort of thing that is easy to get subtly wrong
 * (off-by-one on odd counts) and easy to prove with a handful of assertions.
 */
public final class FloorGridLayout {

    private FloorGridLayout() {
        // Utility class.
    }

    /**
     * @param roomsOnFloor how many rooms this floor has (12, 16 or 22 in the real building)
     */
    public static GridDto gridFor(int roomsOnFloor) {
        if (roomsOnFloor <= 1) {
            return new GridDto(Math.max(roomsOnFloor, 1), 1);
        }
        int rows = 2;
        // Round up, so an odd count puts the extra room on the top row rather than losing it.
        int columns = (roomsOnFloor + rows - 1) / rows;
        return new GridDto(columns, rows);
    }

    /**
     * @param position      1-based position along the corridor
     * @param roomsOnFloor  total rooms on this floor
     */
    public static CellDto cellFor(int position, int roomsOnFloor) {
        GridDto grid = gridFor(roomsOnFloor);
        if (position <= grid.columns()) {
            return new CellDto(position, 1);
        }
        return new CellDto(position - grid.columns(), 2);
    }
}
