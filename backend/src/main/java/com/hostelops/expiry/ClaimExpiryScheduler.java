package com.hostelops.expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ClaimExpiryService#sweep()} on a timer.
 *
 * <p>A thin trigger, on purpose: the scheduling concern (how often) is kept apart from the business
 * rule (what expiring means), so a test can call the sweep directly instead of waiting for a clock.
 *
 * <p>{@code fixedDelay} means "wait this long AFTER the previous run finishes", as opposed to
 * {@code fixedRate}, which starts runs on a fixed cadence regardless. With fixedRate, a sweep that
 * took longer than the interval would overlap with the next one; with fixedDelay it cannot.
 *
 * <p>The interval only affects how promptly a bed is freed, never whether it is. Each request
 * carries its own {@code expires_at}, so a sweep that runs late expires exactly the same rows -
 * just a few minutes later than it might have.
 */
@Component
public class ClaimExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClaimExpiryScheduler.class);

    private final ClaimExpiryService expiryService;

    public ClaimExpiryScheduler(ClaimExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(
            initialDelayString = "${hostelops.expiry.initial-delay:PT30S}",
            fixedDelayString = "${hostelops.expiry.sweep-interval:PT5M}")
    public void sweepExpiredRequests() {
        try {
            expiryService.sweep();
        } catch (Exception e) {
            // A scheduled method that throws is not retried, and by default Spring logs it and
            // moves on - but only for THIS run. Catching it here keeps one bad sweep (a dropped
            // connection, say) from being mistaken for a crash, and guarantees the next tick still
            // happens. Logged at ERROR because a repeatedly failing sweep means beds stay frozen.
            log.error("Expiry sweep failed; the next run will retry", e);
        }
    }
}
