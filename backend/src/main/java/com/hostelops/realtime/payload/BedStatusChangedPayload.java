package com.hostelops.realtime.payload;

import com.hostelops.room.dto.BedStatus;
import com.hostelops.room.dto.RoomStatus;

import java.time.Instant;

/**
 * Public per-floor message: one bed changed state.
 *
 * <p><strong>There is no studentId, no name, and no request id in this record - by construction.</strong>
 * This message is broadcast to every browser watching the floor, including other students. A
 * watcher learns that bed 113-B became PENDING; they can never learn who made it pending, because
 * the message has no field capable of carrying that. The privacy rule is enforced by the shape of
 * the type rather than by remembering to redact before publishing.
 *
 * @param roomStatus the whole-room roll-up, recomputed server-side. It could have been derived in
 *                   the browser from the beds it already holds, but then the roll-up rule would
 *                   exist in both Java and TypeScript and could drift.
 */
public record BedStatusChangedPayload(
        String event,
        Long bedId,
        Long roomId,
        Integer roomNumber,
        String wing,
        String floor,
        String bedLabel,
        BedStatus status,
        RoomStatus roomStatus,
        ChangeCause cause,
        Instant at) {

    public static final String EVENT = "BED_STATUS_CHANGED";

    public static BedStatusChangedPayload of(Long bedId, Long roomId, Integer roomNumber,
                                             String wing, String floor, String bedLabel,
                                             BedStatus status, RoomStatus roomStatus,
                                             ChangeCause cause, Instant at) {
        return new BedStatusChangedPayload(
                EVENT, bedId, roomId, roomNumber, wing, floor, bedLabel, status, roomStatus, cause, at);
    }
}
