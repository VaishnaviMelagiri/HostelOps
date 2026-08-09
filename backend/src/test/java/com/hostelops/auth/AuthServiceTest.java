package com.hostelops.auth;

import com.hostelops.auth.dto.LoginRequest;
import com.hostelops.auth.dto.LoginResponse;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.security.JwtService;
import com.hostelops.security.TokenRevocationService;
import com.hostelops.user.Role;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Sign-in rules, with a stubbed repository and a real BCrypt encoder.
 *
 * <p>The encoder is deliberately the real one rather than a mock: password verification is the
 * single thing this class exists to get right, and stubbing it would test only that the code calls
 * a method.
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private TokenRevocationService revocationService;
    private AuthService authService;

    private static final String CORRECT_PASSWORD = "Student@123";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        revocationService = mock(TokenRevocationService.class);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        JwtService jwtService = new JwtService(
                "a-test-signing-key-that-is-comfortably-over-32-bytes", Duration.ofMinutes(15));

        authService = new AuthService(userRepository, passwordEncoder, jwtService, revocationService);
    }

    private User activeStudent() {
        User user = User.student(
                "student@hostelops.demo",
                new BCryptPasswordEncoder().encode(CORRECT_PASSWORD),
                "Demo Student", "1MS22CS001", "B.E. Computer Science");
        ReflectionTestUtils.setField(user, "id", 7L);
        return user;
    }

    @Test
    @DisplayName("correct credentials return a token and the user's resolved permissions")
    void signsInWithCorrectCredentials() {
        given(userRepository.findByEmailIgnoreCase("student@hostelops.demo"))
                .willReturn(Optional.of(activeStudent()));

        LoginResponse response = authService.login(
                new LoginRequest("student@hostelops.demo", CORRECT_PASSWORD));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.user().role()).isEqualTo(Role.STUDENT);
        assertThat(response.user().permissions())
                .contains("ROOM_READ", "REQUEST_CREATE", "REQUEST_CANCEL_OWN", "ALLOCATION_READ_OWN")
                .doesNotContain("REQUEST_APPROVE", "BED_BLOCK");
    }

    @Test
    @DisplayName("a wrong password is refused")
    void rejectsAWrongPassword() {
        given(userRepository.findByEmailIgnoreCase(anyString()))
                .willReturn(Optional.of(activeStudent()));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("student@hostelops.demo", "Wrong@123")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("an unknown email gives exactly the same error as a wrong password")
    void doesNotRevealWhetherAnAccountExists() {
        given(userRepository.findByEmailIgnoreCase(anyString())).willReturn(Optional.empty());

        // Same code, same message. Anything that distinguished these two cases would turn login
        // into a way to discover which email addresses have accounts here.
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@hostelops.demo", "whatever")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("a deactivated account cannot sign in, and is not distinguishable either")
    void rejectsADeactivatedAccount() {
        User user = activeStudent();
        ReflectionTestUtils.setField(user, "active", false);
        given(userRepository.findByEmailIgnoreCase(anyString())).willReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("student@hostelops.demo", CORRECT_PASSWORD)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("email matching is case-insensitive, like the unique index")
    void emailLookupIsCaseInsensitive() {
        // The repository method itself lowercases both sides; this asserts the service passes the
        // address through to it untouched rather than pre-normalising and diverging from the index.
        given(userRepository.findByEmailIgnoreCase("Student@HostelOps.Demo"))
                .willReturn(Optional.of(activeStudent()));

        LoginResponse response = authService.login(
                new LoginRequest("Student@HostelOps.Demo", CORRECT_PASSWORD));

        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("logout revokes the token it was called with")
    void logoutRevokesTheToken() {
        given(userRepository.findByEmailIgnoreCase(anyString()))
                .willReturn(Optional.of(activeStudent()));
        String token = authService.login(
                new LoginRequest("student@hostelops.demo", CORRECT_PASSWORD)).accessToken();

        authService.logout(token);

        verify(revocationService).revoke(any(), any());
    }

    @Test
    @DisplayName("logout never fails, even on a garbage token")
    void logoutIsForgiving() {
        // A client ending its session must always be able to clear its own state. Erroring here
        // would leave the UI unsure whether it is allowed to.
        authService.logout("not-a-jwt");
        authService.logout(null);
        authService.logout("");

        verify(revocationService, never()).revoke(any(), any());
    }
}
