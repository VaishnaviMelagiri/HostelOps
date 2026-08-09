package com.hostelops.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Makes logout mean something.
 *
 * <p>The problem this solves is inherent to JWTs and worth understanding, because "just use JWTs"
 * usually skips it. A signed token is self-contained: the server can verify it without storing
 * anything, which is exactly why JWTs scale well. But it also means the server has no record to
 * delete when a user logs out - the token is already in their hands and stays valid until it
 * expires. "Log out" would be a purely client-side gesture: delete it from this browser and hope no
 * copy exists anywhere else.
 *
 * <p>Two mechanisms fix that together, and neither is sufficient alone:
 * <ol>
 *   <li><strong>Short-lived tokens</strong> (15 minutes) bound how long a leaked token is useful.</li>
 *   <li><strong>This denylist</strong> closes that window immediately on logout.</li>
 * </ol>
 *
 * <p>The honest trade-off: consulting a database on every authenticated request gives up some of
 * the statelessness that made JWTs attractive. That is a deliberate exchange - being able to
 * actually revoke a session is worth one primary-key lookup, and the table stays small because
 * entries are deleted as soon as the token would have expired regardless.
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RevokedTokenRepository repository;

    public TokenRevocationService(RevokedTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * Revokes a token. Idempotent: revoking an already-revoked token is a no-op, so a double-click
     * on "log out" or a retried request cannot fail.
     *
     * @param jti       the token's unique id
     * @param expiresAt the token's own expiry, so the row can be cleaned up afterwards
     */
    @Transactional
    public void revoke(UUID jti, Instant expiresAt) {
        if (repository.existsById(jti)) {
            return;
        }
        repository.save(new RevokedToken(jti, expiresAt));
        log.debug("Revoked token {} (would have expired at {})", jti, expiresAt);
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(UUID jti) {
        return repository.existsById(jti);
    }

    /**
     * Deletes denylist entries for tokens that are past their own expiry.
     *
     * <p>{@code @Scheduled} runs this on a timer on a background thread. {@code fixedDelay} means
     * "wait this long after the previous run finishes", so a slow run can never overlap itself.
     *
     * <p>Deleting these rows is safe by construction: once a token is past its {@code exp}, the
     * signature check rejects it on expiry alone and the denylist entry adds nothing.
     */
    @Scheduled(
            initialDelayString = "${hostelops.jwt.revocation-cleanup-initial-delay:PT1M}",
            fixedDelayString = "${hostelops.jwt.revocation-cleanup-interval:PT1H}")
    @Transactional
    public void purgeExpiredRevocations() {
        int removed = repository.deleteExpiredBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired token revocation(s)", removed);
        }
    }
}
