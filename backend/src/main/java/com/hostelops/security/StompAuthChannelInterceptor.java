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
import org.springframework.stereotype.Component;

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

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            // Only the CONNECT frame carries credentials; SUBSCRIBE and the rest ride on the
            // session established here.
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

        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejecting WebSocket CONNECT: {}", e.getMessage());
            throw new IllegalArgumentException("WebSocket authentication failed");
        }
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
