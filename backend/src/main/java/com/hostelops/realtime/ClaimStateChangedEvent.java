package com.hostelops.realtime;

import com.hostelops.claim.ClaimStatus;
import com.hostelops.realtime.payload.ChangeCause;
import com.hostelops.room.dto.BedStatus;

import java.time.Instant;

/**
 * An internal, in-process Spring event: something happened to a bed.
 *
 * <p>Services publish this; {@link RealtimePublisher} decides what, if anything, goes out over
 * WebSocket. That indirection is what keeps ClaimService and AdminClaimService free of any
 * messaging code - they state what happened in domain terms and know nothing about STOMP, topics or
 * payload shapes. Swapping WebSocket for server-sent events later would touch one class.
 *
 * @param studentId the student affected, or null when nobody is (a block or unblock). Determines
 *                  whether a private notification is sent at all.
 * @param notifyStudent false when the student themselves caused the change - nobody needs a
 *                      notification telling them what they just did.
 */
public record ClaimStateChangedEvent(
        Long claimId,
        Long bedId,
        Long roomId,
        Integer roomNumber,
        String wing,
        String floor,
        String bedLabel,
        BedStatus bedStatus,
        ClaimStatus claimStatus,
        ChangeCause cause,
        Long studentId,
        boolean notifyStudent,
        String reason,
        Instant at) {

    /** A change caused by the student themselves: broadcast it, but do not notify them. */
    public static ClaimStateChangedEvent byStudent(Long claimId, Long bedId, Long roomId,
                                                   Integer roomNumber, String wing, String floor,
                                                   String bedLabel, BedStatus bedStatus,
                                                   ClaimStatus claimStatus, ChangeCause cause,
                                                   Long studentId) {
        return new ClaimStateChangedEvent(claimId, bedId, roomId, roomNumber, wing, floor, bedLabel,
                bedStatus, claimStatus, cause, studentId, false, null, Instant.now());
    }

    /** A change done TO a student - by an admin or by the expiry sweep. They get told. */
    public static ClaimStateChangedEvent affectingStudent(Long claimId, Long bedId, Long roomId,
                                                          Integer roomNumber, String wing, String floor,
                                                          String bedLabel, BedStatus bedStatus,
                                                          ClaimStatus claimStatus, ChangeCause cause,
                                                          Long studentId, String reason) {
        return new ClaimStateChangedEvent(claimId, bedId, roomId, roomNumber, wing, floor, bedLabel,
                bedStatus, claimStatus, cause, studentId, true, reason, Instant.now());
    }

    /** A change with no student involved at all: a maintenance block or unblock. */
    public static ClaimStateChangedEvent bedOnly(Long claimId, Long bedId, Long roomId,
                                                 Integer roomNumber, String wing, String floor,
                                                 String bedLabel, BedStatus bedStatus,
                                                 ChangeCause cause, String reason) {
        return new ClaimStateChangedEvent(claimId, bedId, roomId, roomNumber, wing, floor, bedLabel,
                bedStatus, null, cause, null, false, reason, Instant.now());
    }

    /** The public topic this change belongs on, e.g. {@code /topic/floors/B-FF}. */
    public String floorTopic() {
        return "/topic/floors/" + wing + "-" + floor;
    }
}
