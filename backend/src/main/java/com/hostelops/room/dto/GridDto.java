package com.hostelops.room.dto;

/** The size of the grid the cells sit in, so the renderer knows the canvas before drawing. */
public record GridDto(int columns, int rows) {
}
