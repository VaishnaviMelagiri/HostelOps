package com.hostelops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Cross-Origin Resource Sharing.
 *
 * <p>Why this exists: in development the React app is served by Vite on
 * {@code http://localhost:5173} while this API runs on {@code http://localhost:8080}. A different
 * port means a different <em>origin</em>, and browsers refuse to let JavaScript read a response
 * from another origin unless the server explicitly opts in. Without this, the request genuinely
 * reaches Spring and Spring genuinely answers - the browser then discards the response and logs a
 * CORS error, which is a confusing bug to meet on your first day with a split frontend/backend.
 *
 * <p><strong>Changed in Phase 2:</strong> this used to configure CORS through
 * {@code WebMvcConfigurer#addCorsMappings}, which is applied by Spring MVC. Spring Security's
 * filters run <em>before</em> MVC, so an unauthenticated cross-origin request could be rejected by
 * the security chain before the MVC CORS handling ever ran - and a 401 without CORS headers shows
 * up in the browser as a CORS error, sending you off debugging entirely the wrong thing. Exposing a
 * {@link CorsConfigurationSource} bean instead lets Spring Security's own {@code CorsFilter} use
 * the same configuration, so preflights and error responses both carry the right headers.
 */
@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${hostelops.cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Specific origins, never "*". A wildcard is incompatible with allowCredentials, and the
        // deployed origin is known anyway - Phase 12 adds the Vercel URL to the same variable.
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);

        // How long a browser may cache the preflight (OPTIONS) result, so it does not send one
        // before every single request.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
