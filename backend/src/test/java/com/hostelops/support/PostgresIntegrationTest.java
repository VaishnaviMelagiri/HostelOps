package com.hostelops.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a genuine PostgreSQL.
 *
 * <p><strong>Why a real database and not H2.</strong> Everything this project claims about
 * concurrency rests on PARTIAL unique indexes - {@code CREATE UNIQUE INDEX ... WHERE status IN
 * (...)}. H2 has no such thing. A test on H2 would create the table, skip the index it could not
 * understand, run happily, and report success while proving precisely nothing. Testing the one
 * mechanism the whole design depends on against a database that does not implement it would be
 * worse than not testing it, because it would produce false confidence.
 *
 * <p>Testcontainers starts a throwaway Postgres in Docker and Flyway migrates it exactly as it
 * would migrate production.
 *
 * <p><strong>The singleton container pattern, and why it is needed here.</strong> The obvious setup
 * - {@code @Testcontainers} on the class plus {@code @Container} on the field - makes JUnit start
 * the container before each test class and stop it afterwards. That breaks as soon as a second test
 * class runs: Spring caches and REUSES the application context between classes (that is what makes
 * a suite fast), so the second class inherits a DataSource still pointing at the first container's
 * port, which no longer exists. The symptom is a pile of "Connection refused" errors in every class
 * but the first, which looks like a bug in the code under test and is not.
 *
 * <p>So the container is started once in a static initialiser and deliberately never stopped. It
 * outlives every test class, matching the lifetime of the cached Spring context. Testcontainers'
 * "ryuk" companion container removes it when the JVM exits, so nothing is left running.
 *
 * <p>{@code @ServiceConnection} (Spring Boot 3.1+) wires the container's URL, username and password
 * into the DataSource automatically - no manual property plumbing.
 *
 * <p><strong>Requires Docker to be running.</strong> Already a prerequisite of the project.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("hostelops_test")
                    .withUsername("hostelops")
                    .withPassword("hostelops");

    static {
        POSTGRES.start();
    }
}
