package com.hostelops.security;

import com.hostelops.user.Role;
import com.hostelops.user.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Who may subscribe to what.
 *
 * <p>The audit flagged this as the one security control with no automated test: the SUBSCRIBE gate
 * on {@code /topic/admin/**} had been verified by hand with three real clients (STUDENT refused,
 * GUEST refused, ADMIN allowed) and nothing more. A manual check proves it worked once; it guards
 * nothing against the next refactor.
 *
 * <p>Tested by driving the interceptor directly with crafted frames rather than opening a real
 * socket. That keeps it fast and, more usefully, isolates the authorization DECISION - the
 * end-to-end wiring was what the manual run confirmed.
 */
class StompSubscriptionGateTest {

    private JwtService jwtService;
    private TokenRevocationService revocationService;
    private AppUserDetailsService userDetailsService;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        revocationService = mock(TokenRevocationService.class);
        userDetailsService = mock(AppUserDetailsService.class);
        interceptor = new StompAuthChannelInterceptor(
                jwtService, revocationService, userDetailsService);
    }

    private static AppUserPrincipal principal(Role role) {
        User user = switch (role) {
            case STUDENT -> User.student("s@hostelops.demo", "hash", "Demo Student",
                    "GATE-001", "B.E. CSE");
            case ADMIN -> User.admin("a@hostelops.demo", "hash", "Demo Admin");
            case GUEST -> User.guest("g@hostelops.demo", "hash", "Guest Reviewer");
        };
        return new AppUserPrincipal(user);
    }

    /** A SUBSCRIBE frame from an already-connected session belonging to the given role. */
    private static Message<byte[]> subscribeAs(Role role, String destination) {
        AppUserPrincipal user = principal(role);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                String.valueOf(user.getId()), null, user.getAuthorities()));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> connectWith(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.addNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("ADMIN may subscribe to the admin queue topic")
    void adminMaySubscribeToTheAdminTopic() {
        assertThatCode(() -> interceptor.preSend(
                subscribeAs(Role.ADMIN, "/topic/admin/queue"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("STUDENT may NOT subscribe to the admin queue topic")
    void studentMayNotSubscribeToTheAdminTopic() {
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeAs(Role.STUDENT, "/topic/admin/queue"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/topic/admin/queue");
    }

    @Test
    @DisplayName("GUEST may NOT subscribe to the admin queue topic")
    void guestMayNotSubscribeToTheAdminTopic() {
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeAs(Role.GUEST, "/topic/admin/queue"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the gate covers the whole /topic/admin/ prefix, not just the one known path")
    void theGateIsByPrefix() {
        // A topic added later under the same prefix inherits the restriction rather than shipping
        // open by accident - the failure mode of listing exact paths.
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeAs(Role.STUDENT, "/topic/admin/something-added-later"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("floor topics need no permission - they carry no identities to withhold")
    void floorTopicsAreOpenToEverySignedInRole() {
        for (Role role : Role.values()) {
            assertThatCode(() -> interceptor.preSend(
                    subscribeAs(role, "/topic/floors/B-FF"), null))
                    .as("%s should be able to watch a floor", role)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the private queue needs no gate - Spring isolates it per session")
    void privateQueueNeedsNoGate() {
        // Every client subscribes to this identical string; Spring rewrites it per session before
        // it reaches the broker, so a client cannot address anyone else's queue whatever it asks
        // for. Adding a check here would imply the isolation was ours to enforce, and it is not.
        assertThatCode(() -> interceptor.preSend(
                subscribeAs(Role.STUDENT, "/user/queue/requests"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a CONNECT with no Authorization header is refused")
    void connectWithoutCredentialsIsRefused() {
        assertThatThrownBy(() -> interceptor.preSend(connectWith(null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Authorization");
    }

    @Test
    @DisplayName("a CONNECT with a revoked token is refused")
    void connectWithARevokedTokenIsRefused() {
        Claims claims = mock(Claims.class);
        UUID jti = UUID.randomUUID();
        given(claims.getId()).willReturn(jti.toString());
        given(claims.get("email", String.class)).willReturn("s@hostelops.demo");
        given(claims.getExpiration()).willReturn(new Date(System.currentTimeMillis() + 60_000));
        given(jwtService.parse(anyString())).willReturn(claims);
        given(revocationService.isRevoked(any())).willReturn(true);

        // A logged-out token must not be able to open a NEW socket. The already-open-session case
        // is a documented limitation; this is the case that is actually closed.
        assertThatThrownBy(() -> interceptor.preSend(connectWith("Bearer some.jwt.value"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a CONNECT whose user no longer exists is refused, not crashed")
    void connectForAMissingUserIsRefused() {
        Claims claims = mock(Claims.class);
        given(claims.getId()).willReturn(UUID.randomUUID().toString());
        given(claims.get("email", String.class)).willReturn("gone@hostelops.demo");
        given(jwtService.parse(anyString())).willReturn(claims);
        given(revocationService.isRevoked(any())).willReturn(false);
        given(userDetailsService.loadUserByUsername(anyString()))
                .willThrow(new UsernameNotFoundException("gone"));

        assertThatThrownBy(() -> interceptor.preSend(connectWith("Bearer some.jwt.value"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the gate names the permission, not the role")
    void gateIsPermissionBased() {
        // ADMIN passes because it holds REQUEST_QUEUE_READ, not because it is called ADMIN. A
        // future warden role granted that permission would subscribe with no change to the gate.
        assertThat(RolePermissions.forRole(Role.ADMIN)).contains(Permission.REQUEST_QUEUE_READ);
        assertThat(RolePermissions.forRole(Role.STUDENT)).doesNotContain(Permission.REQUEST_QUEUE_READ);
        assertThat(RolePermissions.forRole(Role.GUEST)).doesNotContain(Permission.REQUEST_QUEUE_READ);
    }
}
