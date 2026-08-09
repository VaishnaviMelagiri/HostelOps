package com.hostelops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-Origin Resource Sharing.
 *
 * <p>Why this file has to exist at all: in development the React app is served by Vite on
 * {@code http://localhost:5173} while this API runs on {@code http://localhost:8080}. Different
 * port means different <em>origin</em>, and browsers refuse to let JavaScript read a response from
 * a different origin unless that server explicitly opts in with CORS headers. Without this class
 * the request genuinely reaches Spring and Spring genuinely answers - the browser then discards
 * the response and logs a CORS error, which is a confusing first bug to hit.
 *
 * <p>{@code @Configuration} means "this class contributes settings/beans to the application
 * context". Implementing {@link WebMvcConfigurer} lets us adjust Spring MVC's defaults without
 * replacing them wholesale.
 *
 * <p>The allowed origins come from configuration rather than being hardcoded, so Phase 12 can point
 * this at the deployed Vercel domain by setting one environment variable.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${hostelops.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // Needed from Phase 2 so the browser may send the Authorization: Bearer header.
                .allowCredentials(true);
    }
}
