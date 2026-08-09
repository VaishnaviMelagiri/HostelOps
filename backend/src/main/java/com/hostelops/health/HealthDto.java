package com.hostelops.health;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Response shape for {@code GET /api/health}.
 *
 * <p>This is a Java {@code record} - a compact, immutable data carrier. Records are ideal for DTOs
 * because they cannot be mutated after construction, so a controller can never accidentally reshape
 * a response halfway through. Jackson (Spring's JSON library) serialises the components by name,
 * so this class produces exactly:
 *
 * <pre>{ "status": "UP", "database": "UP", "detail": "connection verified" }</pre>
 *
 * @param status   overall service status: UP only when every dependency is UP
 * @param database result of a real round trip to Postgres
 * @param detail   human-readable explanation, especially useful when something is DOWN
 */
public record HealthDto(String status, String database, String detail) {

    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    public static HealthDto up() {
        return new HealthDto(UP, UP, "connection verified");
    }

    public static HealthDto databaseDown(String detail) {
        return new HealthDto(DOWN, DOWN, detail);
    }

    /**
     * Convenience for the controller only - deliberately NOT part of the JSON.
     *
     * <p>Jackson treats any {@code isX()} / {@code getX()} method as a property to serialise, so
     * without {@code @JsonIgnore} this helper would silently add {@code "up": true} to the response
     * body. That is the whole reason for the annotation: a method added for internal convenience
     * should never quietly become part of a public API contract.
     */
    @JsonIgnore
    public boolean isUp() {
        return UP.equals(status);
    }
}
