package com.hostelops.room.dto;

import java.util.Collection;

/**
 * A one-glance summary of a whole room, used only to colour its cell on the map.
 *
 * <p>The authoritative truth is always the per-bed list. This exists so a Double room with one bed
 * taken looks different at a glance from one with both taken, without the renderer having to
 * inspect every bed to pick a colour.
 */
public enum RoomStatus {

    /** No bed in this room is claimed by anyone. */
    AVAILABLE,

    /** Some but not all beds are taken - only possible in a Double. */
    PARTIAL,

    /** Every bed is pending or allocated. */
    FULL,

    /** Every bed is out of circulation for maintenance. */
    BLOCKED;

    public static RoomStatus rollUp(Collection<BedStatus> bedStatuses) {
        if (bedStatuses.isEmpty()) {
            return AVAILABLE;
        }
        if (bedStatuses.stream().allMatch(s -> s == BedStatus.BLOCKED)) {
            return BLOCKED;
        }
        boolean anyFree = bedStatuses.stream().anyMatch(s -> s == BedStatus.AVAILABLE);
        boolean anyTaken = bedStatuses.stream()
                .anyMatch(s -> s == BedStatus.PENDING || s == BedStatus.ALLOCATED || s == BedStatus.BLOCKED);

        if (!anyTaken) {
            return AVAILABLE;
        }
        return anyFree ? PARTIAL : FULL;
    }
}
