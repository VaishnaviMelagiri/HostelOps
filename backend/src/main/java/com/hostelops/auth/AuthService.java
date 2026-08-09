package com.hostelops.auth;

import com.hostelops.auth.dto.LoginRequest;
import com.hostelops.auth.dto.LoginResponse;
import com.hostelops.auth.dto.UserDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.security.JwtService;
import com.hostelops.security.TokenRevocationService;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Signing in and signing out.
 *
 * <p>All three roles authenticate through this one method. There is no branch on role anywhere in
 * this class - the role only determines which permissions {@link UserDto} reports and which
 * authorities the request later carries. That is what "no special-case login paths" means in
 * practice: a GUEST is a real user row with a real BCrypt password going through the identical
 * code, not a bypass.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A valid BCrypt hash of a value nobody knows, used only to burn the same CPU time when the
     * email does not exist. See {@link #login} for why.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenRevocationService revocationService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TokenRevocationService revocationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.revocationService = revocationService;
    }

    /**
     * Verifies credentials and issues an access token.
     *
     * <p>Two details are about not leaking information rather than about correctness:
     *
     * <p><strong>One error for two causes.</strong> An unknown email and a wrong password both
     * return {@code INVALID_CREDENTIALS}. Distinguishing them would turn this endpoint into an
     * oracle for "does an account exist for this address?" - which is a real disclosure for a
     * hostel system, where the answer reveals who lives here.
     *
     * <p><strong>Equal work either way.</strong> If the lookup misses we still run one BCrypt
     * comparison against a dummy hash. BCrypt is intentionally slow, so skipping it would make the
     * "no such user" path measurably faster, and that timing difference alone rebuilds the same
     * oracle the shared error message just closed.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(request.email());

        String hashToCheck = found.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (found.isEmpty() || !passwordMatches) {
            log.debug("Failed sign-in attempt for '{}'", request.email());
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = found.get();
        if (!user.isActive()) {
            // Same generic answer again - a deactivated account should not be discoverable either.
            log.debug("Sign-in refused for deactivated account '{}'", request.email());
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS);
        }

        JwtService.IssuedToken issued = jwtService.issue(user);
        log.info("User {} ({}) signed in", user.getId(), user.getRole());

        return new LoginResponse(issued.token(), issued.expiresAt(), UserDto.from(user));
    }

    /**
     * Invalidates the caller's current token immediately, rather than waiting out its remaining
     * lifetime.
     *
     * <p>Silently does nothing if the token cannot be parsed. Logout must never fail: a client
     * calling it is trying to end a session, and answering with an error would leave the UI unsure
     * whether it may clear its state. An unparseable token is not a valid credential anyway, so
     * there is nothing left to revoke.
     */
    public void logout(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtService.parse(bearerToken);
            UUID jti = UUID.fromString(claims.getId());
            Instant expiresAt = claims.getExpiration().toInstant();
            revocationService.revoke(jti, expiresAt);
            log.info("Token {} revoked by logout", jti);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Logout called with an unusable token: {}", e.getMessage());
        }
    }
}
