package com.hostelops.security;

import com.hostelops.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Creates and verifies the access tokens.
 *
 * <p>A JWT is three base64 segments joined by dots: a header, a payload of "claims", and a
 * signature. <strong>The payload is encoded, not encrypted</strong> - anyone holding the token can
 * read every claim in it. What the signature guarantees is that nobody <em>changed</em> them: alter
 * a single character of the payload and the signature no longer matches the secret only this server
 * knows. So claims may be public facts (user id, role) but never secrets.
 *
 * <p>Claims issued here:
 * <ul>
 *   <li>{@code sub} - the user's database id, the subject the token speaks for</li>
 *   <li>{@code role} - used only for logging/debugging; authority always comes from the
 *       database-loaded user, see {@link JwtAuthFilter}</li>
 *   <li>{@code jti} - a unique id for this individual token. This is the claim that makes logout
 *       possible: a revoked jti is remembered until the token would have expired anyway.</li>
 *   <li>{@code iat} / {@code exp} - issued-at and expiry</li>
 * </ul>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /**
     * Fallback signing key for local development only.
     *
     * <p>It is committed to the repository, which means it is not a secret and is not pretending to
     * be one. The alternative - generating a random key at each startup - would log everyone out on
     * every restart, which during development is pure friction. What matters is that using it is
     * loud (a WARN on every boot) and that production refuses to start without a real one.
     * HS256 requires at least 256 bits, hence the length.
     */
    private static final String DEV_ONLY_SECRET =
            "hostelops-local-development-signing-key-not-a-secret-do-not-deploy";

    private final SecretKey signingKey;
    private final Duration accessTokenTtl;

    public JwtService(
            @Value("${hostelops.jwt.secret:}") String configuredSecret,
            @Value("${hostelops.jwt.access-token-ttl:PT15M}") Duration accessTokenTtl) {

        this.accessTokenTtl = accessTokenTtl;

        String secret = configuredSecret;
        if (secret == null || secret.isBlank()) {
            log.warn("""
                    ============================================================================
                    JWT_SECRET is not set - falling back to the built-in DEVELOPMENT signing key.
                    This key is public (it lives in JwtService.java), so tokens signed with it can
                    be forged by anyone. Fine locally; never deploy with it.
                    Set a real one:  JWT_SECRET=$(openssl rand -base64 48)
                    ============================================================================""");
            secret = DEV_ONLY_SECRET;
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Fail at startup rather than on the first login. HMAC-SHA256 needs a key at least as
            // long as its output; a short key would be silently insecure.
            throw new IllegalStateException(
                    "JWT secret must be at least 32 bytes (256 bits); got " + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** A freshly minted token, plus the two facts logout later needs about it. */
    public record IssuedToken(String token, UUID jti, Instant expiresAt) {
    }

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenTtl);
        UUID jti = UUID.randomUUID();

        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .id(jti.toString())                       // the "jti" claim
                .claim("role", user.getRole().name())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();

        return new IssuedToken(token, jti, expiry);
    }

    /**
     * Verifies the signature and expiry, and returns the claims.
     *
     * @throws JwtException if the token is malformed, tampered with, signed by someone else, or
     *                      expired. The caller treats any of those the same way: not authenticated.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }
}
