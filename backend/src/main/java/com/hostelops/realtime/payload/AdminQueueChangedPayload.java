package com.hostelops.realtime.payload;

import java.time.Instant;

/**
 * Third channel: the admin pending queue changed. A SIGNAL, not the data.
 *
 * <p>Destination {@code /topic/admin/queue}. It says only "something in the queue moved" - never
 * who, never which student, never even which request. The admin's page reacts by refetching
 * {@code GET /api/admin/requests} over authenticated REST, where the identity disclosure is already
 * confined and already permission-checked.
 *
 * <p>That indirection is the same pattern as roommate visibility on the private channel: the socket
 * carries the nudge, the authenticated request carries the names. It matters here because a topic
 * is a broadcast - one wrongly-permitted subscriber would otherwise receive a live feed of every
 * student's request as it happened. With a signal-only payload, the worst a leaked subscription
 * reveals is that the queue is busy.
 *
 * @param cause what moved it, so the UI can decide whether to refetch quietly or flag something new
 */
public record AdminQueueChangedPayload(String event, ChangeCause cause, Instant at) {

    public static final String EVENT = "ADMIN_QUEUE_CHANGED";

    /** The only destination gated by role at SUBSCRIBE time - see StompAuthChannelInterceptor. */
    public static final String TOPIC = "/topic/admin/queue";

    public static AdminQueueChangedPayload of(ChangeCause cause, Instant at) {
        return new AdminQueueChangedPayload(EVENT, cause, at);
    }
}
