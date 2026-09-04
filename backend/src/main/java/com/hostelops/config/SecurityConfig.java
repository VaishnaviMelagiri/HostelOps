package com.hostelops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.common.ApiError;
import com.hostelops.common.ErrorCode;
import com.hostelops.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The security rules for the whole API.
 *
 * <p>Adding the {@code spring-boot-starter-security} dependency instantly locks down every endpoint
 * behind a generated password and a login form. This class replaces those defaults with the ones
 * this project actually wants.
 *
 * <p>{@code @EnableMethodSecurity} switches on {@code @PreAuthorize}, which is where the
 * per-endpoint permission checks live from Phase 3 onward. Two layers of authorization is
 * deliberate rather than redundant: the URL rules below are a coarse net ("everything under /api
 * needs a signed-in user"), and {@code @PreAuthorize} on the method states the specific permission
 * required. A new endpoint added to an existing controller is therefore never accidentally public -
 * the worst case is that it requires authentication but not the right permission, which is a much
 * smaller mistake than being wide open.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CorsConfigurationSource corsConfigurationSource;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          CorsConfigurationSource corsConfigurationSource,
                          ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.corsConfigurationSource = corsConfigurationSource;
        this.objectMapper = objectMapper;
    }

    /**
     * BCrypt: a deliberately slow hash with a per-password random salt built into the output.
     *
     * <p>"Slow" is the feature. A fast hash like SHA-256 lets an attacker with a stolen database
     * try billions of guesses per second; BCrypt's work factor makes each guess cost milliseconds,
     * turning a feasible offline attack into an infeasible one. The salt is why two users with the
     * same password get different hashes, so cracking one reveals nothing about the other.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))

            // CSRF protection defends against a browser automatically attaching AMBIENT
            // credentials - cookies it sends whether or not the page meant to. Our credential is a
            // Bearer token that JavaScript must attach deliberately, so a forged cross-site form
            // post carries no credential at all and there is nothing for CSRF to protect.
            // Disabling it here is reasoned, not a shortcut; it would be wrong with cookie auth.
            .csrf(csrf -> csrf.disable())

            // No HttpSession, ever. Each request proves who it is with its own token, which is what
            // lets the backend scale to several instances with no shared session store.
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                    // Public: you cannot require a token to obtain a token.
                    .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                    // Public, and deliberately so. Requiring authentication here makes logout
                    // fail with 401 the second time it is called - the token was revoked by the
                    // first call, so the filter chain rejects it before the controller can answer.
                    // "Sign out" must be idempotent: a retry, a double-click, or a second tab
                    // must all end with the session gone and a clean 204, never an error telling
                    // the user something went wrong while they were leaving.
                    // There is no security cost: revoking a token requires possessing it, and
                    // anyone holding it could simply use it instead. Revocation is strictly the
                    // less harmful of the two things a stolen token permits.
                    .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                    // Public: a health check that needs credentials is useless to a load balancer.
                    .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                    // Browsers send a preflight OPTIONS with no Authorization header by design.
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                    // The WebSocket HANDSHAKE is permitted, and that is not a hole. The browser
                    // WebSocket API cannot set an Authorization header on the handshake request -
                    // there is simply no API for it - so the token travels in the first STOMP
                    // frame instead, where StompAuthChannelInterceptor verifies it and refuses to
                    // establish the session without a valid one. Authentication moves one layer
                    // up rather than being skipped.
                    .requestMatchers("/ws/**").permitAll()
                    // Everything else requires a valid token. Note this is deny-by-default: a new
                    // endpoint is protected the moment it exists, without anyone remembering to
                    // add it here.
                    .anyRequest().authenticated())

            // Slot our filter in before the username/password filter, so a request arriving with a
            // Bearer token is already authenticated by the time any authorization check runs.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

            .exceptionHandling(ex -> ex
                    // 401 - not signed in, or the token is bad/expired/revoked.
                    .authenticationEntryPoint((request, response, authException) ->
                            writeError(response, ErrorCode.UNAUTHENTICATED))
                    // 403 - signed in, but lacking the required permission. This is the response a
                    // GUEST gets from POST /api/requests, and an ADMIN from /api/me/allocation.
                    .accessDeniedHandler((request, response, accessDeniedException) ->
                            writeError(response, ErrorCode.FORBIDDEN)));

        return http.build();
    }

    /**
     * Writes the project's standard error envelope from inside the filter chain.
     *
     * <p>This has to exist separately from {@code GlobalExceptionHandler} because security failures
     * happen in servlet filters, which run before the dispatcher servlet - so no
     * {@code @RestControllerAdvice} ever sees them. Without this, a 401 would return Spring's
     * default HTML error page, and the frontend's {@code response.json()} would throw a parse error
     * while trying to read it. The client would then report a mysterious JSON syntax error when the
     * real problem was simply an expired session.
     */
    private void writeError(HttpServletResponse response, ErrorCode code) throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code));
    }
}
