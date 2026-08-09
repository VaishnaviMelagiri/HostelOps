package com.hostelops.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * Answers "is this service actually usable right now?" - which for HostelOps means "can it reach
 * Postgres?", because every meaningful operation in this system is a database write.
 *
 * <p>{@code @Service} marks this as a Spring-managed bean, so Spring creates one instance at
 * startup and hands it to anything that asks for a {@code HealthService} (see the controller).
 *
 * <p>The constructor takes a {@link JdbcTemplate} and Spring supplies it automatically - this is
 * <strong>constructor injection</strong>. A class with a single constructor needs no
 * {@code @Autowired} annotation; Spring uses that constructor by default. Constructor injection is
 * preferred over field injection because the object is impossible to construct in an invalid state,
 * and a plain unit test can pass a stub in without Spring being involved at all.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    /** Seconds Postgres is allowed to spend executing the probe once a connection is in hand. */
    private static final int QUERY_TIMEOUT_SECONDS = 3;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Takes the {@link DataSource} rather than the shared {@code JdbcTemplate} bean so this class
     * can own a template with its own short query timeout, without imposing that timeout on the
     * real application queries that will arrive from Phase 3 onward.
     *
     * <p>Two different clocks have to be bounded for this endpoint to fail fast, and it is easy to
     * set one and believe you are covered:
     * <ul>
     *   <li>waiting for a free connection from the pool - bounded by Hikari's
     *       {@code connection-timeout} in application.yml;</li>
     *   <li>waiting for the database to answer once connected - bounded here.</li>
     * </ul>
     */
    public HealthService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    /**
     * Runs a real query against the database rather than returning a hardcoded 200.
     *
     * <p>This distinction matters for deployment (Phase 12): a health check that always returns 200
     * tells Render the service is fine while every actual request is failing on a dead connection
     * pool. {@code SELECT 1} is the cheapest statement that proves a connection was borrowed from
     * the pool, sent to Postgres, and answered.
     */
    public HealthDto check() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (result != null && result == 1) {
                return HealthDto.up();
            }
            return HealthDto.databaseDown("unexpected response from SELECT 1: " + result);
        } catch (Exception e) {
            // Log the stack trace for us, but return only the message to the caller - internal
            // details of a database failure are not something an HTTP client should receive.
            log.error("Health check failed: database is unreachable", e);
            return HealthDto.databaseDown(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
