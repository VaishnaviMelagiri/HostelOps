package com.hostelops.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only endpoint in Phase 1. It exists to prove the three pieces are really connected:
 * browser -> Spring Boot -> Postgres.
 *
 * <p>{@code @RestController} = {@code @Controller} + {@code @ResponseBody}: every method's return
 * value is serialised straight to the HTTP response body as JSON, rather than being treated as the
 * name of an HTML template to render.
 *
 * <p>{@code @RequestMapping("/api")} at class level prefixes every route in this class, so the
 * method below answers {@code GET /api/health}.
 *
 * <p>Note how thin this is: it calls the service and translates the result into an HTTP status.
 * All logic lives in {@link HealthService}. That separation is the rule for the whole project - by
 * Phase 5 the controllers still look like this and the interesting code is all in services.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthDto> health() {
        HealthDto result = healthService.check();

        // 503 SERVICE_UNAVAILABLE, not 200-with-a-DOWN-body: a load balancer or Render health check
        // reads the status code, not the JSON. Saying "DOWN" in a 200 response means every
        // automated system upstream believes this instance is healthy.
        HttpStatus status = result.isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(result);
    }
}
