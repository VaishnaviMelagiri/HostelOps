package com.hostelops.security;

import com.hostelops.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token issuing and verification. Plain JUnit - no Spring context, no database.
 */
class JwtServiceTest {

    private static final String SECRET = "a-test-signing-key-that-is-comfortably-over-32-bytes";

    private static User studentWithId(long id) {
        User user = User.student("student@hostelops.demo", "irrelevant-hash",
                "Demo Student", "1MS22CS001", "B.E. Computer Science");
        // The id is normally assigned by the database on insert; set it directly so this stays a
        // pure unit test.
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private JwtService serviceWithTtl(Duration ttl) {
        return new JwtService(SECRET, ttl);
    }

    @Test
    @DisplayName("a freshly issued token carries the subject, role and a unique jti")
    void issuesAReadableToken() {
        JwtService service = serviceWithTtl(Duration.ofMinutes(15));
        JwtService.IssuedToken issued = service.issue(studentWithId(7L));

        Claims claims = service.parse(issued.token());

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get("role", String.class)).isEqualTo("STUDENT");
        assertThat(claims.get("email", String.class)).isEqualTo("student@hostelops.demo");
        assertThat(UUID.fromString(claims.getId())).isEqualTo(issued.jti());
        assertThat(issued.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("every token gets its own jti, so revoking one never revokes another")
    void jtiIsUniquePerToken() {
        JwtService service = serviceWithTtl(Duration.ofMinutes(15));
        User user = studentWithId(7L);

        assertThat(service.issue(user).jti()).isNotEqualTo(service.issue(user).jti());
    }

    @Test
    @DisplayName("a token signed with a different secret is rejected")
    void rejectsAForgedSignature() {
        JwtService issuer = serviceWithTtl(Duration.ofMinutes(15));
        JwtService differentServer = new JwtService(
                "a-completely-different-key-also-over-32-bytes-long", Duration.ofMinutes(15));

        String token = issuer.issue(studentWithId(7L)).token();

        // This is the guarantee the whole scheme rests on: possessing a token is not enough,
        // it has to have been signed by us.
        assertThatThrownBy(() -> differentServer.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token whose payload was edited is rejected")
    void rejectsATamperedPayload() {
        JwtService service = serviceWithTtl(Duration.ofMinutes(15));
        String token = service.issue(studentWithId(7L)).token();

        // Flip a character in the payload segment (header.payload.signature).
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'e' ? 'f' : 'e';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is rejected even though its signature is perfectly valid")
    void rejectsAnExpiredToken() {
        // Negative TTL: issued already past its expiry.
        JwtService service = serviceWithTtl(Duration.ofSeconds(-60));
        String token = service.issue(studentWithId(7L)).token();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("a secret shorter than 256 bits fails at startup, not at first login")
    void refusesAWeakSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("a blank secret falls back to the development key rather than failing")
    void fallsBackToTheDevelopmentKey() {
        // Convenience for local development; JwtService logs a loud warning when this happens.
        JwtService service = new JwtService("", Duration.ofMinutes(15));
        assertThat(service.parse(service.issue(studentWithId(1L)).token()).getSubject()).isEqualTo("1");
    }
}
