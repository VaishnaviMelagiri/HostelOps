package com.hostelops.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One token that was invalidated before its natural expiry - i.e. somebody logged out.
 *
 * <p>The primary key is the token's own {@code jti} claim, so checking "is this token revoked?" is
 * a primary-key lookup, the cheapest query a database can answer.
 */
@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {

    @Id
    private UUID jti;

    /**
     * The token's own {@code exp}. After this instant the token is rejected on expiry alone, so the
     * row has no further purpose and {@link TokenRevocationService} deletes it. This is what keeps
     * a "denylist" from growing without bound - it only ever holds tokens that are both revoked
     * AND still otherwise valid.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false, insertable = false, updatable = false)
    private Instant revokedAt;

    protected RevokedToken() {
        // Required by JPA.
    }

    public RevokedToken(UUID jti, Instant expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }

    public UUID getJti() {
        return jti;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
