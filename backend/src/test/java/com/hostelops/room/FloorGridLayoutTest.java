package com.hostelops.room;

import com.hostelops.room.dto.CellDto;
import com.hostelops.room.dto.GridDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grid arithmetic. Pure functions, so no Spring and no database.
 *
 * <p>Small, dull-looking assertions - and exactly the sort of code that silently loses a room to an
 * off-by-one when the count is odd. Cheap to prove, annoying to debug through a browser.
 */
class FloorGridLayoutTest {

    @ParameterizedTest(name = "{0} rooms -> {1} columns x {2} rows")
    @CsvSource({
            "12, 6, 2",   // wings B and F
            "16, 8, 2",   // wings A, C and E
            "22, 11, 2",  // wing D, the widest floor in the building
            "1,  1, 1",
            "2,  1, 2",
            "7,  4, 2",   // odd count: the extra room goes on the top row
    })
    @DisplayName("grid is two rows, columns rounded up")
    void gridShape(int rooms, int expectedColumns, int expectedRows) {
        GridDto grid = FloorGridLayout.gridFor(rooms);

        assertThat(grid.columns()).isEqualTo(expectedColumns);
        assertThat(grid.rows()).isEqualTo(expectedRows);
    }

    @Test
    @DisplayName("the first half runs along the top row, the second along the bottom")
    void positionsRunAcrossThenBack() {
        int rooms = 12;

        assertThat(FloorGridLayout.cellFor(1, rooms)).isEqualTo(new CellDto(1, 1));
        assertThat(FloorGridLayout.cellFor(6, rooms)).isEqualTo(new CellDto(6, 1));
        assertThat(FloorGridLayout.cellFor(7, rooms)).isEqualTo(new CellDto(1, 2));
        assertThat(FloorGridLayout.cellFor(12, rooms)).isEqualTo(new CellDto(6, 2));
    }

    @ParameterizedTest
    @CsvSource({"12", "16", "22", "7", "1"})
    @DisplayName("every room gets its own cell - none overlap, none fall outside the grid")
    void everyRoomLandsSomewhereUnique(int rooms) {
        GridDto grid = FloorGridLayout.gridFor(rooms);
        Set<CellDto> seen = new HashSet<>();

        for (int position = 1; position <= rooms; position++) {
            CellDto cell = FloorGridLayout.cellFor(position, rooms);

            assertThat(cell.col()).as("column for position %d", position)
                    .isBetween(1, grid.columns());
            assertThat(cell.row()).as("row for position %d", position)
                    .isBetween(1, grid.rows());
            assertThat(seen.add(cell))
                    .as("position %d landed on a cell already used", position)
                    .isTrue();
        }

        assertThat(seen).hasSize(rooms);
    }
}
