package com.hostelops.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Turns an {@code Authorization: Bearer &lt;token&gt;} header into an authenticated request.
 *
 * <h2>Where this sits - the filter chain</h2>
 * Every HTTP request into a Spring Boot app passes through an ordered list of servlet filters
 * before any controller runs. Spring Security installs its own chain of them, and this class is
 * inserted near the front (see SecurityConfig). Roughly:
 *
 * <pre>
 *   request
 *     -> CorsFilter                 is this cross-origin call allowed at all?
 *     -> JwtAuthFilter (this class) is there a valid token? if so, record who it belongs to
 *     -> AuthorizationFilter        does that user hold the permission this endpoint requires?
 *     -> DispatcherServlet -> your @RestController method
 * </pre>
 *
 * <p>Two habits of this design are worth naming, because they are the parts people get wrong:
 *
 * <p><strong>1. This filter never rejects anything.</strong> If there is no token, or the token is
 * bad, it simply leaves the request unauthenticated and calls {@code filterChain.doFilter(...)}
 * anyway. Deciding whether an anonymous request is acceptable is a later filter's job - and it must
 * be, because {@code POST /api/auth/login} and {@code GET /api/health} are supposed to work without
 * a token. A filter that threw on a missing header would make logging in impossible.
 *
 * <p><strong>2. Authorities come from the database, not from the token.</strong> The token's
 * {@code role} claim is used only for logging. On every request the user is re-read and their
 * permissions recomputed from {@link RolePermissions}. That costs one primary-key lookup and buys
 * two things a self-contained token cannot give: deactivating an account takes effect immediately
 * rather than up to 15 minutes later, and a permission change never requires anyone to sign in
 * again.
 *
 * <p>{@code OncePerRequestFilter} guarantees the logic runs a single time per request even when the
 * request is internally forwarded - without it, a forward re-runs the filter and the work is done
 * twice.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenRevocationService revocationService;
    private final AppUserDetailsService userDetailsService;

    public JwtAuthFilter(JwtService jwtService,
                         TokenRevocationService revocationService,
                         AppUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.revocationService = revocationService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractBearerToken(request);

        // No token, or somebody is already authenticated: nothing for us to do. Pass it along.
        if (token == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            authenticate(token, request);
        } catch (JwtException e) {
            // Malformed, tampered with, signed by someone else, or expired - all the same answer:
            // this request is simply not authenticated. DEBUG, not WARN: an expired token during
            // normal use is routine, and logging it loudly would bury real problems.
            log.debug("Rejecting token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        } catch (UsernameNotFoundException e) {
            // The token is valid but its user is gone - deleted, or renamed to a different email.
            // Routine for a stale token, so DEBUG. It was previously caught by the generic branch
            // below and logged as "Unexpected failure", which would make an ordinary stale session
            // look like an incident in production logs.
            log.debug("Rejecting token for a user that no longer exists: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        } catch (Exception e) {
            log.warn("Unexpected failure while authenticating a request", e);
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        // 1. Verify the signature and expiry. Throws JwtException if either fails.
        Claims claims = jwtService.parse(token);

        // 2. Has this specific token been revoked by a logout? Signature and expiry alone cannot
        //    tell us - a logged-out token is still perfectly well-formed and still unexpired.
        UUID jti = UUID.fromString(claims.getId());
        if (revocationService.isRevoked(jti)) {
            log.debug("Rejecting token {}: revoked by logout", jti);
            return;
        }

        // 3. Load the user fresh. This is also what makes a deactivated account stop working
        //    immediately rather than when their current token happens to expire.
        AppUserPrincipal principal = userDetailsService.loadUserByUsername(claims.get("email", String.class));
        if (!principal.isEnabled()) {
            log.debug("Rejecting token for deactivated user {}", principal.getUsername());
            return;
        }

        // 4. Record the authenticated user for the rest of this request. The three-argument
        //    constructor produces an *authenticated* token; the two-argument one produces an
        //    unauthenticated request-to-authenticate. Using the wrong one here is a classic
        //    Spring Security mistake, and it fails in the confusing direction: everything appears
        //    to work until an authorization check quietly denies a user who should have passed.
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
