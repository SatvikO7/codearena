package com.codearena;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and real Redis, shared by every integration test in the JVM.
 *
 * <p>The containers are started once from a static initialiser and never explicitly
 * stopped — the singleton-container pattern — rather than being declared with
 * {@code @Container} and {@code @Testcontainers}. That annotation pair ties a container's
 * lifecycle to a single test <em>class</em>: it stops the container in the class's
 * teardown, so the next class in the run finds a dead database and every test in it fails
 * with "connection refused". Inheriting the annotations from a shared base does not change
 * that. Started here instead, the containers live for the whole JVM, and Testcontainers'
 * Ryuk sidecar removes them when it exits.
 *
 * <p>There is deliberately no in-memory substitute. The schema depends on PostgreSQL
 * behaviour that H2 does not reproduce — unique indexes over {@code lower(...)} for
 * case-insensitive identity, functional and trigram indexes, and the index names the
 * registration and authoring paths read to tell one constraint violation from another.
 * A test against a database that disagrees with production would be worse than no test.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Cost 12 is right for production and far too slow to pay on every test login.
        // The encoder under test is the same DelegatingPasswordEncoder either way; only
        // the work factor differs.
        registry.add("codearena.security.bcrypt-strength", () -> 4);
    }

    /**
     * Empties every table, in an order the foreign keys allow.
     *
     * <p>The containers are shared by the whole JVM, so each test class starts with
     * whatever the previous one left behind. Every suite therefore clears the database in
     * its setup — and it has to clear <em>all</em> of it, not the tables it happens to care
     * about: contest_problems references problems with ON DELETE RESTRICT, so a suite that
     * deleted only problems would fail the moment a contest test had run before it.
     *
     * <p>Centralised here for that reason. When a later phase adds a table, one method
     * changes rather than five setups that fail one run at a time.
     *
     * <p>Order matters and is dependency-first: children before parents.
     */
    protected static void resetDatabase(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM submission_test_results");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("DELETE FROM contest_participants");
        jdbc.update("DELETE FROM contest_problems");
        jdbc.update("DELETE FROM contests");
        jdbc.update("DELETE FROM problem_test_cases");
        jdbc.update("DELETE FROM problem_examples");
        jdbc.update("DELETE FROM problem_tags");
        jdbc.update("DELETE FROM problems");
        jdbc.update("DELETE FROM users");
    }
}
