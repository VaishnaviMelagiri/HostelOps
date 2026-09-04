package com.hostelops.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates a WebSocket connection.
 *
 * <p><strong>Why the token cannot travel in a header the way it does for REST.</strong> The browser
 * WebSocket API gives no way to set arbitrary HTTP headers on the handshake - there is no
 * equivalent of {@code fetch(url, {headers})}. So the JWT is sent in the first STOMP frame instead:
 * the client puts it in a CONNECT header, and this interceptor reads it there.
 *
 * <p>Authentication happens ONCE, at CONNECT, and the resulting Principal is attached to the
 * session for its lifetime. That is a real difference from REST, where every request is
 * re-authenticated: a token revoked by logout does not tear down an already-open socket. Acceptable
 * here because the socket only ever carries status updates the user could see by refreshing the
 * page anyway - and it is worth knowing rather than assuming otherwise. A stricter design would
 * re-check periodically and close the session.
 *
 * <p>The Principal's name is the user's database id, which is what
 * {@code convertAndSendToUser(userId, ...)} addresses.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    /** Destinations under here require a permission, not merely a valid session. */
    private static final String ADMIN_TOPIC_PREFIX = "/topic/admin/";

    private final JwtService jwtService;
    private final TokenRevocationService revocationService;
    private final AppUserDetailsService userDetailsService;

    public StompAuthChannelInterceptor(JwtService jwtService,
                                       TokenRevocationService revocationService,
                                       AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.revocationService = revocationService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
            return message;
        }

        if (!StompCommand.CONNECT.equals(accessor.getCommand())) {
            // Other frames ride on the session established at CONNECT.
            return message;
        }

        String token = firstAuthorizationHeader(accessor);
        if (token == null) {
            // Refusing outright - unlike the REST filter, which leaves a request anonymous and lets
            // a later filter decide. Here there is no later filter and no anonymous use for a
            // socket, so an unauthenticated CONNECT is simply rejected.
            throw new IllegalArgumentException("A valid Authorization header is required to connect");
        }

        try {
            Claims claims = jwtService.parse(token);

            if (revocationService.isRevoked(UUID.fromString(claims.getId()))) {
                throw new IllegalArgumentException("Token has been revoked");
            }

            AppUserPrincipal principal =
                    userDetailsService.loadUserByUsername(claims.get("email", String.class));
            if (!principal.isEnabled()) {
                throw new IllegalArgumentException("Account is deactivated");
            }

            // The Principal name becomes the address for /user/queue/... deliveries. Using the
            // database id rather than the email keeps the destination stable if an email changes.
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    String.valueOf(principal.getId()), null, principal.getAuthorities()));

            log.debug("WebSocket connected for user {} ({})", principal.getId(), principal.getRole());
            return message;

        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            // UsernameNotFoundException is listed explicitly: a token whose user has since been
            // deleted is an authentication failure like any other, but it is neither a JwtException
            // nor an IllegalArgumentException, so it previously escaped this handler uncaught. The
            // connection was still refused - the frame failed either way - but it took a different
            // path, skipping this log line and surfacing an unnormalised exception. Every reason to
            // refuse a CONNECT should look the same from outside.
            log.debug("Rejecting WebSocket CONNECT: {}", e.getMessage());
            throw new IllegalArgumentException("WebSocket authentication failed");
        }
    }

    /**
     * Checks whether this session may subscribe to the destination it asked for.
     *
     * <p>Almost nothing needs gating here, and it is worth being clear why:
     * <ul>
     *   <li>{@code /topic/floors/**} is public to every signed-in user, and its payload contains no
     *       identities, so there is nothing to withhold from anyone.</li>
     *   <li>{@code /user/queue/**} is isolated by Spring itself - it rewrites the destination per
     *       session, so a client cannot address anyone else's queue whatever it asks for. That
     *       protection is structural and needs no check.</li>
     *   <li>{@code /topic/admin/**} is a genuine broadcast that only one role should receive, so it
     *       is the one destination that must be checked against a permission.</li>
     * </ul>
     *
     * <p>The check names the permission, not the role, exactly like every {@code @PreAuthorize} in
     * the project - so a future warden role able to read the queue would gain this subscription
     * with no change here.
     */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(ADMIN_TOPIC_PREFIX)) {
            return;
        }

        if (!hasAuthority(accessor.getUser(), Permission.REQUEST_QUEUE_READ)) {
            log.debug("Refusing subscription to {} - session lacks {}",
                    destination, Permission.REQUEST_QUEUE_READ);
            // Throwing here aborts the SUBSCRIBE frame; the client gets a STOMP ERROR and no
            // messages are ever routed to it. Silently ignoring the frame instead would leave the
            // client believing it was subscribed and waiting forever.
            throw new IllegalArgumentException("Not permitted to subscribe to " + destination);
        }
    }

    private static boolean hasAuthority(Principal principal, Permission permission) {
        if (!(principal instanceof Authentication authentication)) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (permission.name().equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static String firstAuthorizationHeader(StompHeaderAccessor accessor) {
        List<String> values = accessor.getNativeHeader("Authorization");
        if (values == null || values.isEmpty()) {
            return null;
        }
        String header = values.get(0);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
