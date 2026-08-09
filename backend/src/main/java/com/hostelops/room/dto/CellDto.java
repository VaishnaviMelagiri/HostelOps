package com.hostelops.room.dto;

/**
 * Where to draw a room, as grid coordinates - column and row, both 1-based.
 *
 * <p>Not pixels, on purpose. The backend says "this room is 3rd along the top row"; the frontend
 * decides how big a cell is, how much gap to leave, and whether to draw with SVG, canvas or CSS
 * grid. Swapping the rendering approach later needs no backend change at all.
 */
public record CellDto(int col, int row) {
}
