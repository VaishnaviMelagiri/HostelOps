package com.hostelops.auth;

import com.hostelops.auth.dto.LoginRequest;
import com.hostelops.auth.dto.LoginResponse;
import com.hostelops.auth.dto.UserDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign in, sign out, and "who am I".
 *
 * <p>One login endpoint for all three roles - no {@code /admin/login}, no role parameter. The
 * server decides what a user may do from their stored role; the client never asserts it.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    /** Public - permitted in SecurityConfig, since you cannot need a token to get a token. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Revokes the token used to make this call.
     *
     * <p>Returns 204 No Content: the action succeeded and there is nothing meaningful to send back.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring("Bearer ".length()).trim()
                : null;
        authService.logout(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * The current user, re-read from the database.
     *
     * <p>{@code @AuthenticationPrincipal} injects whatever {@link com.hostelops.security.JwtAuthFilter}
     * placed in the security context for this request - so the method never parses a token itself
     * and cannot be reached at all without a valid one.
     *
     * <p>Used by the frontend on page load: the browser has a token in memory but no idea who it
     * belongs to, and this is how it finds out without trusting anything it decoded locally.
     */
    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AppUserPrincipal principal) {
        // studentCode and course are not carried in the principal (the filter keeps it minimal),
        // so read the row. Cheap, and guarantees a freshly changed course is reflected at once.
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED));
        return UserDto.from(user);
    }
}
