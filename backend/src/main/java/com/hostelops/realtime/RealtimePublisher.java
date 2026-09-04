package com.hostelops.realtime;

import com.hostelops.realtime.payload.AdminQueueChangedPayload;
import com.hostelops.realtime.payload.BedStatusChangedPayload;
import com.hostelops.realtime.payload.RequestResolvedPayload;
import com.hostelops.room.dto.BedStatus;
import com.hostelops.room.dto.RoomStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an internal {@link ClaimStateChangedEvent} into messages on the two channels.
 *
 * <h2>The one thing that matters most here: AFTER_COMMIT</h2>
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} holds the event until the database
 * transaction that raised it has actually committed. Publish inside the transaction instead and a
 * later rollback leaves every connected browser showing a bed status that does not exist in the
 * database - with nothing to ever correct it, because the rollback is silent. A student would see a
 * bed as theirs when the request was never saved.
 *
 * <p>On rollback the event is simply dropped, which is correct: nothing happened, so nothing should
 * be announced.
 *
 * <h2>Why a failure here cannot undo the write</h2>
 * By the time this runs the transaction is committed and gone. If the broker were unreachable, an
 * exception here must not be allowed to look like a failed request - the request DID succeed. So
 * everything is wrapped: a message that cannot be delivered is logged, and the client picks up the
 * true state on its next fetch or reconnect. WebSocket is an optimisation for liveness, never the
 * source of truth.
 */
@Component
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    /** Suffix of the private destination. The /user prefix is added by convertAndSendToUser. */
    private static final String STUDENT_QUEUE = "/queue/requests";

    private final SimpMessagingTemplate messaging;
    private final JdbcTemplate jdbcTemplate;

    public RealtimePublisher(SimpMessagingTemplate messaging, JdbcTemplate jdbcTemplate) {
        this.messaging = messaging;
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onClaimStateChanged(ClaimStateChangedEvent event) {
        publishToFloor(event);
        publishToStudent(event);
        publishToAdminQueue(event);
    }

    /** Public channel: everyone browsing this floor sees the bed change colour. */
    private void publishToFloor(ClaimStateChangedEvent event) {
        try {
            BedStatusChangedPayload payload = BedStatusChangedPayload.of(
                    event.bedId(), event.roomId(), event.roomNumber(),
                    event.wing(), event.floor(), event.bedLabel(),
                    event.bedStatus(), rollUpRoomStatus(event.roomId()),
                    event.cause(), event.at());

            messaging.convertAndSend(event.floorTopic(), payload);
            log.debug("Published {} for bed {} to {}", event.cause(), event.bedId(), event.floorTopic());
        } catch (Exception e) {
            log.error("Could not publish bed status change for bed {}; clients will see it on their "
                    + "next fetch", event.bedId(), e);
        }
    }

    /**
     * Private channel: only the student whose request was decided.
     *
     * <p>{@code convertAndSendToUser(name, "/queue/requests", ...)} rewrites the destination into a
     * session-specific one before it reaches the broker, so a client can only ever subscribe to its
     * OWN {@code /user/queue/requests}. There is no destination string another student could guess
     * to eavesdrop - the isolation is structural, the same shape of argument as the partial unique
     * index rather than a check somebody has to remember to write.
     */
    private void publishToStudent(ClaimStateChangedEvent event) {
        if (!event.notifyStudent() || event.studentId() == null || event.claimStatus() == null) {
            return;
        }
        try {
            RequestResolvedPayload payload = RequestResolvedPayload.of(
                    event.claimId(), event.bedId(), event.roomNumber(), event.bedLabel(),
                    event.wing(), event.floor(), event.claimStatus(), event.reason(), event.at());

            messaging.convertAndSendToUser(String.valueOf(event.studentId()), STUDENT_QUEUE, payload);
            log.debug("Notified student {} that request {} is {}",
                    event.studentId(), event.claimId(), event.claimStatus());
        } catch (Exception e) {
            log.error("Could not notify student {} about request {}",
                    event.studentId(), event.claimId(), e);
        }
    }

    /**
     * Third channel: tell admins their queue moved, without telling them anything about it.
     *
     * <p>Signal only. The payload cannot carry a student, a request id or a bed, so a subscriber
     * who should not be there learns nothing beyond "the queue is busy". The page refetches
     * {@code /api/admin/requests}, which is permission-checked and is the single place identities
     * are disclosed.
     *
     * <p>Subscription to this topic is restricted to REQUEST_QUEUE_READ at SUBSCRIBE time in
     * StompAuthChannelInterceptor - the only destination in the project that is role-gated, because
     * it is the only topic whose existence is meaningful to only one role.
     */
    private void publishToAdminQueue(ClaimStateChangedEvent event) {
        if (!event.affectsPendingQueue()) {
            return;
        }
        try {
            messaging.convertAndSend(AdminQueueChangedPayload.TOPIC,
                    AdminQueueChangedPayload.of(event.cause(), event.at()));
            log.debug("Signalled admin queue change ({})", event.cause());
        } catch (Exception e) {
            log.error("Could not signal the admin queue change", e);
        }
    }

    /**
     * Recomputes the whole-room status from the beds as they now stand.
     *
     * <p>Runs outside any transaction - this listener fires after commit - which is fine for a
     * plain read. One small query per event, and events happen at human pace, not in a loop.
     */
    private RoomStatus rollUpRoomStatus(Long roomId) {
        List<BedStatus> statuses = new ArrayList<>();
        jdbcTemplate.query("SELECT status FROM bed_status WHERE room_id = ?",
                rs -> { statuses.add(BedStatus.valueOf(rs.getString("status"))); },
                roomId);
        return RoomStatus.rollUp(statuses);
    }
}
